package com.joeysretro.joey64.launcher.boxart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.joeysretro.joey64.BuildConfig
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.data.ArtLookup
import com.joeysretro.joey64.launcher.data.LibraryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** The MD5 -> No-Intro name table built from libretro-database (assets/boxart/n64-names.tsv, devtools/boxart/build-table.py). */
object BoxArtTable {
    fun parse(text: String): Map<String, String> {
        val m = HashMap<String, String>(2048)
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line[0] == '#') continue
            val tab = line.indexOf('\t')
            if (tab == 32) m[line.substring(0, 32)] = line.substring(33)
        }
        return m
    }

    @Volatile private var table: Map<String, String>? = null

    fun get(context: Context): Map<String, String> =
        table ?: synchronized(this) {
            table ?: parse(context.assets.open("boxart/n64-names.tsv").bufferedReader().use { it.readText() }).also { table = it }
        }
}

/**
 * Box art (plan, Phase 2; decided 2026-11-05): libretro's covers, picked by [BoxArtNames],
 * downloaded once from thumbnails.libretro.com and kept in `files/boxart/<md5>.jpg`. libretro
 * publishes no terms for other apps, so: our own User-Agent, each cover once, the folder listing
 * fetched at most once a week and kept, three downloads at a time.
 */
class BoxArt(private val context: Context, private val db: LibraryDb) {

    sealed interface Outcome {
        /** [found] covers downloaded, [none] games with no cover. */
        data class Done(val found: Int, val none: Int, val failed: Int) : Outcome
        /** No connection (or the server didn't answer): nothing changed, try again once online. */
        data object Offline : Outcome
    }

    val dir: File get() = File(context.filesDir, "boxart")

    fun file(md5: String) = File(dir, "$md5.jpg")

    /** Looks up and downloads every library game's cover not yet looked up against the listing. [onCover] after each new one. */
    suspend fun fetch(onCover: () -> Unit = {}): Outcome = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        // Nothing to look up against the listing kept (or, with none kept, nothing never looked up):
        // done, without going online, so a connection isn't needed and no offline popup is due.
        val kept = File(dir, LISTING).let { if (it.exists()) it.lastModified() else 0L }
        if (db.artToLookUp(kept).isEmpty() && (kept == 0L || fresh(kept))) return@withContext Outcome.Done(0, 0, 0)
        val listing = try { listing() } catch (e: IOException) {
            JLog.w(TAG, "can't get the cover listing: $e")
            return@withContext Outcome.Offline
        }
        val todo = db.artToLookUp(listing.fetchedAt)
        if (todo.isEmpty()) return@withContext Outcome.Done(0, 0, 0)
        val table = BoxArtTable.get(context)
        dir.mkdirs()
        val found = AtomicInteger(); val none = AtomicInteger(); val failed = AtomicInteger(); val offline = AtomicInteger()
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            todo.map { g ->
                pool.submit {
                    if (offline.get() > 0) return@submit
                    when (val r = lookUp(g, table, listing)) {
                        Result.Found -> { found.incrementAndGet(); onCover() }
                        Result.None -> none.incrementAndGet()
                        Result.Failed -> failed.incrementAndGet()
                        Result.Offline -> offline.incrementAndGet()
                    }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        coroutineContext.ensureActive()
        JLog.i(TAG, "box art: ${todo.size} looked up, ${found.get()} downloaded, ${none.get()} without a cover, ${failed.get()} failed, " +
            "${SystemClock.elapsedRealtime() - t0} ms (listing ${listing.covers.size} covers)")
        if (offline.get() > 0 && found.get() == 0) Outcome.Offline else Outcome.Done(found.get(), none.get(), failed.get())
    }

    private enum class Result { Found, None, Failed, Offline }

    private fun lookUp(g: ArtLookup, table: Map<String, String>, listing: Listing): Result {
        val match = BoxArtNames.pick(table[g.md5], g.fileName, listing.covers)
        if (match == null) {
            db.setArt(g.gameId, "none", null, listing.fetchedAt)
            return Result.None
        }
        return try {
            download(BoxArtNames.url(base(), match.cover), file(g.md5))
            db.setArt(g.gameId, "libretro", match.cover, listing.fetchedAt)
            JLog.i(TAG, "cover for ${g.fileName}: ${match.cover} (${match.how})")
            Result.Found
        } catch (e: FileNotFoundException) {
            // In the listing but gone from the server: none until the next listing.
            JLog.w(TAG, "cover ${match.cover} not on the server")
            db.setArt(g.gameId, "none", null, listing.fetchedAt)
            Result.None
        } catch (e: IOException) {
            // No row either way: looked up again next time.
            if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException) return Result.Offline
            JLog.w(TAG, "can't download ${match.cover}" + "\n" + Log.getStackTraceString(e))
            Result.Failed
        }
    }

    /** Downloads [url] and keeps it as a JPEG (a cover's PNG is ~300 KB, the JPEG about a fifth), written whole or not at all. */
    private fun download(url: String, to: File) {
        val bytes = get(url)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("not an image: $url")
        val part = File(to.parentFile, to.name + ".part")
        try {
            part.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("can't encode") }
            if (!part.renameTo(to)) throw IOException("can't rename ${part.name}")
        } finally {
            bmp.recycle()
            part.delete()
        }
    }

    private class Listing(val covers: BoxArtNames.Covers, val fetchedAt: Long)

    @Volatile private var cached: Listing? = null

    /**
     * The cover names: the kept listing while it's less than [LISTING_DAYS] old, else a fresh one
     * (kept when it parses). A stale kept listing still serves when the server can't be reached.
     */
    private fun listing(): Listing {
        cached?.let { if (fresh(it.fetchedAt)) return it }
        val kept = File(dir, LISTING)
        val keptAt = if (kept.exists()) kept.lastModified() else 0L
        if (keptAt > 0 && fresh(keptAt)) return read(kept, keptAt).also { cached = it }
        val fetched = try {
            String(get(base()), Charsets.UTF_8)
        } catch (e: IOException) {
            if (keptAt > 0) return read(kept, keptAt).also { cached = it }
            throw e
        }
        val names = BoxArtNames.parseListing(fetched)
        if (names.size < 100) throw IOException("cover listing has only ${names.size} names")
        dir.mkdirs()
        val part = File(dir, "$LISTING.part")
        part.writeText(names.joinToString("\n"))
        if (!part.renameTo(kept)) { part.delete(); throw IOException("can't keep the listing") }
        return Listing(BoxArtNames.Covers(names), kept.lastModified()).also { cached = it }
    }

    private fun read(f: File, at: Long) = Listing(BoxArtNames.Covers(f.readLines().filter { it.isNotEmpty() }), at)

    private fun fresh(at: Long) = System.currentTimeMillis() - at < LISTING_DAYS * 24 * 3600_000L

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

    /** The covers folder; debug builds read a stand-in from `files/dev_boxart_base` (a feed on the PC, for testing offline and failures). */
    private fun base(): String {
        if (BuildConfig.DEBUG) {
            val dev = try { File(context.filesDir, "dev_boxart_base").readText().trim() } catch (e: IOException) { "" }
            if (dev.isNotEmpty()) return dev.trimEnd('/') + "/"
        }
        return BASE
    }

    private companion object {
        const val TAG = "Joey64BoxArt"
        const val BASE = "https://thumbnails.libretro.com/Nintendo%20-%20Nintendo%2064/Named_Boxarts/"
        const val LISTING = "listing.txt"
        const val LISTING_DAYS = 7
        const val THREADS = 3
    }
}
