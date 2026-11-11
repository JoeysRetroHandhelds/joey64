package com.joeysretro.joey64.launcher.boxart

import android.content.Context
import com.joeysretro.joey64.BuildConfig
import com.joeysretro.joey64.Capped
import com.joeysretro.joey64.JLog
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Joey64's own covers (Joey 2026-11-07, "we are going to fill the gaps ourselves"): pictures for games
 * ScreenScraper has none for, kept in the joey64-data repo's `boxart/` folder and read from its `release`
 * branch like the cheat lists. `boxart/index.tsv` lists them: the ROM's MD5, the picture's own MD5 (a
 * changed picture is fetched again) and the game; the picture is `boxart/<rom md5>.png`. The index is
 * read again at most once a week. Publisher artwork is never bundled in the APK.
 */
class JoeyCovers(private val context: Context) {

    /** Our covers by ROM MD5: the picture's MD5. Last week's index when the network is down; empty when there is none. */
    fun index(): Map<String, String> {
        val f = File(File(context.filesDir, "boxart"), INDEX_FILE)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > INDEX_DAYS * 24 * 3600_000L) {
            try {
                val text = String(get(base() + INDEX), Charsets.UTF_8)
                f.parentFile?.mkdirs()
                val part = File(f.parentFile, f.name + ".part")
                part.writeText(text)
                if (!part.renameTo(f)) { part.delete(); throw IOException("can't keep ${f.name}") }
            } catch (e: IOException) {
                JLog.w(TAG, "box art: Joey64's cover list not read ($e), ${if (f.exists()) "using last week's" else "none yet"}")
            }
        }
        return try { parse(f.readText()) } catch (e: IOException) { emptyMap() }
    }

    /** The picture of [md5]'s cover, as downloaded. */
    fun picture(md5: String): ByteArray = get(base() + md5 + ".png")

    /** joey64-data's `boxart/` on the release branch; debug builds read a stand-in from `files/dev_covers_base` (a feed on the PC). */
    private fun base(): String {
        if (BuildConfig.DEBUG) {
            val dev = try { File(context.filesDir, "dev_covers_base").readText().trim() } catch (e: IOException) { "" }
            if (dev.isNotEmpty()) return dev.trimEnd('/') + "/"
        }
        return BASE
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Joey64/${BuildConfig.VERSION_NAME}")
        try {
            val code = c.responseCode
            if (code == 404) throw FileNotFoundException(url)
            if (code != 200) throw IOException("HTTP $code from $url")
            return c.inputStream.use { Capped.bytes(it, MAX_REPLY, url.substringAfterLast('/')) }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val TAG = "Joey64BoxArt"
        const val BASE = "https://raw.githubusercontent.com/JoeysRetroHandhelds/joey64-data/release/boxart/"
        private const val INDEX = "index.tsv"
        private const val INDEX_FILE = "joey64-covers.tsv"
        private const val INDEX_DAYS = 7
        /** Our covers are a few hundred KB and the index less: past this a reply isn't one (code audit 3, p3 L10). */
        private const val MAX_REPLY = 16 * Capped.MB
        /** What `box_art.cover` says for one of ours: "joey64:<picture md5>". */
        const val PREFIX = "joey64:"
        private val MD5 = Regex("[0-9a-f]{32}")

        fun parse(text: String): Map<String, String> {
            val m = HashMap<String, String>()
            for (line in text.lineSequence()) {
                if (line.isBlank() || line[0] == '#') continue
                val cols = line.split('\t')
                if (cols.size < 2) continue
                val rom = cols[0].trim().lowercase(); val pic = cols[1].trim().lowercase()
                if (MD5.matches(rom) && MD5.matches(pic)) m[rom] = pic
            }
            return m
        }
    }
}
