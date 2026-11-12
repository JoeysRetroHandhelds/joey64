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

    // The rule measured on the Odin 3's 1,231-game library (approved Versions mockup, 2026-11-11).
    private fun arrange(vararg gs: LibraryGame, regions: Set<RegionGroup> = every, betas: BetaMode = BetaMode.WithGame) =
        LibraryArrange.arrange(gs.toList(), regions, true, gs.filter { it.md5.startsWith("l") }.map { it.md5 }.toSet(), betas)

    @Test fun twoNamesUnderOneCodeForOneDestinationAreTwoGames() {
        // Ocarina of Time and its Master Quest share CZL; homebrew took NED from Power Struggle.
        val mq = g(10, "Legend of Zelda, The - Ocarina of Time - Master Quest (USA) (Debug) (GameCube)", 'E', "CZLE")
        val ps = g(11, "Power Struggle (USA)", 'E', "NEDE")
        val styx = g(12, "Styx (World) (Aftermarket) (Unl)", 'E', "NEDE")
        val slime = g(13, "Slime Heist (World)", 'E', "NEDE")
        assertEquals(listOf(4L, 10L, 11L, 12L, 13L), ids(arrange(ootU2, mq, ps, styx, slime).shown))
    }

    @Test fun aNameLikeAGameThatHasItsDestinationIsAGameOfItsOwn() {
        // Toki no Ocarina GC is like Ocarina of Time's Japanese name, which has a Japanese copy: it doesn't join
        // Master Quest just because Master Quest had none. (GC Ura is Master Quest's, but nothing in its name says so.)
        val mq = g(70, "Legend of Zelda, The - Ocarina of Time - Master Quest (USA) (GameCube)", 'E', "CZLE", rev = 15)
        val gc = g(71, "Zelda no Densetsu - Toki no Ocarina GC (Japan) (GameCube)", 'J', "CZLJ", rev = 15)
        val ura = g(72, "Zelda no Densetsu - Toki no Ocarina GC Ura (Japan) (GameCube)", 'J', "CZLJ", rev = 15)
        assertEquals(listOf(4L, 70L, 71L, 72L), ids(arrange(ootU2, ootJ, mq, gc, ura).shown))
    }

    @Test fun regionalNamesAndTheSameNameUnderTwoCodesAreOneGame() {
        val sf = g(20, "Star Fox 64 (USA)", 'E', "NFXE")
        val lylat = g(21, "Lylat Wars (Europe) (En,Fr,De)", 'P', "NFXP")
        val ootE = g(22, "Legend of Zelda, The - Ocarina of Time (Europe) (En,Fr,De)", 'P', "NZLP")
        val a = arrange(sf, lylat, ootU2, ootJ, ootE)
        assertEquals(listOf(20L, 4L), ids(a.shown))
        assertEquals(listOf(4L, 22L, 5L), ids(a.versions.getValue(5L).copies))
        assertEquals(listOf(20L, 21L), ids(a.versions.getValue(21L).copies))
    }

    @Test fun betasGoWithTheGameOfTheirNameNotTheirCode() {
        // 007's v2 beta carries Mortal Kombat 4's code.
        val bond = g(30, "007 - The World Is Not Enough (USA)", 'E', "NO7E")
        val beta = g(31, "007 - The World Is Not Enough (USA) (v2) (Beta)", 'E', "NM4E", listed = false)
        val mk4 = g(32, "Mortal Kombat 4 (USA)", 'E', "NM4E")
        assertEquals(listOf(30L, 32L), ids(arrange(bond, beta, mk4).shown))
        assertEquals(listOf(30L, 31L), ids(arrange(bond, beta, mk4).versions.getValue(30L).copies))
        assertEquals(listOf(30L, 31L, 32L), ids(arrange(bond, beta, mk4, betas = BetaMode.OwnTile).shown))
        assertEquals(listOf(30L, 32L), ids(arrange(bond, beta, mk4, betas = BetaMode.Hidden).shown))
        assertTrue(arrange(bond, beta, mk4, betas = BetaMode.Hidden).versions.isEmpty())
    }

    @Test fun aGameNoOneReleasedKeepsItsOwnTile() {
        // Glover 2 has Glover's code; there's no Glover 2 release, so its prototype is a tile of its own.
        val glover = g(40, "Glover (USA)", 'E', "NGVE")
        val gloverUnl = g(41, "Glover (USA) (Aftermarket) (Unl)", 'E', "NGVE", rev = 1)
        val glover2 = g(42, "Glover 2 (USA) (Proto)", 'E', "NGVE")
        val a = arrange(glover, gloverUnl, glover2)
        assertEquals(listOf(40L, 42L), ids(a.shown))
        // The cartridge before the aftermarket copy, newer revision or not.
        assertEquals(listOf(40L, 41L), ids(a.versions.getValue(41L).copies))
    }

    @Test fun theCartBeforeAPortAndTheCopyPlayedOrPickedLast() {
        val gc = g(50, "Legend of Zelda, The - Ocarina of Time (USA) (GameCube)", 'E', "CZLE", rev = 15)
        assertEquals(listOf(4L), ids(arrange(ootU0, ootU2, gc).shown))
        assertEquals(listOf(4L, 3L, 50L), ids(arrange(ootU0, ootU2, gc).versions.getValue(4L).copies))
        val played = gc.copy(lastPlayedAt = 100)
        assertEquals(listOf(50L), ids(arrange(ootU0, ootU2, played).shown))
        val picked = ootU0.copy(pickedAt = 200)
        assertEquals(listOf(3L), ids(arrange(picked, ootU2, played).shown))
        // The Versions order doesn't move with the pick.
        assertEquals(listOf(4L, 3L, 50L), ids(arrange(picked, ootU2, played).versions.getValue(3L).copies))
        assertEquals(3L, arrange(picked, ootU2, played).versions.getValue(50L).plays.id)
    }

    @Test fun versionTagsCountBeforeTheHeaderRevision() {
        val v10 = g(60, "Homebrew Game (World) (v1.0) (Aftermarket) (Unl)", 'E', "NHBE", rev = 3)
        val v11 = g(61, "Homebrew Game (World) (v1.1) (Aftermarket) (Unl)", 'E', "NHBE", rev = 0)
        assertEquals(listOf(61L), ids(arrange(v10, v11).shown))
    }

    @Test fun withoutOneVersionNothingFolds() {
        val a = LibraryArrange.arrange(all, every, false, listed)
        assertEquals(ids(all), ids(a.shown))
        assertTrue(a.versions.isEmpty())
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

    @Test fun tidyNamesPutTheArticleInFront() {
        assertEquals("The Legend of Zelda - Ocarina of Time (USA) (Rev 2)", LibraryArrange.tidyTitle(ootU2.title))
        assertEquals("The Legend of Zelda - Ocarina of Time", LibraryArrange.title(ootU0, NameStyle.Short, tidy = true))
        assertEquals("Legend of Zelda, The - Ocarina of Time", LibraryArrange.title(ootU0, NameStyle.Short))
        assertEquals("The New Tetris (USA)", LibraryArrange.tidyTitle("New Tetris, The (USA)"))
        assertEquals("The World Is Not Enough", LibraryArrange.tidyTitle("World Is Not Enough, The"))
        assertEquals("Bomberman 64 - The Second Attack! (USA)", LibraryArrange.tidyTitle("Bomberman 64 - The Second Attack! (USA)"))
        assertEquals("Conker's Bad Fur Day (Europe)", LibraryArrange.tidyTitle(conkerE.title))
        // Only the name before the tags: a tag with a comma stays as it is.
        assertEquals("Wipeout 64 (USA, Europe)", LibraryArrange.tidyTitle("Wipeout 64 (USA, Europe)"))
        assertEquals("A Bug's Life [hack]", LibraryArrange.tidyTitle("Bug's Life, A [hack]"))
    }
}
