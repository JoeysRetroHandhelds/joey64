package com.joeysretro.joey64.video

import com.joeysretro.joey64.cheats.Cheat
import com.joeysretro.joey64.cheats.CheatOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameExtrasTest {
    private fun c(name: String, flag: String, note: String? = null, codes: List<String> = listOf("81000000 0000"), src: String = "project64",
                  options: List<CheatOption> = emptyList(), extra: Set<String> = emptySet()) =
        Cheat(name, codes, options, note, setOf(flag) + extra, src)

    // Mario Kart 64 (U), as joey64-data has it: Project64 16:9, 21:9, two 60 fps codes; libretro another widescreen.
    private val mk64 = mapOf(
        "project64" to listOf(
            c("16:9 Widescreen", "widescreen", "For 16:9 aspect ratios. Do not use with other widescreen enhancements or hacks.", listOf("81000000 0001")),
            c("21:9 Widescreen", "widescreen", "For 21:9 aspect ratios. Do not use with other widescreen enhancements or hacks.", listOf("81000000 0002")),
            c("Sixty Frames (1P Mode)", "60fps", codes = listOf("81000000 0003")),
            c("WIP 60 FPS hack", "60fps", codes = listOf("81000000 0004")),
            c("Infinite Items", "none")),
        "libretro" to listOf(c("Widescreen", "widescreen", codes = listOf("81000000 0009"), src = "libretro")),
    )

    @Test fun versionsComeFromTheFirstSourceWithThem() {
        val x = GameExtras.from(mk64, "3E5055B6-2E92DA52-45")
        assertEquals(listOf("16:9", "21:9"), x.widescreen.map { it.label })
        assertEquals("For most phones and handhelds", x.widescreen[0].detail)
        assertEquals("For extra-wide screens; black bars at the top and bottom on 16:9", x.widescreen[1].detail)
        assertEquals(21 to 9, x.widescreen[1].shape)
        assertEquals(listOf("60 fps (1 player)", "60 fps hack"), x.fps.map { it.label })
        assertEquals(listOf(false, true), x.fps.map { it.wip })
        assertEquals("May glitch", x.fps[1].detail)
        assertEquals("project64", x.source[ExtraKind.Widescreen])
        assertFalse(x.pal)
    }

    @Test fun picks() {
        val x = GameExtras.from(mk64, null)
        assertNull(x.pick(ExtraKind.Widescreen, "off"))
        assertEquals("16:9 Widescreen", x.pick(ExtraKind.Widescreen, GameExtras.ALL)?.name)
        assertEquals("21:9 Widescreen", x.pick(ExtraKind.Widescreen, "21:9 Widescreen")?.name)
        // When a game has one skips a WIP code; a name the lists lost falls back the same way.
        assertEquals("Sixty Frames (1P Mode)", x.pick(ExtraKind.Fps, GameExtras.ALL)?.name)
        assertEquals("Sixty Frames (1P Mode)", x.pick(ExtraKind.Fps, "Gone")?.name)
        assertNull(GameExtras.NONE.pick(ExtraKind.Fps, GameExtras.ALL))
    }

    @Test fun oneVersionReadsOn() {
        val x = GameExtras.from(mapOf("project64" to listOf(c("Widescreen", "widescreen"))), null)
        assertEquals(listOf("On"), x.widescreen.map { it.label })
        assertEquals(16 to 9, x.widescreen[0].shape)
        assertTrue(x.fps.isEmpty())
    }

    @Test fun libretroWhenNoOtherSourceHasThem() {
        val x = GameExtras.from(mapOf("libretro" to listOf(c("Widescreen Mode", "widescreen", src = "libretro"))), null)
        assertEquals("libretro", x.source[ExtraKind.Widescreen])
        assertEquals(1, x.widescreen.size)
    }

    @Test fun codesJoey64CantRunAreLeftOut() {
        val lists = mapOf("project64" to listOf(
            c("Widescreen", "widescreen", extra = setOf("needs_value")),
            c("Sixty Frames", "60fps", options = listOf(CheatOption("01", "On"), CheatOption("00", "Off")), codes = listOf("810000?? 0000")),
            c("Fifty Frames", "60fps", options = listOf(CheatOption("01", "A"), CheatOption("02", "B")))))
        val x = GameExtras.from(lists, null)
        assertTrue(x.widescreen.isEmpty())
        // A single option is filled in; two to pick from can't be a row's version.
        assertEquals(listOf("81000001 0000"), x.fps.single().codes)
    }

    @Test fun palGamesByCountry() {
        assertTrue(GameExtras.isPal("635A2BFF-8B022326-50"))
        assertTrue(GameExtras.isPal("635A2BFF-8B022326-44"))
        assertFalse(GameExtras.isPal("3E5055B6-2E92DA52-45"))
        assertFalse(GameExtras.isPal("3E5055B6-2E92DA52-4A"))
        assertFalse(GameExtras.isPal(null))
    }

    @Test fun notesAndLabels() {
        assertNull(GameExtras.tidyNote("For 16:9 aspect ratios. Do not use with other widescreen enhancements or hacks."))
        assertEquals("Menu Misalignments", GameExtras.tidyNote("Menu Misalignments. For 16:9 aspect ratios. Do not use with other widescreen enhancements or hacks."))
        assertEquals("Changes an ASM value, too fast", GameExtras.tidyNote("Changes an ASM value,too fast."))
        assertEquals("50 fps", GameExtras.fpsLabel("Fifty Frames"))
        assertEquals("Alt 60 fps", GameExtras.fpsLabel("Alt Sixty Frames"))
    }

    @Test fun whatAGameStartsWith() {
        val x = GameExtras.from(mk64, null)
        val on = ExtrasOn.of(x, VideoSettings(mapOf(VideoKey.Widescreen to "21:9 Widescreen", VideoKey.Fps to GameExtras.ALL)), hardcore = false)
        assertEquals(listOf("81000000 0002", "81000000 0003"), on.codeLines)
        assertEquals(21 to 9, on.shape)
        assertTrue(on.fps)
        assertEquals(21 to 9, VideoApply.aspect(VideoSettings(emptyMap()), on.shape))
        val off = ExtrasOn.of(x, VideoSettings.DEFAULTS, hardcore = false)
        assertTrue(off.codeLines.isEmpty()); assertNull(off.shape); assertFalse(off.fps)
        val hc = ExtrasOn.of(x, VideoSettings(mapOf(VideoKey.Widescreen to GameExtras.ALL)), hardcore = true)
        assertTrue(hc.codeLines.isEmpty())
    }

    @Test fun rowsStartOffAndApplyAtStart() {
        assertEquals("off", VideoSettings.DEFAULTS[VideoKey.Widescreen])
        assertEquals("off", VideoSettings.DEFAULTS[VideoKey.Fps])
        assertTrue(VideoKey.Widescreen in VideoKey.AT_START && VideoKey.Fps in VideoKey.AT_START)
        assertEquals("When a game has one", VideoChoices.label(VideoKey.Widescreen, "auto"))
        assertEquals("60 fps (1 player)", VideoChoices.label(VideoKey.Fps, "Sixty Frames (1P Mode)"))
    }

    @Test fun ownWidescreenMode() {
        val auto = VideoSettings(mapOf(VideoKey.Widescreen to GameExtras.ALL))
        val pd = ExtrasOn.withOwnMode(ExtrasOn.NONE, "NPDE", auto, hardcore = false)
        assertEquals(16 to 9, pd.shape); assertTrue(pd.codeLines.isEmpty())
        assertEquals(16 to 9, VideoApply.aspect(VideoSettings(emptyMap()), pd.shape))
        assertEquals(16 to 9, ExtrasOn.withOwnMode(ExtrasOn.NONE, "NFUP", auto, hardcore = false).shape)
        // Aspect ratio set by hand wins
        assertEquals(4 to 3, VideoApply.aspect(VideoSettings(mapOf(VideoKey.Aspect to "4:3")), pd.shape))
        // Widescreen off or Hardcore: nothing; a game not on the list: nothing
        assertNull(ExtrasOn.withOwnMode(ExtrasOn.NONE, "NPDE", VideoSettings.DEFAULTS, hardcore = false).shape)
        assertNull(ExtrasOn.withOwnMode(ExtrasOn.NONE, "NPDE", auto, hardcore = true).shape)
        assertNull(ExtrasOn.withOwnMode(ExtrasOn.NONE, "NSME", auto, hardcore = false).shape)
        assertNull(ExtrasOn.withOwnMode(ExtrasOn.NONE, null, auto, hardcore = false).shape)
        // A picked code keeps its own shape
        val code = ExtrasOn(listOf("81000000 0002"), 21 to 9, false, "code")
        assertEquals(21 to 9, ExtrasOn.withOwnMode(code, "NGEE", auto, hardcore = false).shape)
    }
}
