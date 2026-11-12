package com.joeysretro.joey64.reports

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/*
 * Reports (Joey 2026-11-08: approved reports mockup, version A, asked once over the library): when the
 * user turns Send reports on, Joey64 sends at most once a day how games ran on this device, games with
 * no cover or a cover the user replaced, and crashes, to the report server (server/reports, a Cloudflare
 * Worker). Off sends nothing and keeps nothing: sessions aren't even recorded. Testing builds start On
 * (ReportPrefs.onByDefault).
 *
 * Files, all under files/reports/:
 *  - pending/<time>-<pid>.json: a finished play session waiting to be sent (an event, plus the gpu and
 *    driver it ran on, which go in the report's device part).
 *  - open/<pid>.json: the session being played, rewritten every few seconds. One left behind by a process
 *    that is gone is a session that didn't end with a quit (ReportSender turns it into an event).
 *  - covers-sent.txt: the cover events already sent ("md5 kind cover"), so each is sent once.
 */

/** Send reports and what goes with it, in its own prefs file. */
object ReportPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("reports", Context.MODE_PRIVATE)

    /**
     * Testing builds (a fourth version number, Releases.isTesting) start with Send reports on and don't ask (Joey
     * 2026-11-11: "send reports needs to be on by default for test apks"); Settings › About still turns it off.
     * Public builds start Off. Only a choice the user made is stored, so a public build installed over a testing
     * one is Off and asks again, unless they set it themselves.
     */
    internal fun onByDefault(version: String) = com.joeysretro.joey64.launcher.update.Releases.isTesting(version)
    private val testing: Boolean get() = onByDefault(com.joeysretro.joey64.BuildConfig.VERSION_NAME)

    fun on(ctx: Context) = p(ctx).getBoolean("on", testing)

    /** The library's question was answered (or Send reports set in Settings): it is never asked again. */
    fun asked(ctx: Context) = p(ctx).getBoolean("asked", testing)
    fun setAsked(ctx: Context) = p(ctx).edit().putBoolean("asked", true).apply()

    /**
     * On: a report ID is made if there's none, and crashes from before are left out (only what happens
     * from now on is sent). Off: everything not sent yet is deleted.
     */
    fun setOn(ctx: Context, on: Boolean) {
        val e = p(ctx).edit().putBoolean("on", on).putBoolean("asked", true)
        if (on && !ReportPrefs.on(ctx)) e.putLong("exits_after", System.currentTimeMillis())
        e.apply()
        if (on) install(ctx) else ReportFiles.clear(ctx)
    }

    /** The random report ID (a UUID v4), made the first time it's needed. */
    fun install(ctx: Context): String = p(ctx).getString("install", null) ?: newId(ctx)

    /** Report ID › Reset: a new random ID; reports from before can no longer be linked to the next ones. */
    fun newId(ctx: Context): String = UUID.randomUUID().toString().also { p(ctx).edit().putString("install", it).apply() }

    /** The UTC day (yyyy-mm-dd) of the last report the server took. */
    fun lastSentDay(ctx: Context): String? = p(ctx).getString("sent_day", null)
    fun setLastSentDay(ctx: Context, day: String) = p(ctx).edit().putString("sent_day", day).putLong("sent_at", System.currentTimeMillis()).apply()
    /** When the last report the server took went (ms), for Send now's line; 0 before the first. */
    fun lastSentAt(ctx: Context): Long = p(ctx).getLong("sent_at", 0L)

    /** Process exits recorded before this time (ms) are not reported. On by default (a testing build): since it was installed. */
    fun exitsAfter(ctx: Context): Long = p(ctx).getLong("exits_after", if (testing) installedAt(ctx) else Long.MAX_VALUE)
    private fun installedAt(ctx: Context): Long =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime } catch (e: Exception) { System.currentTimeMillis() }
    fun setExitsAfter(ctx: Context, t: Long) = p(ctx).edit().putLong("exits_after", t).apply()
}

