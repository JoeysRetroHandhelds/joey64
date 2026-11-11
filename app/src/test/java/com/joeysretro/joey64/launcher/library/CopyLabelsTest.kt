package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.data.LibraryGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The line under look-alike copies (approved mockup A, 2026-11-11). Names from the Odin 3's 1,231-game test library. */
class CopyLabelsTest {
    private fun g(id: Long, title: String, region: Char? = 'E') = LibraryGame(id, title, id.toString().padStart(32, '0'), region = region)

    @Test fun theThree007Tiles() {
        val games = listOf(
            g(1, "007 - The World Is Not Enough (USA)"),
            g(2, "007 - The World Is Not Enough (USA) (v2) (Beta)"),
            g(3, "007 - The World Is Not Enough (USA) (v21) (Beta)"),
            g(4, "1080 Snowboarding (Japan, USA) (En,Ja)", 'A'),
        )
        assertEquals(mapOf(1L to "USA", 2L to "USA · v2 · Beta", 3L to "USA · v21 · Beta"), CopyLabels.of(games))
    }

    @Test fun aGameWithoutACopyGetsNoLine() {
        assertEquals(emptyMap<Long, String>(), CopyLabels.of(listOf(g(1, "Super Mario 64 (USA)"), g(2, "Mario Kart 64 (USA)"))))
        assertEquals(emptyMap<Long, String>(), CopyLabels.of(emptyList()))
    }

    @Test fun searchForMarioGolf() {
        val found = listOf(
            g(1, "Mario Golf (Europe)", 'P'),
            g(2, "Mario Golf (USA)"),
            g(3, "Mario Golf (USA) (LodgeNet)", 'G'),
            g(4, "Mario Golf 64 (Japan)", 'J'),
            g(5, "Mario Golf 64 (Japan) (Rev 1)", 'J'),
        )
        assertEquals(mapOf(1L to "Europe", 2L to "USA", 3L to "USA · LodgeNet", 4L to "Japan", 5L to "Japan · Rev 1"), CopyLabels.of(found))
    }

    @Test fun copiesAreFoundWhateverTheCase() {
        assertEquals(setOf(1L, 2L), CopyLabels.of(listOf(g(1, "WCW Nitro (USA)"), g(2, "WCW NITRO (Europe)", 'P'))).keys)
    }

    @Test fun tagsEveryCopyHasAreLeftOut() {
        // Proto is on both, so the date and the region tell them apart.
        assertEquals(listOf("Europe", "USA · 1999-08-25"),
            CopyLabels.lines(listOf("Dragon Sword 64 (Europe) (Proto)" to 'P', "Dragon Sword 64 (USA) (Proto) (1999-08-25)" to 'E')))
        assertEquals(listOf("World · NTSC", "World · PAL"), CopyLabels.lines(listOf(
            "Big Burger (World) (N64brew Game Jam 2020) (NTSC) (Aftermarket) (Unl)" to null,
            "Big Burger (World) (N64brew Game Jam 2020) (PAL) (Aftermarket) (Unl)" to null)))
    }

    @Test fun languagesOnlyWhenNothingElseDiffers() {
        assertEquals(listOf("Europe", "USA"), CopyLabels.lines(listOf("AeroFighters Assault (Europe) (En,Fr,De)" to 'P', "AeroFighters Assault (USA)" to 'E')))
        assertEquals(listOf("Europe · En,Fr", "Europe · En,De"),
            CopyLabels.lines(listOf("Some Game (Europe) (En,Fr)" to 'P', "Some Game (Europe) (En,De)" to 'P')))
    }

    @Test fun aNameWithoutARegionTagTakesTheHeaders() {
        // Hacks and file names: the region comes from the header's destination code.
        assertEquals(listOf("USA", "Japan"), CopyLabels.lines(listOf("Star Road [Hack v1]" to 'E', "Star Road [Hack v1]" to 'J')))
        assertEquals(listOf("USA · v1", "USA · v2"), CopyLabels.lines(listOf("Star Road (v1)" to 'E', "Star Road (v2)" to 'E')))
        assertEquals(listOf("", ""), CopyLabels.lines(listOf("Mystery Game" to null, "Mystery Game" to null)))
    }

    @Test fun squareTags() {
        assertEquals(listOf("Japan", "Japan · Beta · Bad dump"),
            CopyLabels.lines(listOf("Derby Stallion 64 (Japan)" to 'J', "Derby Stallion 64 (Japan) (Beta) [b]" to 'J')))
        assertEquals(listOf("USA", "USA · T+Spa"), CopyLabels.lines(listOf("Some Game (USA) [!]" to 'E', "Some Game (USA) [T+Spa]" to 'E')))
    }

    @Test fun twoCopiesThatStillLookAlikeGetNoLine() {
        // Same name and tags, different dumps: nothing in the name tells them apart, so no line rather than the same one.
        val labels = CopyLabels.of(listOf(g(1, "Mystery Game", null), g(2, "Mystery Game", null)))
        assertTrue(labels.isEmpty())
    }
}
