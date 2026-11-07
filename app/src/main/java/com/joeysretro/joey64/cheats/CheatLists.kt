package com.joeysretro.joey64.cheats

import android.content.Context
import android.util.Log
import com.joeysretro.joey64.BuildConfig
import com.joeysretro.joey64.JLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * The cheat lists (plan, Phase 6; decided 2026-11-05): nothing bundled, every list downloaded
 * from `joey64-cheats` (its `release` branch, `dist/`) and kept in `files/cheatlists/`. The
 * index says which sources have a list for a game; a game's lists are fetched the first time
 * they're wanted (Cheats opened, or the scan's pass), then kept. The index is fetched again when
 * it's more than a week old; when it changed, the kept lists go, so they're fetched fresh.
 */
class CheatLists(private val context: Context) {

    sealed interface Outcome {
        /** [cheats] merged from every source, in the order they're shown. */
        data class Found(val cheats: List<Cheat>) : Outcome
        /** No source has cheats for this game. */
        data object None : Outcome
        /** Not kept and couldn't be fetched. */
        data object Offline : Outcome
    }

    private val dir get() = File(context.filesDir, "cheatlists")

    /** [keys] (see [CheatLogic.keys]): one game's cheats, fetched now if they aren't kept and [download] is on. */
    suspend fun forGame(keys: List<String>, download: Boolean): Outcome = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext Outcome.None
        val index = try { index(download) } catch (e: IOException) {
            JLog.w(TAG, "can't get the cheat index: $e")
            return@withContext Outcome.Offline
        } ?: return@withContext Outcome.Offline
        val wanted = keys.flatMap { k -> index[k].orEmpty().keys.map { src -> src to k } }
        if (wanted.isEmpty()) return@withContext Outcome.None
        val lists = HashMap<String, MutableList<Cheat>>()
        var missing = false
        for ((src, k) in wanted) {
            val text = try { list(src, k, download) } catch (e: IOException) {
                JLog.w(TAG, "can't get $src/$k: $e"); null
            }
            if (text == null) { missing = true; continue }
            try {
                lists.getOrPut(src) { ArrayList() } += CheatLogic.parseList(text)
            } catch (e: Exception) {
                JLog.e(TAG, "bad list $src/$k" + "\n" + Log.getStackTraceString(e))
                File(dir, "$src/$k.json").delete()
            }
        }
        if (lists.isEmpty()) return@withContext if (missing) Outcome.Offline else Outcome.None
        val merged = CheatLogic.merge(lists)
        if (merged.isEmpty()) Outcome.None else Outcome.Found(merged)
    }

    /** The scan's pass: every game's lists fetched ahead, so Cheats opens at once and works offline. */
    suspend fun prefetch(games: Collection<List<String>>) = withContext(Dispatchers.IO) {
        val index = try { index(true) } catch (e: IOException) { JLog.w(TAG, "cheats: no index: $e"); return@withContext } ?: return@withContext
        var got = 0
        for (keys in games) for (k in keys) for (src in index[k].orEmpty().keys) {
            val f = File(dir, "$src/$k.json")
            if (f.exists()) continue
            try { list(src, k, true); got++ } catch (e: IOException) {
                JLog.w(TAG, "cheats: can't get $src/$k: $e")
                if (e is UnknownHostException || e is ConnectException || e is SocketTimeoutException) return@withContext
            }
        }
        if (got > 0) JLog.i(TAG, "cheats: $got lists downloaded for ${games.size} games")
    }

    @Volatile private var cached: Pair<Long, Map<String, Map<String, Int>>>? = null

    /** The kept index while it's fresh; else a new one (kept when it parses), else the stale one. Null: none, and not allowed to fetch. */
    private fun index(download: Boolean): Map<String, Map<String, Int>>? {
        val kept = File(dir, INDEX)
        val keptAt = if (kept.exists()) kept.lastModified() else 0L
        cached?.let { (at, m) -> if (at == keptAt && (fresh(at) || !download)) return m }
        if (keptAt > 0 && (fresh(keptAt) || !download)) return read(kept)
        if (!download) return null
        val text = try { String(get(base() + INDEX), Charsets.UTF_8) } catch (e: IOException) {
            if (keptAt > 0) return read(kept)
            throw e
        }
        val parsed = try { CheatLogic.parseIndex(text) } catch (e: Exception) {
            JLog.e(TAG, "the cheat index doesn't parse" + "\n" + Log.getStackTraceString(e))
            if (keptAt > 0) return read(kept)
            throw IOException("bad index")
        }
        val changed = keptAt == 0L || kept.readText() != text
        dir.mkdirs()
        if (changed) dir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
        writeWhole(kept, text)
        cached = kept.lastModified() to parsed
        return parsed
    }

    private fun read(f: File) = CheatLogic.parseIndex(f.readText()).also { cached = f.lastModified() to it }

    private fun list(src: String, key: String, download: Boolean): String? {
        val f = File(dir, "$src/$key.json")
        if (f.exists()) return f.readText()
        if (!download) return null
        val text = try { String(get(base() + "$src/$key.json"), Charsets.UTF_8) } catch (e: FileNotFoundException) { return null }
        f.parentFile?.mkdirs()
        writeWhole(f, text)
        return text
    }

    private fun writeWhole(f: File, text: String) {
        val part = File(f.parentFile, f.name + ".part")
        part.writeText(text)
        if (!part.renameTo(f)) { part.delete(); throw IOException("can't keep ${f.name}") }
    }

    private fun fresh(at: Long) = System.currentTimeMillis() - at < INDEX_DAYS * 24 * 3600_000L

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Joey64/${BuildConfig.VERSION_NAME}")
        try {
            val code = c.responseCode
            if (code == 404) throw FileNotFoundException(url)
            if (code != 200) throw IOException("HTTP $code from $url")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** `dist/` of the release branch; debug builds read a stand-in from `files/dev_cheats_base` (a feed on the PC). */
    private fun base(): String {
        if (BuildConfig.DEBUG) {
            val dev = try { File(context.filesDir, "dev_cheats_base").readText().trim() } catch (e: IOException) { "" }
            if (dev.isNotEmpty()) return dev.trimEnd('/') + "/"
        }
        return BASE
    }

    private companion object {
        const val TAG = "Joey64Cheats"
        const val BASE = "https://raw.githubusercontent.com/JoeysRetroHandhelds/joey64-cheats/release/dist/"
        const val INDEX = "index.json"
        const val INDEX_DAYS = 7
    }
}
