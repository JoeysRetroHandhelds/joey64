package com.joeysretro.joey64.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaListsTest {
    private fun ach(id: Int, unlocked: Int = 0, progress: String = "", percent: Float = 0f, points: Int = 5, rarity: Float = 0f,
                    time: Long = 0, type: Int = 0, section: Int = RaAchievement.LOCKED) =
        RaAchievement(id, "A$id", "d", points, "", "", progress, percent, rarity, time, unlocked, type, section)

    private fun game(id: Long, n: Int, got: Int, beaten: Long = 0, played: Long = 0, title: String = "G$id") =
        RaGame(id, id.toInt(), title, "", n, got, 100, got * 2, beaten, 0, played)

    @Test fun gameFiltersAsTheMockupCounts() {
        val gs = listOf(game(1, 40, 18, beaten = 5), game(2, 79, 31), game(3, 36, 36, beaten = 5), game(4, 90, 0))
        val c = RaLists.counts(gs)
        assertEquals(4, c[GameFilter.All]); assertEquals(2, c[GameFilter.InProgress]); assertEquals(2, c[GameFilter.Beaten])
        assertEquals(1, c[GameFilter.Mastered]); assertEquals(1, c[GameFilter.NotStarted])
    }

    @Test fun gameSorts() {
        val gs = listOf(game(1, 10, 1, played = 5, title = "b"), game(2, 10, 9, played = 9, title = "c"), game(3, 10, 5, played = 1, title = "a"))
        assertEquals(listOf(2L, 1L, 3L), RaLists.games(gs, GameFilter.All, GameSort.RecentlyPlayed).map { it.gameId })
        assertEquals(listOf(3L, 1L, 2L), RaLists.games(gs, GameFilter.All, GameSort.Name).map { it.gameId })
        assertEquals(listOf(2L, 3L, 1L), RaLists.games(gs, GameFilter.All, GameSort.MostComplete).map { it.gameId })
    }

    @Test fun cachedOrderPutsAlmostThereFirstAndUnlockedLast() {
        val list = listOf(
            ach(1, section = RaAchievement.ACTIVE_CHALLENGE),
            ach(2, unlocked = 1, time = 100, section = RaAchievement.RECENTLY_UNLOCKED),
            ach(3, progress = "14/16", percent = 87.5f, section = RaAchievement.ALMOST_THERE),
            ach(4),
            ach(5, unlocked = 1, time = 200, section = RaAchievement.UNLOCKED),
        )
        assertEquals(listOf(3, 1, 4, 5, 2), RaLists.order(list, live = false).map { it.id })
        assertEquals(list, RaLists.order(list, live = true))
    }

    @Test fun achievementFiltersAndSorts() {
        val list = listOf(ach(1, unlocked = 1, time = 50, points = 10, rarity = 8.4f), ach(2, type = RaAchievement.TYPE_MISSABLE, points = 25, rarity = 2.9f),
            ach(3, progress = "3/16", percent = 18f, points = 5, rarity = 1.2f), ach(4, progress = "62/70", percent = 88f))
        val c = RaLists.achCounts(list)
        assertEquals(1, c[AchFilter.Unlocked]); assertEquals(3, c[AchFilter.Locked]); assertEquals(1, c[AchFilter.Missable]); assertEquals(1, c[AchFilter.AlmostThere])
        assertEquals(listOf(2, 1, 3, 4), RaLists.achievements(list, AchFilter.All, AchSort.MostPoints, true).map { it.id })
        assertEquals(1, RaLists.achievements(list, AchFilter.All, AchSort.RecentlyUnlocked, true).first().id)
        assertEquals(listOf(3, 2, 1, 4), RaLists.achievements(list, AchFilter.All, AchSort.Rarest, true).map { it.id })
    }

    @Test fun words() {
        assertEquals("18 of 40 unlocked · 36 points", RaLists.gameLine(game(1, 40, 18)))
        assertEquals("All 36 unlocked · 100 points", RaLists.gameLine(game(1, 36, 36)))
        assertEquals("18 of 40 unlocked · 36 of 100 points", RaLists.gameSummary(game(1, 40, 18), AchFilter.All, 40))
        assertEquals("Locked · 22 of 40", RaLists.gameSummary(game(1, 40, 18), AchFilter.Locked, 22))
        assertEquals("JoeyRetro · 2,184 points", RaLists.pageSummary("JoeyRetro", 2184, GameFilter.All, 7, 7))
        assertEquals("In progress · 5 of 7", RaLists.pageSummary("JoeyRetro", 2184, GameFilter.InProgress, 5, 7))
        assertEquals("8.4% of players", RaLists.rarity(ach(1, rarity = 8.4f)))
        assertNull(RaLists.rarity(ach(1)))
        assertEquals("3 / 16", RaLists.progress(ach(1, progress = "3/16")))
        assertEquals("Just now", RaLists.unlockDate(ach(1, unlocked = 1, time = 1_000), 1_000_000 + 20_000))
        assertEquals("Softcore", RaLists.mode(ach(1, unlocked = 1)))
        assertEquals("Hardcore", RaLists.mode(ach(1, unlocked = 3)))
    }

    @Test fun parsesNativeJson() {
        val a = RaAchievement.parseList("""[{"id":7,"title":"Lap Record","description":"Set it","points":10,"badge":"b","badgeLocked":"bl","progress":"14/16","percent":87.5,"rarity":3.1,"rarityHardcore":1,"unlockTime":0,"state":1,"category":3,"bucket":7,"unlocked":0,"type":1,"section":"Almost There","sectionType":7}]""")
        assertEquals(1, a.size)
        assertEquals(RaAchievement.ALMOST_THERE, a[0].section)
        assertEquals(true, a[0].missable)
        assertEquals(true, a[0].almostThere)
        assertEquals(emptyList<RaAchievement>(), RaAchievement.parseList("not json"))
    }

    // Set tabs (approved mockup 2026-11-08, subsets_mock.png).
    @Test fun setsParseOnlyWhenThereIsMoreThanOne() {
        val two = org.json.JSONObject("""{"id":10003,"achievements":115,"sets":[{"id":4189,"title":"Super Mario 64","achievements":115,"unlocked":13,"points":1000,"pointsUnlocked":130},{"id":4782,"title":"Super Mario 64 [Subset - Bonus]","achievements":48,"unlocked":1,"points":240,"pointsUnlocked":1}]}""")
        val g = RaGame.parse(1, two, 0)
        assertEquals(listOf(4189, 4782), g.sets.map { it.id })
        assertEquals("Main set", RaLists.setLabel(0, g.sets[0]))
        assertEquals("Bonus", RaLists.setLabel(1, g.sets[1]))
        assertEquals("Bonus · 1 of 48 unlocked · 1 of 240 points", RaLists.setSummary("Bonus", g.sets[1], AchFilter.All, 48))
        assertEquals("Bonus · Locked · 47 of 48", RaLists.setSummary("Bonus", g.sets[1], AchFilter.Locked, 47))
        val one = org.json.JSONObject("""{"id":1,"sets":[{"id":7,"title":"x","achievements":3}]}""")
        assertEquals(emptyList<RaSet>(), RaGame.parse(1, one, 0).sets)
        assertEquals("Bonus", RaLists.setLabel(1, RaSet(1, "Bonus", 1, 0, 1, 0)))
    }

    @Test fun gameLineCountsTheOtherSets() {
        val s = RaSet(1, "a", 10, 0, 10, 0)
        assertEquals("1 of 40 unlocked · 2 points · + 2 sets", RaLists.gameLine(game(1, 40, 1).copy(sets = listOf(s, s, s))))
        assertEquals("1 of 40 unlocked · 2 points · + 1 set", RaLists.gameLine(game(1, 40, 1).copy(sets = listOf(s, s))))
        assertEquals("1 of 40 unlocked · 2 points", RaLists.gameLine(game(1, 40, 1)))
    }

    @Test fun achievementsKeepTheirSet() {
        val a = RaAchievement.parseList("""[{"id":78008,"set":4782},{"id":1}]""")
        assertEquals(listOf(4782, 0), a.map { it.set })
        assertEquals(listOf(78008), RaLists.inSet(a, 4782).map { it.id })
        assertEquals(2, RaLists.inSet(a, null).size)
    }

    @Test fun unofficialOnesFollowTheSetting() {
        val list = listOf(ach(1), ach(2, section = RaAchievement.UNOFFICIAL), ach(3, unlocked = 1, section = RaAchievement.UNLOCKED))
        assertEquals(listOf(1, 3), RaAchievement.forSetting(list, unofficial = false).map { it.id })
        assertEquals(list, RaAchievement.forSetting(list, unofficial = true))
    }

    @Test fun theUnknownEmulatorWarningIsntCounted() {
        val list = listOf(ach(RaAchievement.WARNING_ID, unlocked = 1, section = RaAchievement.UNLOCKED), ach(1, unlocked = 1, section = RaAchievement.UNLOCKED), ach(2))
        val c = RaLists.achCounts(list)
        assertEquals(2, c[AchFilter.All]); assertEquals(1, c[AchFilter.Unlocked])
        assertEquals(2, RaLists.counted(list))
    }
}
