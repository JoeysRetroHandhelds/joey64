package com.joeysretro.joey64.achievements

import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug builds only: made-up sets for the Achievements pages, held in memory while the made-up
 * account is on (RA_FAKE), so the pages can be checked without signing in. Nothing is written to
 * the library. Game ids are negative, so they never match a library game.
 */
internal object RaFake {
    private val now = System.currentTimeMillis() / 1000

    private fun game(id: Int, title: String, n: Int, got: Int, points: Int, pointsGot: Int, beaten: Boolean) = JSONObject()
        .put("id", id).put("title", title).put("hash", "").put("badge", "")
        .put("achievements", n).put("unlocked", got).put("points", points).put("pointsUnlocked", pointsGot)
        .put("beaten", if (beaten) now - 86_400 else 0).put("completed", if (got == n) now - 86_400 else 0)

    private fun ach(id: Int, title: String, desc: String, points: Int, unlockedAgo: Long? = null, progress: String = "", percent: Double = 0.0,
                    rarity: Double, missable: Boolean = false, hard: Boolean = false, section: Int = RaAchievement.LOCKED) = JSONObject()
        .put("id", id).put("title", title).put("description", desc).put("points", points).put("badge", "").put("badgeLocked", "")
        .put("progress", progress).put("percent", percent).put("rarity", rarity)
        .put("unlockTime", unlockedAgo?.let { now - it } ?: 0).put("unlocked", if (unlockedAgo == null) 0 else if (hard) 3 else 1)
        .put("type", if (missable) RaAchievement.TYPE_MISSABLE else 0).put("sectionType", section)

    private fun mk64() = JSONArray()
        .put(ach(1, "Lap Record", "Set the record on 16 tracks", 10, progress = "14/16", percent = 87.5, rarity = 3.1, section = RaAchievement.ALMOST_THERE))
        .put(ach(2, "First Try", "Win your first 150cc cup without restarting", 25, rarity = 22.4, missable = true))
        .put(ach(3, "Time Trialist", "Beat every staff ghost", 25, progress = "3/16", percent = 18.75, rarity = 1.2))
        .put(ach(4, "Untouchable", "Finish the Special Cup without being hit", 50, rarity = 4.5, missable = true))
        .put(ach(5, "Battle Royale", "Win a battle with all three balloons left", 10, rarity = 11.9))
        .put(ach(6, "Pole Position", "Win any race in first place", 5, unlockedAgo = 86_400, rarity = 64.0, section = RaAchievement.UNLOCKED))
        .put(ach(7, "Mirror Master", "Win every cup in mirror mode", 25, unlockedAgo = 30L * 86_400, rarity = 6.8, section = RaAchievement.UNLOCKED))
        .put(ach(8, "Shortcut Scholar", "Use every shortcut on one track", 10, unlockedAgo = 8L * 86_400, rarity = 15.0, hard = true, section = RaAchievement.UNLOCKED))

    fun cache(): Map<Long, Pair<String, String>> = linkedMapOf(
        -1L to (game(1001, "Mario Kart 64", 8, 3, 160, 40, beaten = true).toString() to mk64().toString()),
        -2L to (game(1002, "Super Mario 64", 79, 31, 650, 310, beaten = false).toString() to "[]"),
        -3L to (game(1003, "Kirby 64: The Crystal Shards", 36, 36, 400, 400, beaten = true).toString() to "[]"),
        -4L to (game(1004, "GoldenEye 007", 52, 0, 400, 0, beaten = false).toString() to "[]"),
    )
}
