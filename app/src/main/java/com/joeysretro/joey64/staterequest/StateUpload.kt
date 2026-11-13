package com.joeysretro.joey64.staterequest

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.update.AppUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * The states players said Yes to ([StateRequests.answer]), sent in the background with retry. Each Yes is a folder in
 * `files/staterequests/outbox/`: item.json (md5, kind, app version, tries, next try), `state` (the ring's raw state
 * wrapped as RetroArch keeps states, RZIP + RASTATE: 16.8 MB raw, about 2 MB) and `save.gz` (the game's .srm, gzipped). [maybeSend] posts them,
 * oldest first, to the reports server's POST /v1/state (multipart; 204 taken). 429 waits as long as Retry-After says;
 * offline, busy or a 5xx (503: the server's wanted list didn't load) waits longer each time (1 min, doubling, up to a
 * day), or as long as its Retry-After says; 404 "not wanted" and 410 (it has enough) drop it and stop the asks for
 * that game (any other 404, a server without the route yet, waits like a 5xx); any other answer (400, 413) drops it. Sent when the library comes to the front and right after a Yes.
 */
object StateUpload {
    private const val TAG = "Joey64StateReq"
    /** Yeses waiting at most: a state is ~2 MB, so the outbox stays under ~10 MB (the oldest goes). */
    private const val MAX_WAITING = 5
    private const val MAX_TRIES = 12
    private val sending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun outbox(ctx: Context) = File(StateRequests.dir(ctx), "outbox")

    /** Copies [ask]'s state and the game's save into the outbox. False when the state is gone or can't be copied. */
    fun queue(ctx: Context, ask: StateRequests.Ask, saveName: String?): Boolean {
        val state = File(ask.state.path)
        if (!state.isFile || !state.canonicalPath.startsWith(ctx.filesDir.canonicalPath + File.separator)) {
            JLog.w(TAG, "state request: ${ask.state.path} is gone"); return false
        }
        val box = outbox(ctx).apply { mkdirs() }
        val item = File(box, "${System.currentTimeMillis()}-${ask.wanted.md5}")
        val part = File(box, item.name + ".part")
        return try {
            part.deleteRecursively(); part.mkdirs()
            File(part, "state").writeBytes(com.joeysretro.joey64.states.StateFormat.wrap(state.readBytes()))
            val save = saveName?.let { File(File(ctx.filesDir, "saves"), "$it.srm") }
            if (save != null && save.isFile) GZIPOutputStream(File(part, "save.gz").outputStream()).use { out -> save.inputStream().use { it.copyTo(out) } }
            File(part, "item.json").writeText(JSONObject().put("md5", ask.wanted.md5).put("kind", ask.wanted.kind.name.lowercase())
                .put("app", AppUpdates.installedVersion(ctx)).put("tries", 0).put("next", 0L).toString())
            if (!part.renameTo(item)) throw IOException("can't keep ${item.name}")
            // The oldest go past MAX_WAITING ([waiting] is newest first: `dropLast` deleted the newest, the Yes just
            // queued among them; code audit 4, C-M3).
            for (old in pastCap(waiting(ctx))) { old.deleteRecursively(); StateRequests.dropped(ctx, md5Of(old)) }
            true
        } catch (e: IOException) {
            JLog.w(TAG, "state request: not queued ($e)"); part.deleteRecursively(); false
        }
    }

    /** Of [newestFirst], the ones past [MAX_WAITING]: the oldest. */
    internal fun <T> pastCap(newestFirst: List<T>): List<T> = newestFirst.drop(MAX_WAITING)

    /** An item's md5 from its folder name ("<time>-<md5>"). */
    private fun md5Of(item: File) = item.name.substringAfter('-')

    /** The outbox's items, newest first. */
    private fun waiting(ctx: Context) = outbox(ctx).listFiles { f -> f.isDirectory && !f.name.endsWith(".part") }.orEmpty().sortedByDescending { it.name }

    /** What the server's answer means for an item: sent, try again at a time, or drop. */
    sealed interface Result {
        data object Sent : Result
        data class Later(val at: Long) : Result
        /** [done]: the server doesn't want this game's states any more (404, 410). */
        data class Drop(val done: Boolean = false) : Result
    }

    /**
     * [code] from the server ([retryAfter] its Retry-After seconds, [body] the start of its answer) on try number [tries],
     * at [now]. A 404 stops the asks only when it says "not wanted": a server without the state route yet (the live one
     * until its deploy, 2026-11-12) answers 404 "not found", which waits and tries again like a 5xx.
     */
    fun resultOf(code: Int, retryAfter: Long?, tries: Int, now: Long, body: String = ""): Result = when {
        code in 200..299 -> Result.Sent
        code == 410 || (code == 404 && body.trim().startsWith("not wanted")) -> Result.Drop(done = true)
        tries >= MAX_TRIES -> Result.Drop()
        code == 429 -> Result.Later(now + (retryAfter ?: 3600L).coerceIn(60L, 86_400L) * 1000)
        code == -1 || code == 404 || code == 408 || code >= 500 -> Result.Later(now + (retryAfter?.coerceIn(60L, 86_400L)?.times(1000)
            ?: (60_000L shl tries.coerceAtMost(10)).coerceAtMost(86_400_000L)))
        else -> Result.Drop()
    }

    /** Sends what's waiting and due. Returns how many went. */
    suspend fun maybeSend(ctx: Context): Int = withContext(Dispatchers.IO) {
        if (!sending.compareAndSet(false, true)) return@withContext 0
        var sent = 0
        try {
            for (item in waiting(ctx).reversed()) {
                val meta = try { JSONObject(File(item, "item.json").readText()) } catch (e: Exception) {
                    item.deleteRecursively(); StateRequests.dropped(ctx, md5Of(item)); continue
                }
                val now = System.currentTimeMillis()
                if (meta.optLong("next") > now) continue
                val tries = meta.optInt("tries")
                val (code, retryAfter, body) = post(ctx, item, meta)
                when (val r = resultOf(code, retryAfter, tries, now, body)) {
                    Result.Sent -> {
                        item.deleteRecursively(); sent++; StateRequests.accepted(ctx, meta.optString("md5"))
                        JLog.i(TAG, "state request: sent ${meta.optString("md5")}")
                    }
                    is Result.Drop -> {
                        item.deleteRecursively()
                        if (r.done) StateRequests.enough(ctx, meta.optString("md5")) else StateRequests.dropped(ctx, meta.optString("md5"))
                        JLog.w(TAG, "state request: ${meta.optString("md5")} dropped (HTTP $code, try ${tries + 1})")
                    }
                    is Result.Later -> {
                        File(item, "item.json").writeText(meta.put("tries", tries + 1).put("next", r.at).toString())
                        JLog.i(TAG, "state request: ${meta.optString("md5")} waits (HTTP $code), next try in ${(r.at - now) / 1000} s")
                        // Offline or the server's limit: the rest would get the same answer.
                        if (code == -1 || code == 429) break
                    }
                }
            }
        } finally { sending.set(false) }
        sent
    }

    private const val BOUNDARY = "joey64-state-7f3a91"

    /** One item as multipart (md5, kind, app, state, save): the HTTP code (-1 offline), Retry-After and the answer's start. */
    private fun post(ctx: Context, item: File, meta: JSONObject): Triple<Int, Long?, String> {
        val c = URL(com.joeysretro.joey64.reports.ReportSender.base(ctx) + "/v1/state").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.doOutput = true
            c.setChunkedStreamingMode(64 * 1024)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            c.setRequestProperty("User-Agent", "Joey64/${AppUpdates.installedVersion(ctx)}")
            c.outputStream.buffered().use { out ->
                fun text(name: String, value: String) {
                    out.write("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                fun file(name: String, f: File) {
                    out.write(("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$name\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n").toByteArray())
                    f.inputStream().use { it.copyTo(out) }
                    out.write("\r\n".toByteArray())
                }
                text("md5", meta.optString("md5")); text("kind", meta.optString("kind")); text("app", meta.optString("app"))
                file("state", File(item, "state"))
                File(item, "save.gz").takeIf { it.isFile }?.let { file("save", it) }
                out.write("--$BOUNDARY--\r\n".toByteArray())
            }
            val code = c.responseCode
            val body = try { (if (code >= 400) c.errorStream else c.inputStream)?.use { s -> String(s.readNBytes(64)) }.orEmpty() }
                catch (e: IOException) { "" }
            Triple(code, c.getHeaderField("Retry-After")?.trim()?.toLongOrNull(), body)
        } catch (e: IOException) {
            Triple(-1, null, "")
        } finally { c.disconnect() }
    }
}
