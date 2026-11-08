package com.joeysretro.joey64.launcher.boxart

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ScreenScraper answers as box art reads them (shapes from its api2 jeuInfos JSON). */
class ScreenScraperPickTest {
    private fun jeu(name: String, vararg covers: Pair<String, String>) = JSONObject().apply {
        put("id", "5396")
        put("noms", org.json.JSONArray().put(JSONObject().put("region", "ss").put("text", name)))
        put("medias", org.json.JSONArray().apply {
            put(JSONObject().put("type", "ss").put("region", "wor").put("url", "https://x/shot"))
            for ((r, u) in covers) put(JSONObject().put("type", "box-2D").put("region", r).put("url", u))
            put(JSONObject().put("type", "box-2D-back").put("region", "us").put("url", "https://x/back"))
        })
    }

    @Test fun readsTheFrontCoversOnly() {
        val g = ScreenScraperPick.game(jeu("Super Mario 64", "eu" to "https://x/eu", "us" to "https://x/us"))!!
        assertEquals("Super Mario 64", g.name)
        assertEquals(listOf("eu" to "https://x/eu", "us" to "https://x/us"), g.covers)
    }

    @Test fun anyRegionsNameFits() {
        val j = jeu("Lylat Wars").apply { getJSONArray("noms").put(JSONObject().put("region", "us").put("text", "Star Fox 64")) }
        val g = ScreenScraperPick.game(j)!!
        assertTrue(g.fits("Star Fox 64"))
        assertFalse(g.fits("Sealed Palace"))
    }

    @Test fun notAGameHoldersAreNoGame() {
        assertNull(ScreenScraperPick.game(jeu("ZZZ(notgame):SM64")))
        assertNull(ScreenScraperPick.game(null))
    }

    @Test fun picksTheCoverOfTheRomsRegion() {
        val g = ScreenScraperPick.game(jeu("Star Fox 64", "jp" to "j", "eu" to "e", "us" to "u", "wor" to "w"))!!
        assertEquals("e", ScreenScraperPick.cover(g, "Lylat Wars (Europe) (En,Fr,De)")?.second)
        assertEquals("j", ScreenScraperPick.cover(g, "Star Fox 64 (Japan)")?.second)
        assertEquals("u", ScreenScraperPick.cover(g, "Star Fox 64 (USA)")?.second)
        assertEquals("u", ScreenScraperPick.cover(g, "Some Hack")?.second)
        val onlyJp = ScreenScraperPick.game(jeu("Sin and Punishment", "jp" to "j"))!!
        assertEquals("j", ScreenScraperPick.cover(onlyJp, "Sin and Punishment (USA)")?.second)
        assertNull(ScreenScraperPick.cover(ScreenScraperPick.game(jeu("Nothing"))!!, "x"))
        // A European country: its own cover, else Europe's (Fighters Destiny (Germany) got the US one).
        val fd = ScreenScraperPick.game(jeu("Fighters Destiny", "us" to "u", "eu" to "e", "fr" to "f"))!!
        assertEquals("e", ScreenScraperPick.cover(fd, "Fighters Destiny (Germany)")?.second)
        assertEquals("f", ScreenScraperPick.cover(fd, "Fighters Destiny (France)")?.second)
        assertEquals("u", ScreenScraperPick.cover(fd, "Fighters Destiny (USA, Europe)")?.second)
    }

    @Test fun searchTermsDropTags() {
        assertEquals("Shotgun Mario 64", ScreenScraperPick.searchTerm("Shotgun Mario 64 (v1.0.4)[Dan_GPTV]"))
        assertEquals("Super Mario Land 64", ScreenScraperPick.searchTerm("Super Mario Land 64 v1.1 (Super Mario 64 Hack)"))
        assertEquals("Tsumi to Batsu - Hoshi no Keishousha", ScreenScraperPick.searchTerm("Tsumi to Batsu - Hoshi no Keishousha (Japan) (patched)"))
        assertEquals("Mario Kart 64 - Amped Up", ScreenScraperPick.searchTerm("Mario Kart 64 - Amped Up"))
    }

    @Test fun searchResultsMustFit() {
        assertTrue(ScreenScraperPick.fits("Super Mario Star Road Final - Deluxe Edition", "Super Mario 64 - Star Road"))
        assertTrue(ScreenScraperPick.fits("Sealed Palace", "Legend Of Zelda - The Sealed Palace"))
        assertTrue(ScreenScraperPick.fits("Smash Remix", "Super Smash Bros - Smash Remix"))
        assertFalse(ScreenScraperPick.fits("Glover", "Glover 2"))
        assertTrue(ScreenScraperPick.fits("Resident Evil 2", "Resident Evil 2"))
        assertTrue(ScreenScraperPick.fits("Zelda 64 Dawn & Dusk", "Zelda 64: Dawn and Dusk"))
        assertFalse(ScreenScraperPick.fits("Paper Mario", "Mario Party"))
        assertFalse(ScreenScraperPick.fits("Legend of Zelda, The - Sands of Time", "The Legend of Zelda"))
        assertFalse(ScreenScraperPick.fits("Legend of Zelda, The - Sands of Time", "Legend Of Zelda - Ocarina Of Time, The"))
        assertTrue(ScreenScraperPick.fits("Glover", "Glover"))
        assertTrue(ScreenScraperPick.fits("Doom 64", "Doom 64"))
        assertFalse(ScreenScraperPick.fits("x", ""))
    }

    @Test fun fixesParse() {
        val t = '\t'
        val f = BoxArtFixes.parse(listOf("# x", "A".repeat(32) + "${t}108717${t}Star Road", "b".repeat(32) + "${t}none", "c".repeat(32) + "${t}soon", "short${t}1").joinToString("\n"))
        assertEquals(mapOf("a".repeat(32) to BoxArtFixes.Fix.Game("108717"), "b".repeat(32) to BoxArtFixes.Fix.None), f)
    }

    @Test fun fixesTakeARegionAndTheFirstLineWins() {
        val t = '\t'
        val f = BoxArtFixes.parse(listOf("a".repeat(32) + "${t}108717${t}us${t}Star Road", "a".repeat(32) + "${t}5396${t}eu",
            "b".repeat(32) + "${t}5396${t}Not a region: a note").joinToString("\n"))
        assertEquals(BoxArtFixes.Fix.Game("108717", "us"), f["a".repeat(32)])
        assertEquals(BoxArtFixes.Fix.Game("5396", null), f["b".repeat(32)])
    }

    /** Every line of the shipped fixes list is used once: a typo or a second line for an MD5 would be dropped silently. */
    @Test fun shippedFixes() {
        val f = listOf(File("src/main/assets/boxart/fixes.tsv"), File("app/src/main/assets/boxart/fixes.tsv")).first { it.exists() }
        val lines = f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(lines.size, BoxArtFixes.parse(f.readText()).size)
        assertTrue(lines.isNotEmpty())
    }

    /** The table the app ships parses and has every name once per MD5. */
    @Test fun shippedTable() {
        val f = listOf(File("src/main/assets/boxart/n64-names.tsv"), File("app/src/main/assets/boxart/n64-names.tsv")).first { it.exists() }
        val t = BoxArtTable.parse(f.readText())
        assertEquals(true, t.size > 1000)
        assertEquals("Super Mario 64 (USA)", t["20b854b239203baf6c961b850a4a51a2"])
    }
}
