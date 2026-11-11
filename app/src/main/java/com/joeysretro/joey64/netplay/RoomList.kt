package com.joeysretro.joey64.netplay

import android.content.Context
import com.joeysretro.joey64.JLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Netplay's worldwide list of public rooms (Joey 2026-11-10: "public vs private rooms with a lobby browser", kept by
 * our Cloudflare Worker, server/rooms). Only the list: the games still connect device to device with the room code.
 * A public room the router opened tells the list its game, players, host name and code every 30 s and is gone a
 * minute after it stops; a private room is never on it. The Netplay page reads it every 10 s while it's open.
 */
object RoomList {
    private const val TAG = "playtogether"
    const val SERVER = "https://joey64-rooms.joeysretrohandhelds.workers.dev"
    private const val LIST_EVERY_MS = 10_000L
    private const val POST_EVERY_MS = 30_000L

    data class Listing(val name: String, val device: String, val game: String, val md5: String, val players: Int, val code: String)
    data class Room(val id: String, val name: String, val device: String, val game: String, val md5: String, val players: Int,
                    val ip: String, val port: Int, val code: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var base = SERVER
    private val _rooms = MutableStateFlow<List<Room>?>(null)
    /** The public rooms, null until the first answer (or when it can't be reached). */
    val rooms: StateFlow<List<Room>?> = _rooms

    /** Debug builds: files/dev_rooms_base (a local `wrangler dev` through adb reverse) instead of the server. */
    fun init(context: Context) {
        if (com.joeysretro.joey64.BuildConfig.DEBUG)
            File(context.filesDir, "dev_rooms_base").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { base = it.trimEnd('/') }
    }

    private var watchJob: Job? = null
    private var watchers = 0

    fun watch() {
        watchers++
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (isActive) {
                _rooms.value = fetch()
                delay(LIST_EVERY_MS)
            }
        }
    }

    fun unwatch() {
        watchers = (watchers - 1).coerceAtLeast(0)
        if (watchers == 0) { watchJob?.cancel(); watchJob = null }
    }

    private fun fetch(): List<Room>? = try {
        val c = URL("$base/rooms").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        parse(text)
    } catch (e: Exception) { JLog.i(TAG, "public rooms: can't reach the list: $e"); null }

    /** The Worker's `{"rooms":[...]}`; rooms with a bad field are left out. */
    fun parse(text: String): List<Room> {
        val a = JSONObject(text).optJSONArray("rooms") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val code = o.optString("code")
            val room = RoomCode.decode(code) ?: return@mapNotNull null
            Room(o.optString("id"), o.optString("name").take(48), o.optString("device").take(48), o.optString("game").take(80),
                o.optString("md5").lowercase(), o.optInt("players", 1).coerceIn(1, 4), room.ip, room.port, RoomCode.clean(code))
        }
    }

    // ---- this device's public room ----

    private var listing: Listing? = null
    private var postJob: Job? = null
    private val id = java.util.UUID.randomUUID().toString().replace("-", "")
    private val secret = java.util.UUID.randomUUID().toString().replace("-", "")

    /** The room to list (null: none). Posted at once when it changes, then every 30 s; null takes it off the list. */
    fun hosting(l: Listing?) {
        if (l == listing) return
        val was = listing
        listing = l
        if (l == null) {
            postJob?.cancel(); postJob = null
            if (was != null) scope.launch { send("DELETE", "$base/rooms/$id", null) }
            return
        }
        postJob?.cancel()
        postJob = scope.launch {
            while (isActive) {
                val now = listing ?: break
                send("POST", "$base/rooms", JSONObject().put("id", id).put("secret", secret).put("name", now.name).put("device", now.device)
                    .put("game", now.game).put("md5", now.md5).put("players", now.players).put("code", now.code).toString())
                delay(POST_EVERY_MS)
            }
        }
    }

    private fun send(method: String, url: String, body: String?) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method; c.connectTimeout = 5000; c.readTimeout = 5000
            c.setRequestProperty("X-Room-Secret", secret)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) JLog.w(TAG, "public rooms: $method answered $code")
            else if (method == "POST" && listing != null) JLog.i(TAG, "public rooms: listed")
            c.disconnect()
        } catch (e: Exception) { JLog.i(TAG, "public rooms: $method failed: $e") }
    }
}