object ReportFiles {
    fun dir(ctx: Context) = File(ctx.filesDir, "reports")
    fun pending(ctx: Context) = File(dir(ctx), "pending").apply { mkdirs() }
    fun open(ctx: Context) = File(dir(ctx), "open").apply { mkdirs() }
    fun coversSent(ctx: Context) = File(dir(ctx), "covers-sent.txt")

    /** Send reports Off: nothing waiting is kept. covers-sent.txt goes too, so turning it on again starts fresh. */
    fun clear(ctx: Context) { dir(ctx).deleteRecursively() }

    /** Writes [text] to [f] whole or not at all. */
    fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, f.name + ".part")
        part.writeText(text)
        if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) part.delete() }
    }
}

/**
 * One play session, kept by the game screen while Send reports is on: the time played at normal
 * speed and the frames run and drawn in it (paused, the pause menu and fast-forward don't count), and
 * the host's numbers for the whole run ([native], NativeBridge.sessionStats). [base] has the session's
 * fixed fields (md5, renderer, scale, settings, refreshHz, fromState, cheats) and gpu / driver.
 */
class SessionTracker(private val openFile: File, private val pendingDir: File, private val base: JSONObject) {
    private var ms = 0L
    private var run = 0L
    private var drawn = 0L
    private var lastAt = -1L
    private var lastRun = 0
    private var lastDrawn = 0
    private var allDrawn = 0L
    private var stats: DoubleArray? = null

    /**
     * Every few seconds, and when play stops or starts: the time since the last call counts when
     * [counting] (playing at normal speed through all of it, as far as the caller knows).
     */
    @Synchronized fun tick(counting: Boolean, framesRun: Int, framesDrawn: Int, now: Long) {
        if (lastAt >= 0 && counting && now > lastAt) {
            ms += now - lastAt
            run += (framesRun - lastRun).coerceAtLeast(0)
            drawn += (framesDrawn - lastDrawn).coerceAtLeast(0)
        }
        if (lastAt >= 0) allDrawn += (framesDrawn - lastDrawn).coerceAtLeast(0) else allDrawn = framesDrawn.toLong().coerceAtLeast(0)
        lastAt = now; lastRun = framesRun; lastDrawn = framesDrawn
    }

    val seconds get() = ms / 1000

    /** The host's numbers so far (NativeBridge.sessionStats), taken with each tick. */
    @Synchronized fun native(v: DoubleArray, n: Int) { stats = v.copyOf(n) }

    /** A fixed field that can change while playing (cheats turned on in the pause menu). */
    @Synchronized fun put(key: String, value: Any) { base.put(key, value) }

    /** The session as sent, ended by "quit" or "crash"; the gpu and driver stay in it for the sender. */
    @Synchronized fun event(ended: String): JSONObject = JSONObject(base.toString()).apply {
        put("type", "session")
        put("seconds", seconds)
        if (ms >= 1000) {
            put("emuFps", round1(run * 1000.0 / ms))
            put("drawnFps", round1(drawn * 1000.0 / ms))
            put("dupes", round1((run - drawn).coerceAtLeast(0) * 1000.0 / ms))
        }
        put("frames", allDrawn)
        stats?.let { putNative(this, it) }
        put("ended", ended)
    }

    private var done = false

    /** The session so far, for when the process ends without [finish]. Any thread; nothing once finished. */
    @Synchronized fun writeOpen() { if (!done && ms > 0) ReportFiles.write(openFile, event("crash").toString()) }

    /** Quit: the session waits in pending/ for the next send, if [keep] says it's worth sending. */
    @Synchronized fun finish() {
        if (done) return
        done = true
        if (keep(seconds, allDrawn)) ReportFiles.write(File(pendingDir, "${System.currentTimeMillis()}-${openFile.nameWithoutExtension}.json"), event("quit").toString())
        openFile.delete()
    }

