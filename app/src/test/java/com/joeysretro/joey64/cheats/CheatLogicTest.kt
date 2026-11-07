package com.joeysretro.joey64.cheats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatLogicTest {
    private val items = Cheat("Infinite Items\\2 player Mode\\Player 1", listOf("80165F5D 00??", "80165F8A 00??"),
        listOf(CheatOption("0D", "Double Mushroom"), CheatOption("0B", "Ghost")), source = "project64")
    private val bonus = Cheat("Have\\Bonus Mode and All Gold Cups", listOf("50000401 0000", "8018ED10 00??"),
        listOf(CheatOption("00", "Off"), CheatOption("FF", "On")), source = "project64")

    @Test fun valuesFillTheQuestionMarks() {
        assertEquals(listOf("80165F5D 000B", "80165F8A 000B"), CheatLogic.resolve(items, "0B"))
        assertEquals(listOf("8013CD0E 0006"), CheatLogic.resolve(Cheat("Laps", listOf("8013CD0E ????")), "0006"))
        assertEquals(listOf("8013CD0E 0006"), CheatLogic.resolve(Cheat("Laps", listOf("8013CD0E ????")), "06"))
        assertEquals(items.codes, CheatLogic.resolve(items, null))
    }

    @Test fun aSourcesOwnOffGoesAndOneChoiceLeftIsPlainOnOff() {
        val t = CheatLogic.tidy(bonus)!!
        assertTrue(t.options.isEmpty())
        assertEquals(listOf("50000401 0000", "8018ED10 00FF"), t.codes)
        assertEquals(2, CheatLogic.tidy(items)!!.options.size)
    }

    @Test fun codesJoey64CantRunAreHidden() {
        assertNull(CheatLogic.tidy(Cheat("Items", listOf("8016609D ????"), flags = setOf("needs_value"))))
    }

    @Test fun gameSharkButtonCodesStaySinceItCanBePressed() {
        val gs = CheatLogic.tidy(Cheat("Press GS\\Debug", listOf("8818ED10 0001"), flags = setOf("gs_button")))!!
        assertTrue(gs.gsButton)
    }

    @Test fun noteLines() {
        assertNull(CheatLogic.noteLine(Cheat("A", listOf("8018ED10 0001"))))
        assertEquals("Press L to stop", CheatLogic.noteLine(Cheat("A", listOf("8018ED10 0001"), note = " Press L to stop ")))
        assertEquals("Needs the GameShark button", CheatLogic.noteLine(Cheat("A", listOf("8818ED10 0001"), flags = setOf("gs_button"))))
        assertEquals("Needs the GameShark button: press it on the title screen",
            CheatLogic.noteLine(Cheat("A", listOf("8818ED10 0001"), note = "Press it on the title screen", flags = setOf("gs_button"))))
        assertEquals("Needs the GameShark button: L to stop",
            CheatLogic.noteLine(Cheat("A", listOf("8818ED10 0001"), note = "L to stop", flags = setOf("gs_button"))))
        assertEquals("Takes effect after a reset · Use on a new save",
            CheatLogic.noteLine(Cheat("A", listOf("F0000318 0040"), note = "Use on a new save", flags = setOf("boot"))))
    }

    @Test fun mergeKeepsTheFirstSourcesNameAndDropsSameCodes() {
        val lr = listOf(
            Cheat("Infinite Items\\2 player Mode\\Player 1", listOf("11111111 0001"), source = "libretro"),
            Cheat("Ghost for player one", items.codes, items.options, source = "libretro"),
            Cheat("Press L To Levitate", listOf("D0000000 0020", "8016F2FA 0040"), source = "libretro"),
        )
        val merged = CheatLogic.merge(mapOf("libretro" to lr, "project64" to listOf(items)))
        assertEquals(listOf(items.name, "More from libretro\\Press L To Levitate"), merged.map { it.name })
        assertEquals("project64", merged[0].source)
    }

    @Test fun libretroAloneHasNoFolder() {
        val lr = listOf(Cheat("Infinite Lives", listOf("80000000 0001"), source = "libretro"))
        assertEquals(listOf("Infinite Lives"), CheatLogic.merge(mapOf("libretro" to lr)).map { it.name })
        val tree = CheatLogic.tree(CheatLogic.merge(mapOf("project64" to listOf(items), "libretro" to lr)))
        assertEquals(listOf("Infinite Items", "More from libretro"), tree.map { it.label })
    }

    @Test fun widescreenAnd60FpsStayOutOfCheats() {
        assertNull(CheatLogic.tidy(Cheat("Widescreen", listOf("80000000 0001"), flags = setOf("widescreen"))))
        assertNull(CheatLogic.tidy(Cheat("60 FPS", listOf("80000000 0001"), flags = setOf("60fps"))))
    }

    @Test fun foldersCountEveryCheatInside() {
        val tree = CheatLogic.tree(listOf(items, items.copy(name = "Infinite Items\\2 player Mode\\Player 2"), bonus.copy(name = "No Laps")))
        assertEquals(listOf("Infinite Items", "No Laps"), tree.map { it.label })
        assertEquals(2, (tree[0] as CheatNode.Folder).count)
        val inner = CheatLogic.at(tree, listOf("Infinite Items", "2 player Mode"))!!
        assertEquals(listOf("Player 1", "Player 2"), inner.map { it.label })
        assertNull(CheatLogic.at(tree, listOf("Gone")))
    }

    @Test fun typedCodesReadOrSayWhy() {
        assertEquals(listOf("8018ED10 0001", "D0123456 0010"), CheatLogic.parseTyped("8018ed10 0001\nD0123456 0010"))
        assertEquals(listOf("8018ED10 0001", "D0123456 0010"), CheatLogic.parseTyped("8018ED10 0001+D0123456:0010"))
        assertNull(CheatLogic.parseTyped("8018ED10 001"))
        assertNull(CheatLogic.parseTyped("8018ED10 0001 oops"))
        assertNull(CheatLogic.parseTyped(""))
    }

    @Test fun keysTryTheCountryThenWithout() {
        assertEquals(listOf("3E5055B6-2E92DA52-45", "3E5055B6-2E92DA52"), CheatLogic.keys("3E5055B6-2E92DA52-45"))
        assertEquals(emptyList<String>(), CheatLogic.keys(null))
    }

    @Test fun listsAndIndexParse() {
        val list = """{"source":"project64","license":"GPL-2.0-only","key":"K","game":"G","cheats":[
            {"name":"A\\B","codes":["80165F5D 00??"],"options":[["0D","Double Mushroom"]],"note":"n"},
            {"name":"C","codes":["8818ED10 0001"],"flags":["gs_button"]}]}"""
        val cheats = CheatLogic.parseList(list)
        assertEquals("A\\B", cheats[0].name)
        assertEquals(listOf(CheatOption("0D", "Double Mushroom")), cheats[0].options)
        assertEquals(setOf("gs_button"), cheats[1].flags)
        assertEquals("project64", cheats[0].source)
        val index = CheatLogic.parseIndex("""{"format":1,"games":{"K":{"game":"G","sources":{"project64":16,"libretro":776}}}}""")
        assertEquals(mapOf("project64" to 16, "libretro" to 776), index["K"])
    }

    @Test fun choicesRoundTrip() {
        val c = CheatChoices(
            on = linkedMapOf(items.name to CheatOn("0B", CheatLogic.resolve(items, "0B")), "No Laps" to CheatOn(null, listOf("8018ED10 0001"))),
            own = listOf(OwnCode("Infinite lives", listOf("8018ED10 0001", "D0123456 0010"), on = true), OwnCode("Off one", listOf("80000000 0001"), on = false)),
        )
        val back = CheatChoices.parse(c.toJson())
        assertEquals(c, back)
        assertEquals(3, back.count)
        assertEquals(listOf("80165F5D 000B,80165F8A 000B", "8018ED10 0001", "8018ED10 0001,D0123456 0010"), back.codeLines())
    }

    @Test fun theShippedRepoFormatParses() {
        // A file as tools/convert.py writes it (joey64-cheats dist/project64/3E5055B6-2E92DA52-45.json, first cheats).
        val text = """{"source":"project64","license":"GPL-2.0-only","key":"3E5055B6-2E92DA52-45","game":"Mario Kart 64 (U)","cheats":[{"name":"Battle Mode\\Infinite Balloons\\Player 1","codes":["8118D8C0 0003"]},{"name":"Have\\Bonus Mode and All Gold Cups","codes":["50000401 0000","8018ED10 00??"],"options":[["00","Off"],["FF","On"]]}]}"""
        val merged = CheatLogic.merge(mapOf("project64" to CheatLogic.parseList(text)))
        assertEquals(2, merged.size)
        assertTrue(merged[1].options.isEmpty())
    }
}
