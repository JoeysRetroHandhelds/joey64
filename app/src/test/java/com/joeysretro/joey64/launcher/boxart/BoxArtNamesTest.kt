package com.joeysretro.joey64.launcher.boxart

import com.joeysretro.joey64.launcher.boxart.BoxArtNames.How
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class BoxArtNamesTest {
    // Real names from the 2026-11-06 cover list.
    private val covers = BoxArtNames.Covers(listOf(
        "Super Mario 64 (USA)",
        "Super Mario 64 (Europe) (En,Fr,De)",
        "Donkey Kong 64 (USA)",
        "Rat Attack! (USA) (En,Fr,De,Es,It,Nl)",
        "Frogger 2 (USA) (Proto 1)",
        "Whack 'n' Roll (USA) (Beta) (1998-02-05)",
        "Resident Evil 2 (USA)",
        "Tsumi to Batsu - Hoshi no Keishousha (Japan)",
        "Mischief Makers (USA)",
        "Legend of Zelda, The - Ocarina of Time (USA)",
        "Legend of Zelda, The - Ocarina of Time (USA) (Rev 1)",
        "Mario no Photopi (Japan)",
        "Hey You, Pikachu! (USA)",
        "Pokemon Snap (USA)",
        "Star Wars Episode I - Battle for Naboo (USA)",
    ))

    @Test fun hashNameFirst() {
        assertEquals(BoxArtNames.Match("Super Mario 64 (USA)", How.Hash), BoxArtNames.pick("Super Mario 64 (USA)", "sm64", covers))
    }

    @Test fun fileNameWhenNotInTable() {
        assertEquals(BoxArtNames.Match("Resident Evil 2 (USA)", How.FileName), BoxArtNames.pick(null, "Resident Evil 2 (USA)", covers))
    }

    @Test fun titleFallbackKeepsTheRegion() {
        assertEquals(BoxArtNames.Match("Donkey Kong 64 (USA)", How.Title), BoxArtNames.pick("Donkey Kong 64 (USA) (LodgeNet)", "x", covers))
        assertEquals("Rat Attack! (USA) (En,Fr,De,Es,It,Nl)", BoxArtNames.pick("Rat Attack! (USA)", "x", covers)?.cover)
        assertEquals("Super Mario 64 (Europe) (En,Fr,De)", BoxArtNames.pick("Super Mario 64 (Europe) (En,Fr,De) (Rev 2)", "x", covers)?.cover)
        assertEquals("Legend of Zelda, The - Ocarina of Time (USA)", BoxArtNames.pick("Legend of Zelda, The - Ocarina of Time (USA) (Zelda Collection)", "x", covers)?.cover)
    }

    @Test fun titleFallbackOnAPatchedFileName() {
        assertEquals(BoxArtNames.Match("Tsumi to Batsu - Hoshi no Keishousha (Japan)", How.Title),
            BoxArtNames.pick(null, "Tsumi to Batsu - Hoshi no Keishousha (Japan) (patched)", covers))
    }

    @Test fun protoTakesTheOnlyCover() {
        assertEquals("Frogger 2 (USA) (Proto 1)", BoxArtNames.pick("Frogger 2 (USA) (Proto 3)", "x", covers)?.cover)
    }

    @Test fun hacksGetNone() {
        for (f in listOf("SM64 Last Impact", "Super Mario 64 - Peach's Fury", "Super Mario Star Road Final - Deluxe Edition (Hack by Skelux)", "Smash Remix"))
            assertNull(f, BoxArtNames.pick(null, f, covers))
        assertNull(BoxArtNames.pick(null, "", covers))
    }

    @Test fun unsafeCharactersBecomeUnderscores() {
        assertEquals("Mario Kart 64 _ Test_ A_B", BoxArtNames.fileName("Mario Kart 64 / Test: A&B"))
    }

    @Test fun listingAndUrls() {
        val html = """<tr><td><a href="?C=N;O=D">Name</a></td></tr>
            <tr><td><a href="/Nintendo%20-%20Nintendo%2064/">Parent Directory</a></td></tr>
            <tr><td><a href="Hey%20You,%20Pikachu!%20(USA).png">Hey You, Pikachu! (USA).png</a></td></tr>
            <tr><td><a href="Whack%20'n'%20Roll%20(USA)%20(Beta)%20(1998-02-05).png">x</a></td></tr>"""
        assertEquals(listOf("Hey You, Pikachu! (USA)", "Whack 'n' Roll (USA) (Beta) (1998-02-05)"), BoxArtNames.parseListing(html))
        assertEquals("https://t/x/Hey%20You%2C%20Pikachu%21%20%28USA%29.png", BoxArtNames.url("https://t/x/", "Hey You, Pikachu! (USA)"))
    }

    /** The table the app ships parses and has every name once per MD5. */
    @Test fun shippedTable() {
        val f = listOf(File("src/main/assets/boxart/n64-names.tsv"), File("app/src/main/assets/boxart/n64-names.tsv")).first { it.exists() }
        val t = BoxArtTable.parse(f.readText())
        assertEquals(true, t.size > 1000)
        assertEquals("Super Mario 64 (USA)", t["20b854b239203baf6c961b850a4a51a2"])
    }
}