    companion object {
        /** Less than this is a game started to check something: not worth a session. */
        const val MIN_SECONDS = 20L
        /** A game that ran this long and drew fewer than [BOOT_FRAMES] frames didn't boot: kept, it's what we most need to see. */
        const val BOOT_SECONDS = 5L
        const val BOOT_FRAMES = 60L

        fun keep(seconds: Long, frames: Long) = seconds >= MIN_SECONDS || (seconds >= BOOT_SECONDS && frames < BOOT_FRAMES)

        /** NativeBridge.sessionStats' order; -1 (or a missing value) means not known, left out. */
        fun putNative(o: JSONObject, v: DoubleArray) {
            fun at(i: Int) = v.getOrNull(i)?.takeIf { it >= 0 && !it.isNaN() }
            at(0)?.let { o.put("underruns", it.toLong()) }
            at(1)?.let { o.put("lagMs", round1(it)) }
            at(2)?.let { o.put("lagMaxMs", round1(it)) }
            at(3)?.let { o.put("thermalMax", it.toInt()) }
            at(4)?.let { o.put("headroomMax", Math.round(it * 100) / 100.0) }
            at(5)?.let { o.put("thermalDrops", it.toInt()) }
            at(6)?.let { o.put("rdpSync", it >= 1) }
            at(7)?.let { o.put("cpuCore", if (it >= 1) "interpreter" else "dynarec") }
            at(8)?.let { o.put("firstFrameMs", it.toLong()) }
            val timed = at(9) ?: 0.0
            if (timed > 0) {
                at(10)?.let { o.put("p95Ms", it.toInt()) }
                at(11)?.let { o.put("p99Ms", it.toInt()) }
                at(12)?.let { o.put("slowFrames", it.toLong()) }
                o.put("framesTimed", timed.toLong())
            }
        }
        private fun round1(v: Double) = Math.round(v * 10) / 10.0
    }
}

/** The report the server takes (server/reports/src/validate.js, format 1). Pure: unit tested. */
object ReportJson {
    /** [screenW] x [screenH]: the main screen in pixels, long side first; [dpi] its density (dp = px * 160 / dpi). */
    data class Device(val maker: String?, val model: String?, val soc: String?, val gpu: String?, val driver: String?, val android: Int,
                      val screenW: Int? = null, val screenH: Int? = null, val dpi: Int? = null)

