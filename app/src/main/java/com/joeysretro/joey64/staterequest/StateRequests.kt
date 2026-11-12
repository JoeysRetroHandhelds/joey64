package com.joeysretro.joey64.staterequest

import android.content.Context
import com.joeysretro.joey64.BuildConfig
import com.joeysretro.joey64.Capped
import com.joeysretro.joey64.JLog
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asking players for a state of games we need data for (Joey 2026-11-12, joey64-notes queue.md "Ask players for a
 * state of games we need data for"): a wanted list in joey64-data names the games and what we need; when a listed game
 * is quit and both checks pass, the app asks once (Yes / No / Don't ask again for this game / Don't ask again ever).
 * Yes sends a state from a few minutes before the quit and the game's save ([StateUpload]). The popup is Joey's to
 * design: nothing here draws anything, [check] only says whether there is something to ask.
 *
 * The two checks: (1) at least [MIN_PLAY_SECONDS] of counted play (the host counts only the game running: paused, our
 * menus and background time don't count); (2) a state taken while the game drew a 3D scene, not a title screen, menu
 * or logo. Problem games skip (2). Both come from the host's ring of recent states, `files/states/ring/<md5>/meta.json`,
 * written when a session ends (agreed with the host side 2026-11-12).
 */
object StateRequests {
    private const val TAG = "Joey64StateReq"
    /** Five minutes of counted play (Joey 2026-11-12, "Yes, add them"). */
    const val MIN_PLAY_SECONDS = 5 * 60L
    /** A state at least this long before the quit: not the pause menu moment (queue.md). */
    const val BEFORE_QUIT_MS = 2 * 60_000L

    enum class Kind {
        /** A gameplay state (the widescreen finder): needs the 3D check. */
        Gameplay,
        /** A state near a problem: any recent state will do. */
        Problem;

        companion object {
            fun parse(s: String) = when (s.trim().lowercase()) { "gameplay" -> Gameplay; "problem" -> Problem; else -> null }
        }
    }

    /** One game on the wanted list: its ROM [md5], [title], what [kind] of state we need, and [need], the line players see. */
    data class Wanted(val md5: String, val kind: Kind, val need: String, val title: String = "", val why: String = "")

    /**
     * One state in the ring: [path] (absolute, files/states/ring/<md5>/<n>.raw: the core's raw M64+SAVE data), taken at
     * [takenAt] (epoch ms) after [playAt] seconds of counted play, [drew3d] when a 3D scene was on screen.
     */
    data class RingEntry(val path: String, val takenAt: Long, val drew3d: Boolean, val playAt: Long = MIN_PLAY_SECONDS)

    /** A session's ring: its counted play and when it ended (epoch ms), then its states. */
    data class Ring(val playSeconds: Long, val quitAt: Long, val entries: List<RingEntry>)

    /** What to ask about: the game, the state to send and why it's wanted. */
    data class Ask(val wanted: Wanted, val state: RingEntry)

    /** The popup's answers (queue.md). No asks again after another session that passes. */
    enum class Answer { Yes, No, NotThisGame, Never }

    private val MD5 = Regex("[0-9a-f]{32}")

    /**
     * joey64-data's `wanted/wanted.json` (the host side's format, 2026-11-12): {"version":1,"games":[{md5 (the ROM's,
     * .z64 byte order, lower case), title, kind "gameplay"|"problem", need (the line players see), why, enough}]}.
     * A game that isn't one is skipped; a list that isn't one is empty.
     */
    fun parseWanted(text: String): Map<String, Wanted> {
        val m = LinkedHashMap<String, Wanted>()
        if (text.isBlank()) return m
        val games = try { JSONObject(text).optJSONArray("games") } catch (e: org.json.JSONException) { null } ?: return m
        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            val md5 = g.optString("md5").trim().lowercase()
            val kind = Kind.parse(g.optString("kind")) ?: continue
            if (MD5.matches(md5)) m[md5] = Wanted(md5, kind, g.optString("need").trim(), g.optString("title").trim(), g.optString("why").trim())
        }
        return m
    }

    /** The ring's meta.json; null when it isn't one. */
    fun parseRing(json: String): Ring? = try {
        val o = JSONObject(json)
        val a = o.optJSONArray("entries")
        val entries = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            val e = a.optJSONObject(i) ?: return@mapNotNull null
            val path = e.optString("path")
            if (path.isEmpty() || path.contains("..")) null
            else RingEntry(path, e.optLong("takenAt"), e.optBoolean("drew3d"), e.optLong("playAt", MIN_PLAY_SECONDS))
        }
        Ring(o.optLong("playSeconds"), o.optLong("quitAt"), entries)
    } catch (e: org.json.JSONException) { null }

    /**
     * The state to send for [kind] from [ring]: the newest taken at least [BEFORE_QUIT_MS] before the quit, and for a
     * gameplay state one with a 3D scene taken after [MIN_PLAY_SECONDS] of play (the 3D check alone passes a title drawn
     * in 3D: Batman's did, host side 2026-11-12). A problem state may be any of them, newest first. Null: none passes.
     */
    fun pick(ring: Ring, kind: Kind): RingEntry? {
        if (ring.quitAt <= 0) return null  // no quitAt: the session didn't end cleanly (meta.json is written at the end)
        val early = ring.entries.filter { it.takenAt <= ring.quitAt - BEFORE_QUIT_MS }.sortedByDescending { it.takenAt }
        return when (kind) {
            Kind.Gameplay -> early.firstOrNull { it.drew3d && it.playAt >= MIN_PLAY_SECONDS }
            Kind.Problem -> early.firstOrNull() ?: ring.entries.maxByOrNull { it.takenAt }
        }
    }

    /**
     * The don't-ask answers kept on the device: Don't ask again ever, the games not to ask about, and the games done
     * (sent from here, or the server said it has enough or doesn't want it).
     */
    data class Prefs(val never: Boolean, val skipped: Set<String>, val sent: Set<String>)

    /**
     * Whether to ask about [md5] after a session: it's wanted, no don't-ask answer covers it, this install hasn't sent
     * one for it, the session had [MIN_PLAY_SECONDS] of play, and a state passes [pick].
     */
    fun decide(md5: String, wanted: Map<String, Wanted>, prefs: Prefs, ring: Ring?): Ask? {
        val w = wanted[md5.lowercase()] ?: return null
        if (prefs.never || w.md5 in prefs.skipped || w.md5 in prefs.sent) return null
        if (ring == null || ring.playSeconds < MIN_PLAY_SECONDS) return null
        val state = pick(ring, w.kind) ?: return null
        return Ask(w, state)
    }

    // ---- On the device ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("state_requests", Context.MODE_PRIVATE)

    fun prefsOf(ctx: Context): Prefs {
        val p = prefs(ctx)
        return Prefs(p.getBoolean("never", false), p.getStringSet("skipped", emptySet()).orEmpty(), p.getStringSet("sent", emptySet()).orEmpty())
    }

    /** [md5]'s ring from the session just ended, if the host wrote one. */
    fun ringOf(ctx: Context, md5: String): Ring? {
        val f = File(ringDir(ctx, md5), "meta.json")
        return try { if (f.exists()) parseRing(f.readText()) else null } catch (e: IOException) { null }
    }

    /** The host's ring for [md5] (its stateRingDir). */
    fun ringDir(ctx: Context, md5: String) = File(File(File(ctx.filesDir, "states"), "ring"), md5.lowercase())

    /** The server has enough of [md5] (410) or doesn't want it (404): don't ask about it again. */
    fun done(ctx: Context, md5: String) {
        val p = prefs(ctx)
        p.edit().putStringSet("sent", p.getStringSet("sent", emptySet()).orEmpty() + md5.lowercase()).apply()
    }

    /** After a session of [md5]: something to ask about, or null. Reads the network for the wanted list (call off the main thread). */
    fun check(ctx: Context, md5: String): Ask? {
        val wanted = wanted(ctx)
        if (md5.lowercase() !in wanted) return null
        val ask = decide(md5, wanted, prefsOf(ctx), ringOf(ctx, md5))
        JLog.i(TAG, "state request for $md5: ${if (ask == null) "nothing to ask" else "ask (${ask.wanted.kind}, ${ask.state.path})"}")
        return ask
    }

    /**
     * The popup's [answer] to [ask]. Yes queues the state and save ([StateUpload]) and marks the game sent, so it isn't
     * asked about again from this install; NotThisGame and Never are kept; No only closes.
     */
    fun answer(ctx: Context, ask: Ask, answer: Answer, saveName: String?) {
        val p = prefs(ctx)
        fun add(key: String) = p.edit().putStringSet(key, p.getStringSet(key, emptySet()).orEmpty() + ask.wanted.md5).apply()
        when (answer) {
            Answer.Yes -> { if (StateUpload.queue(ctx, ask, saveName)) add("sent") }
            Answer.No -> Unit
            Answer.NotThisGame -> add("skipped")
            Answer.Never -> p.edit().putBoolean("never", true).apply()
        }
        JLog.i(TAG, "state request for ${ask.wanted.md5}: $answer")
    }

    /** Settings › About › Ask for save states (approved mockup 2026-11-12, screen 4): Off is Don't ask again's answer. */
    fun asking(ctx: Context) = !prefs(ctx).getBoolean("never", false)
    fun setAsking(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("never", !on).apply() }

    // ---- The wanted list, read from joey64-data like our covers ----

    /**
     * Whether a start of [md5] should keep the host's ring of recent states: it's on the kept wanted list (no network
     * read: this runs as the game starts) and no don't-ask answer covers it, so a game nobody will be asked about
     * doesn't pay for the ring.
     */
    fun ringFor(ctx: Context, md5: String): Boolean {
        val f = File(dir(ctx), WANTED_FILE)
        val wanted = try { if (f.exists()) parseWanted(f.readText()) else return false } catch (e: IOException) { return false }
        val w = wanted[md5.lowercase()] ?: return false
        val p = prefsOf(ctx)
        return !p.never && w.md5 !in p.skipped && w.md5 !in p.sent
    }

    /** The host's switch for the ring (MainActivity.EXTRA_STATE_RING on the host side's branch). */
    const val EXTRA_STATE_RING = "com.joeysretro.joey64.STATE_RING"

    /** The wanted list; last week's when the network is down, empty when there's none yet. */
    fun wanted(ctx: Context): Map<String, Wanted> {
        val f = File(dir(ctx), WANTED_FILE)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > WANTED_DAYS * 24 * 3600_000L) {
            try {
                val text = String(get(base(ctx) + WANTED), Charsets.UTF_8)
                f.parentFile?.mkdirs()
                val part = File(f.parentFile, f.name + ".part")
                part.writeText(text)
                if (!part.renameTo(f)) { part.delete(); throw IOException("can't keep ${f.name}") }
            } catch (e: FileNotFoundException) {
                // No list published yet: nothing is wanted. Kept as empty so it isn't asked for every session.
                f.parentFile?.mkdirs(); f.writeText("")
            } catch (e: IOException) {
                JLog.w(TAG, "wanted list not read ($e), ${if (f.exists()) "using last week's" else "none yet"}")
            }
        }
        return try { if (f.exists()) parseWanted(f.readText()) else emptyMap() } catch (e: IOException) { emptyMap() }
    }

    internal fun dir(ctx: Context) = File(ctx.filesDir, "staterequests")

    /** joey64-data's release branch; debug builds read a stand-in from files/dev_wanted_base (a feed on the PC). */
    private fun base(ctx: Context): String {
        if (BuildConfig.DEBUG) {
            val dev = try { File(ctx.filesDir, "dev_wanted_base").readText().trim() } catch (e: IOException) { "" }
            if (dev.isNotEmpty()) return dev.trimEnd('/') + "/"
        }
        return BASE
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Joey64/${BuildConfig.VERSION_NAME}")
        try {
            val code = c.responseCode
            if (code == 404) throw FileNotFoundException(url)
            if (code != 200) throw IOException("HTTP $code from $url")
            return c.inputStream.use { Capped.bytes(it, MAX_REPLY, url.substringAfterLast('/')) }
        } finally { c.disconnect() }
    }

    const val BASE = "https://raw.githubusercontent.com/JoeysRetroHandhelds/joey64-data/release/"
    private const val WANTED = "wanted/wanted.json"
    private const val WANTED_FILE = "wanted.json"
    private const val WANTED_DAYS = 7
    private const val MAX_REPLY = 2 * Capped.MB
}
