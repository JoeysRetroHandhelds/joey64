package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.data.LibraryGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Library's Show and Covers rows (approved mockup 2026-11-08). */
class LibraryArrangeTest {
    private fun g(id: Long, title: String, region: Char? = 'E', code: String? = null, rev: Int? = 0, listed: Boolean = true,
                  fav: Boolean = false, last: Long? = null, secs: Long = 0, added: Long = 0) =
        LibraryGame(id, title, (if (listed) "l" else "h") + id.toString().padStart(31, '0'), headerId = code, region = region, revision = rev,
            favourite = fav, lastPlayedAt = last, playSeconds = secs, addedAt = added)

    private val conkerU = g(1, "Conker's Bad Fur Day (USA)", 'E', "NFUE")
    private val conkerE = g(2, "Conker's Bad Fur Day (Europe)", 'P', "NFUP")
    private val ootU0 = g(3, "Legend of Zelda, The - Ocarina of Time (USA)", 'E', "CZLE", rev = 0)
    private val ootU2 = g(4, "Legend of Zelda, The - Ocarina of Time (USA) (Rev 2)", 'E', "CZLE", rev = 2)
    private val ootJ = g(5, "Zelda no Densetsu - Toki no Ocarina (Japan)", 'J', "CZLJ")
    private val ootBeta = g(6, "Legend of Zelda, The - Ocarina of Time (USA) (Beta)", 'E', "CZLE", rev = 3)
    private val hack = g(7, "Ocarina of Time Master Quest Hack", 'E', "CZLE", listed = false)
    private val disk = g(8, "SimCity 64 (Japan)", null, null)
    private val all = listOf(conkerU, conkerE, ootU0, ootU2, ootJ, ootBeta, hack, disk)
    private val listed = all.filter { it.md5.startsWith("l") }.map { it.md5 }.toSet()
    private val every = RegionGroup.entries.toSet()

    private fun ids(l: List<LibraryGame>) = l.map { it.id }

    @Test fun regionsComeFromTheHeaderCode() {
        assertEquals(setOf(RegionGroup.USA), LibraryArrange.regionsOf('E'))
        assertEquals(setOf(RegionGroup.USA, RegionGroup.Japan), LibraryArrange.regionsOf('A'))
        assertEquals(setOf(RegionGroup.Europe), LibraryArrange.regionsOf('U'))
        assertEquals(setOf(RegionGroup.Other), LibraryArrange.regionsOf('B'))
        assertEquals(setOf(RegionGroup.Other), LibraryArrange.regionsOf(null))
    }

    @Test fun regionsLabel() {
        assertEquals("All regions", LibraryArrange.regionsLabel(every))
        assertEquals("USA", LibraryArrange.regionsLabel(setOf(RegionGroup.USA)))
        assertEquals("USA, Japan", LibraryArrange.regionsLabel(setOf(RegionGroup.Japan, RegionGroup.USA)))
    }

    @Test fun allRegionsWithoutOneVersionShowsEverything() {
        assertEquals(ids(all), ids(LibraryArrange.visible(all, every, false, listed)))
    }

    @Test fun onlyTickedRegionsShow() {
        assertEquals(listOf(2L), ids(LibraryArrange.visible(all, setOf(RegionGroup.Europe), false, listed)))
        assertEquals(listOf(5L, 8L), ids(LibraryArrange.visible(all, setOf(RegionGroup.Japan, RegionGroup.Other), false, listed)))
    }

    @Test fun oneVersionKeepsTheFirstTickedRegionsNewestRevision() {
        // USA first: Conker (USA), OoT Rev 2 (not the beta); the hack and the disk always show.
        assertEquals(listOf(1L, 4L, 7L, 8L), ids(LibraryArrange.visible(all, every, true, listed)))
        // Without USA: Conker (Europe), OoT (Japan).
        assertEquals(listOf(2L, 5L, 8L), ids(LibraryArrange.visible(all, setOf(RegionGroup.Europe, RegionGroup.Japan, RegionGroup.Other), true, listed)))
    }

    @Test fun oneVersionWithoutNoIntroFactsGroupsNothing() {
        assertEquals(ids(all), ids(LibraryArrange.visible(all, every, true, emptySet())))
    }

    @Test fun sortOrders() {
        val a = g(1, "banjo", last = 10, secs = 50, added = 3)
        val b = g(2, "Aero Gauge", last = null, secs = 500, added = 1, fav = true)
        val c = g(3, "Conker", last = 20, secs = 5, added = 2)
        val l = listOf(a, b, c)
        assertEquals(listOf(2L, 1L, 3L), ids(LibraryArrange.sort(l, SortBy.Name, false)))
        assertEquals(listOf(3L, 1L, 2L), ids(LibraryArrange.sort(l, SortBy.RecentlyPlayed, false)))
        assertEquals(listOf(2L, 1L, 3L), ids(LibraryArrange.sort(l, SortBy.MostPlayed, false)))
        assertEquals(listOf(1L, 3L, 2L), ids(LibraryArrange.sort(l, SortBy.RecentlyAdded, false)))
        assertEquals(listOf(2L, 3L, 1L), ids(LibraryArrange.sort(l, SortBy.RecentlyPlayed, true)))
    }

    @Test fun shortNamesDropTheTags() {
        assertEquals("Conker's Bad Fur Day", LibraryArrange.shortTitle("Conker's Bad Fur Day (Europe)"))
        assertEquals("Sin and Punishment", LibraryArrange.shortTitle("Sin and Punishment [T+Eng] (Japan)"))
        assertEquals("Super Mario 64", LibraryArrange.shortTitle("Super Mario 64"))
        assertEquals("(Homebrew)", LibraryArrange.shortTitle("(Homebrew)"))
    }

    @Test fun coverSizeColumnsNeverUnderTwo() {
        assertEquals(5, LibraryArrange.columns(4, CoverSize.Small))
        assertEquals(4, LibraryArrange.columns(4, CoverSize.Medium))
        assertEquals(3, LibraryArrange.columns(4, CoverSize.Large))
        assertEquals(2, LibraryArrange.columns(2, CoverSize.Large))
    }

    @Test fun lookForNewGames() {
        assertTrue(LibraryArrange.lookNow(LookFor.Start, 100, 100))
        assertTrue(LibraryArrange.lookNow(LookFor.Daily, 99, 100))
        assertTrue(LibraryArrange.lookNow(LookFor.Daily, null, 100))
        assertFalse(LibraryArrange.lookNow(LookFor.Daily, 100, 100))
        assertFalse(LibraryArrange.lookNow(LookFor.Asked, null, 100))
    }
}