    const val MAX_EVENTS = 200
    private val MD5 = Regex("^[0-9a-f]{32}$")
    private val HEADER = Regex("^[A-Za-z0-9 ]{4}$")
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f]")

    /** A string the server will take: no control characters, at most [max] long. */
    fun clean(s: String?, max: Int): String? = s?.replace(CONTROL, " ")?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    fun header(s: String?): String? = s?.takeIf { HEADER.matches(it) }

    fun md5(s: String?): String? = s?.lowercase()?.takeIf { MD5.matches(it) }

    fun report(install: String, app: String, core: String?, d: Device, events: List<JSONObject>): JSONObject = JSONObject().apply {
        put("format", 1)
        put("install", install)
        put("app", clean(app, 32) ?: "?")
        clean(core, 64)?.let { put("core", it) }
        put("device", JSONObject().apply {
            clean(d.maker, 64)?.let { put("maker", it) }
            clean(d.model, 64)?.let { put("model", it) }
            clean(d.soc, 64)?.let { put("soc", it) }
            clean(d.gpu, 64)?.let { put("gpu", it) }
            clean(d.driver, 96)?.let { put("driver", it) }
            if (d.android in 21..99) put("android", d.android)
            // Added 2026-11-08 (server migrations/0003-screen.sql); the server takes each on its own.
            d.screenW?.takeIf { it in 100..10000 }?.let { put("screenW", it) }
            d.screenH?.takeIf { it in 100..10000 }?.let { put("screenH", it) }
            d.dpi?.takeIf { it in 60..1000 }?.let { put("dpi", it) }
        })
        put("events", JSONArray(events))
    }

    /** A session event as the server takes it: the sender's own fields (gpu, driver) taken out. */
    fun session(stored: JSONObject, header: String?): JSONObject? {
        val md5 = md5(stored.optString("md5")) ?: return null
        return JSONObject().apply {
            put("type", "session")
            put("md5", md5)
            header(header)?.let { put("header", it) }
            clean(stored.optString("renderer"), 16)?.let { put("renderer", it) }
            stored.optInt("scale", 0).takeIf { it in 1..16 }?.let { put("scale", it) }
            stored.optJSONObject("settings")?.let { put("settings", settings(it)) }
            put("seconds", stored.optLong("seconds").coerceIn(0, 7 * 24 * 3600))
            for (k in listOf("emuFps", "drawnFps", "dupes")) if (stored.has(k)) put(k, stored.getDouble(k).coerceIn(0.0, 1000.0))
            // Numbers: name to the largest value the server takes (server/reports/src/validate.js).
            for ((k, max) in NUMBERS) if (stored.has(k)) {
                val v = stored.optDouble(k)
                if (!v.isNaN() && v >= 0) put(k, if (k in WHOLE) v.coerceAtMost(max).toLong() else v.coerceAtMost(max))
            }
            for (k in listOf("rdpSync", "fromState")) if (stored.has(k)) put(k, stored.optBoolean(k))
            stored.optString("cpuCore").takeIf { it == "interpreter" || it == "dynarec" }?.let { put("cpuCore", it) }
            // Added 2026-11-09 (server migrations/0004-gpu-rules.sql): why this driver and renderer ran.
            for (k in listOf("driverRule", "rendererRule")) clean(stored.optString(k), 120)?.let { put(k, it) }
            if (stored.has("trialLevel")) stored.optInt("trialLevel", -1).takeIf { it in 0..2 }?.let { put("trialLevel", it) }
            stored.optString("pickedBy").takeIf { it == "auto" || it == "user" }?.let { put("pickedBy", it) }
            put("ended", if (stored.optString("ended") == "crash") "crash" else "quit")
        }
    }

    private val NUMBERS = listOf("frames" to 1e9, "framesTimed" to 1e9, "firstFrameMs" to 600_000.0, "p95Ms" to 100.0, "p99Ms" to 100.0,
        "slowFrames" to 1e9, "underruns" to 100_000.0, "lagMs" to 1000.0, "lagMaxMs" to 1000.0, "thermalMax" to 6.0, "headroomMax" to 10.0,
        "thermalDrops" to 1e6, "refreshHz" to 500.0, "cheats" to 1000.0)
    private val WHOLE = setOf("frames", "framesTimed", "firstFrameMs", "p95Ms", "p99Ms", "slowFrames", "underruns", "thermalMax", "thermalDrops", "refreshHz", "cheats")

    /** At most 60 settings, names up to 48 characters, string values up to 64. */
    fun settings(o: JSONObject): JSONObject = JSONObject().apply {
        for (k in o.keys().asSequence().sorted().take(60)) {
            val name = clean(k, 48) ?: continue
            when (val v = o.get(k)) {
                is Boolean, is Int, is Long, is Double -> put(name, v)
                else -> clean(v.toString(), 64)?.let { put(name, it) }
            }
        }
    }

    /** A game with no cover ("none") or one whose downloaded cover the user replaced ("replaced", with that [cover]). */
    fun cover(md5: String, kind: String, header: String?, title: String?, cover: String?): JSONObject? {
        val m = md5(md5) ?: return null
        return JSONObject().apply {
            put("type", if (kind == "none") "nocover" else "replaced")
            put("md5", m)
            header(header)?.let { put("header", it) }
            clean(title, 200)?.let { put("title", it) }
            if (kind != "none") put("cover", clean(cover, 64) ?: return null)
        }
    }

    fun crash(md5: String?, where: String, summary: String): JSONObject = JSONObject().apply {
        put("type", "crash")
        md5(md5)?.let { put("md5", it) }
        put("where", where)
        // Line breaks and tabs are fine in a summary; other control characters aren't.
        put("summary", summary.replace(Regex("[\\u0000-\\u0008\\u000b-\\u001f\\u007f]"), " ").take(2000).ifEmpty { "?" })
    }
}
