package com.joeysretro.joey64

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Logging from the Kotlin side: logcat and the session log file, through the same native sink as the host's lines. */
object JLog {
    fun i(tag: String, msg: String) = line(Log.INFO, tag, msg)
    fun w(tag: String, msg: String) = line(Log.WARN, tag, msg)
    fun e(tag: String, msg: String) = line(Log.ERROR, tag, msg)
    private fun line(prio: Int, tag: String, msg: String): Int {
        try { NativeBridge.log(prio, tag, msg) } catch (e: UnsatisfiedLinkError) { Log.println(prio, tag, msg) }
        return 0
    }
}

/**
 * The logs a tester sends us (2026-11-06, Joey: "everything we would need to debug if they run into issues").
 * Each process start is one session in files/logs/, named <yyyyMMdd-HHmmss>-<pid>:
 *  - `-app.log`: every line the app, the host and the core log, written by the native sink (joeylog.cpp), so nothing
 *    depends on logd's level filter (the Duo keeps no info lines). It opens with a header: build, core, device,
 *    battery and heat, the intent, and why the previous run ended.
 *  - `-system.log`: logcat for our pid, as logd keeps it: Java exceptions, libc and linker messages, driver lines
 *    (Granite, Mesa) that bypass our sink.
 *  - `-exit-<time>.txt` (+ `-exit-<time>.trace` / `.tombstone.pb`): an earlier run's end as Android recorded it
 *    (ApplicationExitInfo, Android 11+): crash, native crash, ANR, low memory, killed; Android 11+ adds the ANR's
 *    thread dump, Android 12+ a native crash's tombstone (protobuf).
 * The last [KEEP] sessions are kept. [zip] packs them for sending (the Share button comes with Joey's menus).
 */
object LogFiles {
    private const val TAG = "Joey64"
    private const val KEEP = 5
    private const val PREFS = "logfiles"
    private const val LAST_EXIT = "lastExitTime"
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private var started = false
    lateinit var session: String
        private set

    fun dir(ctx: Context) = File(ctx.filesDir, "logs").apply { mkdirs() }

    /**
     * Once per process, before anything else logs: the launcher's screen or the game's, whichever opens first. A later
     * call (the game started from the launcher) adds only its intent line to the session already open.
     */
    fun start(ctx: Context, intent: Intent?) {
        if (started) { logIntent(intent); return }
        started = true
        val dir = dir(ctx)
        session = "${stamp.format(Date())}-${Process.myPid()}"
        prune(dir)
        NativeBridge.openLog(File(dir, "$session-app.log").absolutePath)
        captureLogcat(File(dir, "$session-system.log"))
        catchUncaught()
        header(ctx, intent)
        previousExits(ctx, dir)
    }

    /** Sessions sorted oldest first; keeps the newest KEEP - 1 plus the one starting now. */
    private fun prune(dir: File) {
        val sessions = dir.listFiles().orEmpty().filter { it.isFile }.groupBy { it.name.split('-').take(3).joinToString("-") }
            .toSortedMap()
        val drop = sessions.size - (KEEP - 1)
        if (drop > 0) sessions.values.take(drop).flatten().forEach { it.delete() }
    }

