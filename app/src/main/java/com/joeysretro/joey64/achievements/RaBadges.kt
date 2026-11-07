package com.joeysretro.joey64.achievements

import android.content.Context
import com.joeysretro.joey64.JLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Achievement badges and game icons, kept in `files/ra/badges/` by their file name on
 * RetroAchievements' media server (badges never change once made, so a kept one is always right).
 * Settings › Save badges for offline fetches a game's whole set when it starts; otherwise each one
 * is fetched the first time a pop-up needs it.
 */
object RaBadges {
    private const val TAG = "joey64ra"
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "ra-badges").apply { isDaemon = true } }
    private val running = HashMap<String, Future<File?>>()
    // Waiting for a download never takes a download thread.
    private val waiters = Executors.newCachedThreadPool { r -> Thread(r, "ra-badge-wait").apply { isDaemon = true } }

    /** "https://media.retroachievements.org/Badge/12345.png" -> "Badge_12345.png"; null for anything else. */
    fun fileName(url: String): String? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?')
        if (path.isEmpty()) return null
        val name = path.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("_")
        return name.takeIf { Regex("""[A-Za-z0-9_.\-]+""").matches(it) && !it.contains("..") }
    }

    private fun dir(context: Context) = File(File(context.filesDir, "ra"), "badges")

    /** The kept file, or null when it isn't here yet. */
    fun kept(context: Context, url: String): File? {
        val name = fileName(url) ?: return null
        return File(dir(context), name).takeIf { it.isFile && it.length() > 0 }
    }

    /** Fetches [url] unless it's kept; [done] gets the file (or null) on the fetching thread. */
    fun fetch(context: Context, url: String, done: ((File?) -> Unit)? = null) {
        kept(context, url)?.let { done?.invoke(it); return }
        val name = fileName(url) ?: run { done?.invoke(null); return }
        val app = context.applicationContext
        val job = job(app, url, name)
        if (done != null) waiters.execute { done(try { job.get(30, TimeUnit.SECONDS) } catch (e: Exception) { null }) }
    }

    /** Waits up to [timeoutMs] for [url] (a pop-up shows its badge if it comes in time). Not on the main thread. */
    fun await(context: Context, url: String, timeoutMs: Long): File? {
        kept(context, url)?.let { return it }
        val name = fileName(url) ?: return null
        val app = context.applicationContext
        val job = job(app, url, name)
        return try { job.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
    }

    /** One download per file at a time; a finished one (fetched or failed) makes way for a new try. */
    @Synchronized
    private fun job(context: Context, url: String, name: String): Future<File?> {
        running[name]?.let { if (!it.isDone) return it }
        return pool.submit<File?> { download(context, url, name) }.also { running[name] = it }
    }

    private fun download(context: Context, url: String, name: String): File? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        val bytes = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
        val d = dir(context).apply { mkdirs() }
        val tmp = File(d, "$name.tmp")
        tmp.writeBytes(bytes)
        val f = File(d, name)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        f
    } catch (e: Exception) {
        JLog.w(TAG, "badge not fetched ($name): ${e.javaClass.simpleName}")
        null
    }
}
