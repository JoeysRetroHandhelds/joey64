package com.joeysretro.joey64.achievements

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The look-up's answer lands in the progress cache's shapes (what RaLists reads). */
class RaLibraryFetchTest {
    private val answer = JSONObject("""
        {"Success":true,"GameId":10,"Title":"Mario Kart 64","ImageIconUrl":"https://x/icon.png","Sets":[
          {"AchievementSetId":1,"GameId":10,"Title":"Mario Kart 64","Type":"core","Achievements":[
            {"ID":100,"Title":"A","Description":"d","Flags":3,"Points":5,"BadgeName":"111","Type":"missable"},
            {"ID":101,"Title":"B","Description":"d","Flags":3,"Points":10,"BadgeName":"112","BadgeURL":"https://x/b.png"},
            {"ID":102,"Title":"U","Description":"d","Flags":5,"Points":1,"BadgeName":"113"}]},
          {"AchievementSetId":2,"GameId":20,"Title":"Time Trials","Type":"bonus","Achievements":[
            {"ID":200,"Title":"T","Description":"d","Flags":3,"Points":25,"BadgeName":"114"}]}]}
    """)

    @Test fun countsAndSets() {
        val (game, listJson) = RaLibraryFetch.build("ABC", answer, setOf(101, 200))!!
        assertEquals(10, game.getInt("id"))
        assertEquals("abc", game.getString("hash"))
        assertEquals(3, game.getInt("achievements"))
        assertEquals(2, game.getInt("unlocked"))
        assertEquals(40, game.getInt("points"))
        assertEquals(35, game.getInt("pointsUnlocked"))
        assertTrue(game.getBoolean("fetched"))
        val g = RaGame.parse(1, game, 0)
        assertEquals(2, g.sets.size)
        assertEquals("Time Trials", g.sets[1].title)
        assertEquals(1, g.sets[1].unlocked)

        val list = RaAchievement.parseList(listJson)
        assertEquals(4, list.size)
        val a = list.first { it.id == 100 }
        assertFalse(a.isUnlocked); assertTrue(a.missable)
        assertEquals("https://media.retroachievements.org/Badge/111_lock.png", a.badgeLocked)
        val b = list.first { it.id == 101 }
        assertTrue(b.isUnlocked); assertEquals("https://x/b.png", b.badge); assertEquals(RaAchievement.UNLOCKED, b.section)
        assertEquals(RaAchievement.UNOFFICIAL, list.first { it.id == 102 }.section)
        assertEquals(20, list.first { it.id == 200 }.set)
    }

    @Test fun oneSetHasNoSetsList() {
        val one = JSONObject(answer.toString()).apply { put("Sets", getJSONArray("Sets").apply { remove(1) }) }
        val (game, _) = RaLibraryFetch.build("abc", one, emptySet())!!
        assertFalse(game.has("sets"))
        assertEquals(0, game.getInt("unlocked"))
    }
}
