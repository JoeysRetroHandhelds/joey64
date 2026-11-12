package com.joeysretro.joey64.achievements

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.settings.SignInStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Every library game's achievements before it's played (approved D3 2026-11-11: "fetch every library game's
 * achievements when the ROMs folder is scanned, once signed in"; the look-up joins the library's scan, no
 * progress page of its own). For each game RetroAchievements has a set for ([RaGameList]) and the progress
 * cache has nothing yet, rcheevos' two read-only requests: `achievementsets` by the ROM's hash (the sets and
 * their achievements, rc_api_init_fetch_game_sets_request) and `unlocks` (which ones this account has,
 * softcore, rc_api_init_fetch_user_unlocks_request). The answer goes into `ra_sets` in the same JSON shapes
 * rc_client's progress cache writes ([RaCache]), marked `"fetched":true`; a game that's played replaces it with
 * rc_client's own, and fetched ones are looked up again after a week (unlocks on other devices). One request at
 * a time; the sign-in key is sent only in the POST body, never logged.
 */
object RaLibraryFetch {
    private const val TAG = "joey64ra"
    private const val URL_DOREQUEST = "https://retroachievements.org/dorequest.php"
    private const val MEDIA = "https://media.retroachievements.org"
    private const val AGAIN_MS = 7 * 24 * 60 * 60 * 1000L
    private const val CORE = 3
    private const val UNOFFICIAL = 5

    @Volatile private var running = false

    /** Blocking (call off the main thread). The number of games whose sets were kept. */
    suspend fun fetchMissing(context: Context, userAgent: String): Int {
        if (running) return 0
        running = true
        try {
            val app = context.applicationContext
            val sets = RaGameList.hashes(app) ?: return 0
            val kept = try { SignInStore(app).load() } catch (e: Exception) { null } ?: return 0
            if (RaBridge.account.value == null) return 0
            val db = LibraryDb.get(app)
            val todo = db.md5sNeedingSets(System.currentTimeMillis() - AGAIN_MS).filter { it in sets }
            if (todo.isEmpty()) return 0
            JLog.i(TAG, "library achievements: looking up ${todo.size} games")
            var done = 0
            for (md5 in todo) {
                if (RaBridge.account.value == null) break
                val (game, list) = fetchOne(md5, kept.user, kept.token, userAgent) ?: continue
                if (db.saveFetchedAchievements(md5, game, list)) done++
                kotlinx.coroutines.delay(200)
            }
            JLog.i(TAG, "library achievements: $done of ${todo.size} kept")
            return done
        } finally {
            running = false
        }
    }

    private fun fetchOne(md5: String, user: String, token: String, agent: String): Pair<JSONObject, String>? {
        val base = "u=" + enc(user) + "&t=" + enc(token)
        val sets = post("r=achievementsets&$base&m=$md5", agent) ?: return null
        if (!sets.optBoolean("Success", false)) return null
        val gameId = sets.optInt("GameId")
        if (gameId <= 0) return null
        val unlocks = post("r=unlocks&$base&g=$gameId&h=0", agent)
            ?.takeIf { it.optBoolean("Success", false) }?.optJSONArray("UserUnlocks")
            ?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.optInt(it) } } ?: return null
        return build(md5, sets, unlocks)
    }

    /** `achievementsets` + the unlocked ids as rc_client's gameJson / achievementList would give them. Kept apart for tests. */
    fun build(md5: String, sets: JSONObject, unlocked: Set<Int>): Pair<JSONObject, String>? {
        val arr = sets.optJSONArray("Sets") ?: return null
        val gameId = sets.optInt("GameId")
        val list = JSONArray()
        val setsOut = JSONArray()
        var total = 0; var got = 0; var points = 0; var pointsGot = 0
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val setId = s.optInt("GameId").takeIf { it > 0 } ?: gameId
            val ach = s.optJSONArray("Achievements") ?: JSONArray()
            var sTotal = 0; var sGot = 0; var sPoints = 0; var sPointsGot = 0
            for (j in 0 until ach.length()) {
                val a = ach.optJSONObject(j) ?: continue
                val flags = a.optInt("Flags", CORE)
                if (flags != CORE && flags != UNOFFICIAL) continue
                val id = a.optInt("ID")
                val pts = a.optInt("Points")
                val isGot = id in unlocked
                if (flags == CORE) {
                    sTotal++; sPoints += pts
                    if (isGot) { sGot++; sPointsGot += pts }
                }
                val badge = a.optString("BadgeName")
                val bucket = when { flags == UNOFFICIAL -> RaAchievement.UNOFFICIAL; isGot -> RaAchievement.UNLOCKED; else -> RaAchievement.LOCKED }
                list.put(JSONObject()
                    .put("id", id).put("title", a.optString("Title")).put("description", a.optString("Description")).put("points", pts)
                    .put("badge", a.optString("BadgeURL").ifEmpty { "$MEDIA/Badge/$badge.png" })
                    .put("badgeLocked", a.optString("BadgeLockedURL").ifEmpty { "$MEDIA/Badge/${badge}_lock.png" })
                    .put("progress", "").put("percent", 0).put("rarity", a.optDouble("Rarity", 0.0)).put("rarityHardcore", a.optDouble("RarityHardcore", 0.0))
                    .put("unlockTime", 0).put("state", if (isGot) 2 else 1).put("category", if (flags == UNOFFICIAL) 2 else 1)
                    .put("bucket", bucket).put("unlocked", if (isGot) 1 else 0).put("type", typeOf(a.optString("Type")))
                    .put("section", when (bucket) { RaAchievement.UNLOCKED -> "Unlocked"; RaAchievement.UNOFFICIAL -> "Unofficial"; else -> "Locked" })
                    .put("sectionType", bucket).put("set", setId))
            }
            setsOut.put(JSONObject().put("id", setId).put("title", s.optString("Title").ifEmpty { sets.optString("Title") })
                .put("achievements", sTotal).put("unlocked", sGot).put("points", sPoints).put("pointsUnlocked", sPointsGot))
            total += sTotal; got += sGot; points += sPoints; pointsGot += sPointsGot
        }
        val game = JSONObject().put("id", gameId).put("title", sets.optString("Title")).put("hash", md5.lowercase())
            .put("badge", sets.optString("ImageIconUrl")).put("achievements", total).put("unlocked", got)
            .put("points", points).put("pointsUnlocked", pointsGot).put("beaten", 0).put("completed", 0).put("fetched", true)
        if (setsOut.length() > 1) game.put("sets", setsOut)
        return game to list.toString()
    }

    /** rc_client's types: 1 missable, 2 progression, 3 win condition. */
    private fun typeOf(t: String) = when (t) { "missable" -> 1; "progression" -> 2; "win_condition" -> 3; else -> 0 }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun post(body: String, agent: String): JSONObject? = try {
        val c = URL(URL_DOREQUEST).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", agent)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        try {
            val status = c.responseCode
            val bytes = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() }
            if (status != 200) { JLog.w(TAG, "library achievements: ${body.substringBefore('&')} answered $status"); null }
            else bytes?.let { JSONObject(String(it)) }
        } finally { c.disconnect() }
    } catch (e: Exception) {
        JLog.w(TAG, "library achievements: ${body.substringBefore('&')} failed: ${e.javaClass.simpleName}")
        null
    }
}