    /**
     * logcat for our pid only, streamed into the file by a child process. Android kills an app's whole process group
     * when the app dies, so the child goes with it.
     */
    private fun captureLogcat(out: File) {
        try {
            ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}")
                .redirectErrorStream(true).redirectOutput(out).start()
        } catch (e: Exception) {
            JLog.e(TAG, "log: logcat capture failed: $e")
        }
    }

    private fun catchUncaught() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            JLog.e(TAG, "uncaught exception on thread ${t.name}:\n${Log.getStackTraceString(e)}")
            previous?.uncaughtException(t, e)
        }
    }

    private fun header(ctx: Context, intent: Intent?) {
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION") val code = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
        JLog.i(TAG, "log: session $session")
        JLog.i(TAG, "build: ${ctx.packageName} ${pkg.versionName} ($code), commit ${BuildConfig.GIT_COMMIT}" +
            (if (BuildConfig.GIT_DIRTY) " + uncommitted changes" else "") +
            ", ${if (BuildConfig.DEBUG) "debug" else "release"}, built ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(BuildConfig.BUILD_TIME))}" +
            ", installed ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(pkg.lastUpdateTime))}")
        val core = try { ctx.assets.open("core-version.txt").bufferedReader().readText().trim() } catch (e: Exception) { "core-version.txt missing" }
        core.lines().forEach { JLog.i(TAG, "core: $it") }
        val soc = if (Build.VERSION.SDK_INT >= 31) ", SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        JLog.i(TAG, "device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.PRODUCT})$soc, board ${Build.BOARD}, hardware ${Build.HARDWARE}")
        JLog.i(TAG, "android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}, build ${Build.FINGERPRINT}")
        JLog.i(TAG, "cpu: ${Runtime.getRuntime().availableProcessors()} cores, " + cpuMaxFreqs() + ", ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        JLog.i(TAG, "memory: ${mem.totalMem shr 20} MB total, ${mem.availMem shr 20} MB free, low ${mem.lowMemory}, app limit ${am.memoryClass}/${am.largeMemoryClass} MB")
        val storage = ctx.filesDir.usableSpace shr 20
        JLog.i(TAG, "storage: $storage MB free for app data")
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            val level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / maxOf(1, bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
            val plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            JLog.i(TAG, "battery: $level%, ${if (plugged) "charging" else "on battery"}, ${bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0} C")
        }
        val pm = ctx.getSystemService(PowerManager::class.java)
        JLog.i(TAG, "power: thermal status ${pm.currentThermalStatus}, power save ${pm.isPowerSaveMode}, sustained mode supported ${pm.isSustainedPerformanceModeSupported}")
        @Suppress("DEPRECATION") val display = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        JLog.i(TAG, "display: ${display.mode.physicalWidth}x${display.mode.physicalHeight} at ${display.mode.refreshRate} Hz, modes " +
            display.supportedModes.joinToString { "%.0f".format(it.refreshRate) })
        logIntent(intent)
    }

    private fun logIntent(intent: Intent?) {
        if (intent == null) return
        val extras = intent.extras?.keySet()?.joinToString { k -> "$k=${@Suppress("DEPRECATION") intent.extras?.get(k)}" } ?: "none"
        JLog.i(TAG, "intent: action ${intent.action}, data ${intent.data}, type ${intent.type}, extras $extras")
    }

    private fun cpuMaxFreqs(): String {
        val f = (0 until Runtime.getRuntime().availableProcessors()).map { i ->
            try { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 } catch (e: Exception) { 0L }
        }
        return "max MHz " + f.joinToString("/")
    }

    /** Earlier runs' ends that Android recorded and we have not saved yet. */
    private fun previousExits(ctx: Context, dir: File) {
        if (Build.VERSION.SDK_INT < 30) { JLog.i(TAG, "previous exits: needs Android 11"); return }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(LAST_EXIT, 0L)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val exits = try { am.getHistoricalProcessExitReasons(null, 0, 10) } catch (e: Exception) { JLog.e(TAG, "previous exits: $e"); return }
        val fresh = exits.filter { it.timestamp > seen }.sortedBy { it.timestamp }
        if (exits.isEmpty()) JLog.i(TAG, "previous exits: none recorded")
        exits.firstOrNull()?.let { JLog.i(TAG, "previous exit: ${describe(it)}") }
        for (x in fresh) {
            val name = "$session-exit-${stamp.format(Date(x.timestamp))}"
            File(dir, "$name.txt").writeText(describe(x) + "\n")
            val trace = try { x.traceInputStream } catch (e: Exception) { null }
            if (trace != null) {
                val ext = if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "tombstone.pb" else "trace"
                trace.use { copy(it, File(dir, "$name.$ext")) }
            }
            if (x.reason in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE))
                JLog.w(TAG, "previous run ended badly: ${describe(x)}" + if (trace != null) " (trace saved)" else "")
        }
        exits.maxOfOrNull { it.timestamp }?.let { if (it > seen) prefs.edit().putLong(LAST_EXIT, it).apply() }
    }

    private fun describe(x: ApplicationExitInfo): String {
        val reason = when (x.reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
            ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
            ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
            ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
            else -> "reason ${x.reason}"
        }
        return "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(x.timestamp))} pid ${x.pid} ${x.processName}: $reason, " +
            "status ${x.status}, importance ${x.importance}, pss ${x.pss / 1024} MB, rss ${x.rss / 1024} MB, \"${x.description ?: ""}\""
    }

    private fun copy(input: InputStream, out: File) = out.outputStream().use { input.copyTo(it) }

    /** All kept sessions in one zip (cacheDir/joey64-logs.zip), for sending. */
    fun zip(ctx: Context): File {
        val out = File(ctx.cacheDir, "joey64-logs.zip")
        ZipOutputStream(out.outputStream()).use { z ->
            dir(ctx).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { f ->
                z.putNextEntry(ZipEntry("logs/${f.name}"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }
}
