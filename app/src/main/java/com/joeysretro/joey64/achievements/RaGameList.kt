package com.joeysretro.joey64.achievements

import android.content.Context
import com.joeysretro.joey64.JLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Which N64 games have achievements, for the library's Has achievements filter (approved Show popup,
 * 2026-11-04; built 2026-11-07). RetroAchievements' `systemgames` request (rcheevos'
 * rc_api_init_fetch_games_list_request, unauthenticated: nothing about the account is sent) lists
 * every N64 game with its achievement count and supported hashes. The hashes of games with at least one
 * achievement are kept in `files/ra/n64-sets.json` and fetched again once a day while signed in.
 * The library's md5 is RetroAchievements' N64 hash, so they match as they are.
 */
object RaGameList {
    private const val TAG = "joey64ra"
    private const val N64 = 2
    private const val URL_DOREQUEST = "https://retroachievements.org/dorequest.php"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    private fun file(context: Context) = File(File(context.filesDir, "ra"), "n64-sets.json")

    /** The hashes of games with achievements, from the last fetch; null when never fetched. */
    fun hashes(context: Context): Set<String>? = try {
        val f = file(context)
        if (!f.isFile) null else JSONObject(f.readText()).getJSONArray("hashes").let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }
    } catch (e: Exception) { JLog.e(TAG, "set list: can't read it: $e"); null }

    /** `systemgames`' answer: the hashes (lower case) of games with at least one achievement; null if it isn't one. */
    fun parse(json: String): Set<String>? = try {
        val o = JSONObject(json)
        if (!o.optBoolean("Success", false)) null else {
            val games = o.getJSONArray("Response")
            val out = HashSet<String>()
            for (i in 0 until games.length()) {
                val g = games.getJSONObject(i)
                if (g.optInt("NumAchievements", 0) <= 0) continue
                val h = g.optJSONArray("SupportedHashes") ?: continue
                for (j in 0 until h.length()) out += h.getString(j).lowercase()
            }
            out
        }
    } catch (e: Exception) { null }

    /** Fetches the list when there's none or it's a day old (blocking; call off the main thread). True when it changed. */
    fun refreshIfOld(context: Context, userAgent: String): Boolean {
        val f = file(context)
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) return false
        return try {
            val c = URL(URL_DOREQUEST).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", userAgent)
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write("r=systemgames&s=$N64".toByteArray()) }
            val body = try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
            val set = body?.let { parse(String(it)) }
            if (set == null) { JLog.w(TAG, "set list: no usable answer (${body?.size ?: 0} bytes)"); return false }
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeText(JSONObject().put("hashes", JSONArray(set.sorted())).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            JLog.i(TAG, "set list: ${set.size} N64 hashes with achievements")
            true
        } catch (e: Exception) {
            JLog.w(TAG, "set list: not fetched: ${e.javaClass.simpleName}")
            false
        }
    }
}
