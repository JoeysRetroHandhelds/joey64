package com.joeysretro.joey64.achievements

import com.joeysretro.joey64.launcher.ingame.InGamePopup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaPopupsTest {
    private val ach = JSONObject("""{"id":101,"title":"Time Trialist","description":"Win every cup","points":10,"progress":"4/16","badge":"https://media.retroachievements.org/Badge/12345.png","badgeLocked":"https://media.retroachievements.org/Badge/12345_lock.png"}""")
    private val game = JSONObject("""{"id":7,"title":"Mario Tennis","badge":"https://media.retroachievements.org/Images/000123.png","achievements":40,"unlocked":19,"points":400,"pointsUnlocked":164,"beaten":0,"completed":0}""")

    @Test fun unlockAsApproved() {
        val p = RaPopupText.unlock(ach, game, offline = false, badge = null)
        assertEquals("Achievement unlocked · *10 points* · 19 of 40", p.label)
        assertEquals("Time Trialist", p.name)
        assertEquals("Win every cup", p.line)
        assertNull(p.extra)
        assertEquals(InGamePopup.Kind.Achievement, p.kind)
    }

    @Test fun onePointAndOffline() {
        ach.put("points", 1)
        val p = RaPopupText.unlock(ach, null, offline = true, badge = null)
        assertEquals("Achievement unlocked · *1 point*", p.label)
        assertEquals("Offline · sends when you're back online", p.extra)
    }

    @Test fun progressAndFailedAreCompact() {
        val pr = RaPopupText.progress(ach, null)
        assertEquals("progress", pr.key)
        assertEquals("4/16", pr.name)
        assertTrue(pr.compact)
        val f = RaPopupText.challengeFailed(ach, null)
        assertEquals("*Challenge failed*", f.label)
        assertEquals("Time Trialist", f.name)
        assertTrue(f.compact)
    }

    @Test fun summaryLines() {
        assertEquals("19 of 40 achievements · 164 of 400 points", RaPopupText.summary(game, null).line)
        game.put("unlocked", 40)
        assertEquals("All 40 achievements · 400 points", RaPopupText.summary(game, null).line)
        game.put("achievements", 0)
        assertEquals("No achievements for this game yet", RaPopupText.summary(game, null).line)
    }

    @Test fun beatenAndMasteredAreFramed() {
        game.put("unlocked", 26)
        val b = RaPopupText.beaten(game, null)
        assertEquals("*Beaten* · Softcore", b.label)
        assertEquals("Finished the game · 26 of 40 achievements", b.line)
        assertTrue(b.framed)
        val m = RaPopupText.mastered(game, null)
        assertEquals("All 40 achievements · 400 points", m.line)
        assertTrue(m.framed)
    }

    @Test fun badgeUrlsBothPicturesOnce() {
        val list = JSONArray().put(ach).put(ach).put(JSONObject("""{"section":"Locked"}"""))
        assertEquals(listOf(ach.getString("badge"), ach.getString("badgeLocked")), RaPopupText.badgeUrls(list))
    }

    @Test fun badgeFileNames() {
        assertEquals("Badge_12345.png", RaBadges.fileName("https://media.retroachievements.org/Badge/12345.png"))
        assertEquals("Images_000123.png", RaBadges.fileName("https://media.retroachievements.org/Images/000123.png?x=1"))
        assertNull(RaBadges.fileName("file:///etc/passwd"))
        assertNull(RaBadges.fileName("https://media.retroachievements.org/"))
    }
}
