package com.joeysretro.joey64.reports

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.update.AppUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Sends what's waiting, at most once a day (UTC), when the library comes to the front and Send reports
 * is on. Each report is one device part and its events: sessions are grouped by the GPU and driver
 * they ran on (Settings can change the driver), covers and crashes go with the first. Anything the
 * server didn't take for a reason that may pass (offline, busy, 429) waits for the next day.
 */
object ReportSender {
    private const val TAG = "Joey64Reports"
    const val SERVER = "https://joey64-reports.joeysretrohandhelds.workers.dev"
    /** Reports a day the server takes from one install (server/reports/src/store.js, PER_INSTALL_DAY). */
    private const val MAX_REPORTS = 3
    private const val MAX_COVERS = 150

    private val sending = java.util.concurrent.atomic.AtomicBoolean(false)

    fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

    /** [force]: debug builds, send now even if a report went today. */
    suspend fun maybeSend(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!ReportPrefs.on(ctx)) return@withContext
        if (!force && ReportPrefs.lastSentDay(ctx) == today()) return@withContext
        if (!sending.compareAndSet(false, true)) return@withContext
        try { send(ctx) } catch (e: Exception) { JLog.w(TAG, "reports: $e") } finally { sending.set(false) }
    }

    private fun send(ctx: Context) {
        val exits = exits(ctx)
        sweepOpen(ctx, exits)
        val pending = ReportFiles.pending(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        val sessions = pending.mapNotNull { f -> try { f to JSONObject(f.readText()) } catch (e: Exception) { f.delete(); null } }
        val db = LibraryDb.get(ctx)
        val headers = db.headersOf(sessions.mapNotNull { ReportJson.md5(it.second.optString("md5")) }.toSet())

        val sentCovers = ReportFiles.coversSent(ctx).takeIf { it.exists() }?.readLines()?.toHashSet() ?: hashSetOf()
        val covers = db.coverReports().map { it to "${it.md5} ${it.kind} ${it.cover ?: ""}" }.filter { it.second !in sentCovers }.take(MAX_COVERS)
        val coverEvents = covers.mapNotNull { (c, key) -> ReportJson.cover(c.md5, c.kind, c.header, c.title, c.cover)?.let { it to key } }

        // Oldest first: when the report is too big for all of them, the newest wait (and stay after exitsAfter).
        val crashes = exits.filter { it.crash && it.timestamp > ReportPrefs.exitsAfter(ctx) }.sortedBy { it.timestamp }
            .map { it to ReportJson.crash(it.md5, it.where, it.summary) }

        // Sessions by (gpu, driver), newest group first; covers and crashes ride with the first report.
        val groups = sessions.groupBy { it.second.optString("gpu") to it.second.optString("driver") }
            .entries.sortedByDescending { e -> e.value.maxOf { it.first.name } }
        val devicePrefs = ctx.getSharedPreferences("device", Context.MODE_PRIVATE)
        val plan = mutableListOf<Pair<Pair<String, String>, List<Pair<File, JSONObject>>>>()
        if (groups.isEmpty()) plan += (devicePrefs.getString("report_gpu", "").orEmpty() to devicePrefs.getString("report_driver", "").orEmpty()) to emptyList()
        else for (g in groups.take(MAX_REPORTS)) plan += g.key to g.value

        val app = AppUpdates.installedVersion(ctx)
        val core = core(ctx)
        val install = ReportPrefs.install(ctx)
        var anySent = false
        var maxExit = 0L
        for ((i, step) in plan.withIndex()) {
            val (gpuDriver, group) = step
            val crashesHere = if (i == 0) crashes else emptyList()
            val coversHere = if (i == 0) coverEvents else emptyList()
            val sessionEvents = group.mapNotNull { (f, o) -> ReportJson.session(o, headers[o.optString("md5")])?.let { f to it } }
                .take(ReportJson.MAX_EVENTS - crashesHere.size - coversHere.size)
            // Crashes, then sessions oldest first, then covers: what doesn't fit waits for the next report, from the end.
            val events = crashesHere.map { it.second } + sessionEvents.map { it.second } + coversHere.map { it.first }
            if (events.isEmpty()) continue
            val screen = mainScreen(ctx)
            val device = ReportJson.Device(Build.MANUFACTURER, Build.MODEL, if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
                gpuDriver.first.ifEmpty { null }, gpuDriver.second.ifEmpty { null }, Build.VERSION.SDK_INT,
                screen?.first, screen?.second, screen?.third)
            val keep = fitting(ReportJson.report(install, app, core, device, emptyList()).toString(), events)
            val sentCrashes = crashesHere.take(keep)
            val sentSessions = sessionEvents.take((keep - crashesHere.size).coerceAtLeast(0))
            val sentCovers = coversHere.take((keep - crashesHere.size - sessionEvents.size).coerceAtLeast(0))
            val body = ReportJson.report(install, app, core, device, events.take(keep)).toString()
            val code = post(ctx, body)
            JLog.i(TAG, "reports: sent ${sentSessions.size} sessions" + (if (i == 0) ", ${sentCovers.size} covers, ${sentCrashes.size} crashes" else "") +
                (if (keep < events.size) " (${events.size - keep} more wait: over ${MAX_BODY / 1024} KB)" else "") + ": $code")
            if (code == 204 || code == 400 || code == 413) {
                // 400: the server can't read it, and it won't later either: dropped, not sent every day. 413 (too big even
                // after the cut above) the same, so one report can't hold up the ones after it for good (code audit 3, M22).
                if (code != 204) JLog.e(TAG, "reports: the server refused the report ($code): ${lastError ?: ""}")
                sentSessions.forEach { it.first.delete() }
                if (i == 0) {
                    ReportFiles.coversSent(ctx).appendText(sentCovers.joinToString("") { it.second + "\n" })
                    // Every crash went: past every exit so far. Some wait: past the newest that went, so the rest go next.
                    maxExit = if (sentCrashes.size == crashesHere.size) exits.maxOfOrNull { it.timestamp } ?: 0L
                    else sentCrashes.lastOrNull()?.first?.timestamp ?: 0L
                }
                anySent = anySent || code == 204
            } else break
        }
        if (maxExit > 0) ReportPrefs.setExitsAfter(ctx, maxOf(maxExit, ReportPrefs.exitsAfter(ctx).takeIf { it != Long.MAX_VALUE } ?: 0L))
        if (anySent || (plan.all { it.second.isEmpty() } && crashes.isEmpty() && coverEvents.isEmpty())) ReportPrefs.setLastSentDay(ctx, today())
    }

    /** The server takes at most 64 KB a report (server/reports/src/validate.js, MAX_BYTES); a report stays under this. */
    const val MAX_BODY = 60 * 1024

    /**
     * How many of [events], from the start, fit in one report whose body without events is [empty]. At least one, so a
     * single event too big even alone goes once and is dropped (413) instead of holding the queue (code audit 3, M22:
     * a report over 64 KB got 413 every day, and nothing from that install was sent again).
     */
    internal fun fitting(empty: String, events: List<JSONObject>, max: Int = MAX_BODY): Int {
        var total = empty.toByteArray().size
        for ((n, e) in events.withIndex()) {
            total += e.toString().toByteArray().size + 1 // and the comma before the next
            if (total > max) return maxOf(n, 1)
        }
        return events.size
    }

    private var lastError: String? = null

    /** The base address: the server, or in debug builds files/dev_reports_base (a local wrangler dev through adb reverse). */
    private fun base(ctx: Context): String {
        if (com.joeysretro.joey64.BuildConfig.DEBUG) File(ctx.filesDir, "dev_reports_base").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.trimEnd('/') }
        return SERVER
    }

    private fun post(ctx: Context, body: String): Int {
        lastError = null
        val c = URL(base(ctx) + "/v1/report").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", "Joey64/${AppUpdates.installedVersion(ctx)}")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code >= 400) lastError = c.errorStream?.bufferedReader()?.use { it.readText().take(200) }
            code
        } catch (e: java.io.IOException) {
            JLog.i(TAG, "reports: not sent ($e), trying again tomorrow")
            -1
        } finally { c.disconnect() }
    }

    /** The bundled core as About shows it: "dd 0ac9d17". */
    private fun core(ctx: Context): String? {
        val lines = try { ctx.assets.open("core-version.txt").bufferedReader().readLines() } catch (e: Exception) { return null }
        fun field(k: String) = lines.firstOrNull { it.startsWith("$k:") }?.substringAfter(':')?.trim().orEmpty()
        return listOf(field("branch"), field("commit").substringBefore(' ').take(7)).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }

    /** One of our processes' ends as Android recorded it, and the session it was playing (by pid). */
    private data class Exit(val pid: Int, val timestamp: Long, val crash: Boolean, var where: String, val summary: String, var md5: String? = null)

    private fun exits(ctx: Context): List<Exit> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val list = try { ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 16) } catch (e: Exception) { return emptyList() }
        return list.map { x ->
            val crash = x.reason == ApplicationExitInfo.REASON_CRASH || x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || x.reason == ApplicationExitInfo.REASON_ANR
            Exit(x.pid, x.timestamp, crash, if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "core" else "launcher", if (crash) summary(ctx, x) else "")
        }
    }

    private fun summary(ctx: Context, x: ApplicationExitInfo): String {
        val head = when (x.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash, signal ${x.status}"
            ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
            else -> "crash (Java)"
        } + (x.description?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        val detail = when (x.reason) {
            // The ANR trace is text: the main thread's stack is near the top.
            ApplicationExitInfo.REASON_ANR -> try { x.traceInputStream?.bufferedReader()?.useLines { it.take(40).joinToString("\n") } } catch (e: Exception) { null }
            // A Java crash has no trace in the exit record: LogFiles kept the stack when it happened.
            ApplicationExitInfo.REASON_CRASH -> File(ReportFiles.dir(ctx), "java-crash-${x.pid}.txt").takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
            // A native crash's tombstone (Android 12+): the signal and the crashing thread's backtrace.
            ApplicationExitInfo.REASON_CRASH_NATIVE -> try {
                x.traceInputStream?.use { s -> s.readNBytesCompat(8 shl 20) }?.let { Tombstone.summary(it) }
            } catch (e: Exception) { null }
            else -> null
        }
        // A native crash's tombstone starts with the signal itself: "native crash" is enough above it.
        val top = if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && detail != null)
            "native crash" + (x.description?.takeIf { it.isNotBlank() && it != "crash" }?.let { ": $it" } ?: "") else head
        return scrub(top + (detail?.let { "\n" + it } ?: ""))
    }

    /** At most [max] bytes of the stream (InputStream.readNBytes is Android 13+). */
    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Paths and content addresses out of crash text (a file name in an exception's message can name the user's folders).
     * A path runs to the end of its line, a quote or ": " (folder names have spaces: "/storage/.../John Smith/Zelda.z64"
     * left " Smith/Zelda.z64" behind), and SAF document ids ("primary:ROMs/...", an SD card's "1A2B-3C4D:...") go too
     * (code audit 3, p7 L3). Content URIs have no spaces (they're percent-encoded).
     */
    fun scrub(s: String): String = s
        .replace(Regex("""content://\S+"""), "<path>")
        .replace(Regex("""file://$PATH_REST"""), "<path>")
        .replace(Regex("""/(storage|sdcard|mnt|data/user|data/data)/$PATH_REST"""), "<path>")
        .replace(Regex("""\b(primary|[0-9A-F]{4}-[0-9A-F]{4}):[^\s"']$PATH_REST"""), "<path>")

    /** The rest of a path: up to the end of its line, a quote or ": ". */
    private const val PATH_REST = """(?:(?!: )[^\n"'])*"""

    /**
     * Sessions left in open/ by a process that is gone: they didn't end with a quit. One whose process
     * crashed is sent as ended "crash" (and its crash gets the game's MD5); one stopped otherwise (swiped
     * away, killed for memory) as "quit".
     */
    private fun sweepOpen(ctx: Context, exits: List<Exit>) {
        val alive = try {
            ctx.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().map { it.pid }.toSet()
        } catch (e: Exception) { setOf(android.os.Process.myPid()) }
        for (f in ReportFiles.open(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()) {
            val pid = f.nameWithoutExtension.toIntOrNull() ?: run { f.delete(); continue }
            if (pid in alive) continue
            val o = try { JSONObject(f.readText()) } catch (e: Exception) { f.delete(); continue }
            val exit = exits.firstOrNull { it.pid == pid }
            if (exit?.crash == true) {
                exit.md5 = o.optString("md5")
                // A Java crash or ANR while playing: the game screen's side, not the library's.
                if (exit.where == "launcher") exit.where = "host"
            } else o.put("ended", "quit")
            if (SessionTracker.keep(o.optLong("seconds"), o.optLong("frames", Long.MAX_VALUE))) ReportFiles.write(File(ReportFiles.pending(ctx), "${f.lastModified()}-$pid.json"), o.toString())
            f.delete()
        }
    }

    /** LogFiles' uncaught-exception handler: the stack, kept for the next report when Send reports is on. */
    fun keepJavaCrash(ctx: Context, stack: String) {
        if (!ReportPrefs.on(ctx)) return
        try { ReportFiles.write(File(ReportFiles.dir(ctx), "java-crash-${android.os.Process.myPid()}.txt"), stack.take(2000)) } catch (e: Exception) { }
    }

    /**
     * The main screen as layouts see it: its size in pixels, long side first, and its density in dpi
     * (the Thor's main screen, not the second one). Null when Android won't say.
     */
    @Suppress("DEPRECATION")
    private fun mainScreen(ctx: Context): Triple<Int, Int, Int>? = try {
        val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val m = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        Triple(maxOf(m.widthPixels, m.heightPixels), minOf(m.widthPixels, m.heightPixels), m.densityDpi)
    } catch (e: RuntimeException) { null }
}
