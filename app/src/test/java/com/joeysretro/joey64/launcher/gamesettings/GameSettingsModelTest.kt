package com.joeysretro.joey64.launcher.gamesettings

import com.joeysretro.joey64.audio.AudioKey
import com.joeysretro.joey64.audio.AudioStore
import com.joeysretro.joey64.controls.ControlsEditor
import com.joeysretro.joey64.controls.ControlsStore
import com.joeysretro.joey64.controls.StickFeel
import com.joeysretro.joey64.emulation.EmuKey
import com.joeysretro.joey64.emulation.EmuStore
import com.joeysretro.joey64.video.VideoKey
import com.joeysretro.joey64.video.VideoSettings
import com.joeysretro.joey64.video.VideoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameSettingsModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val banjo = "Banjo-Kazooie (USA)"
    private val tooie = "Banjo-Tooie (USA)"
    private fun video() = VideoStore(File(tmp.root, "video"))
    private fun audio() = AudioStore(File(tmp.root, "audio"))
    private fun controls() = ControlsStore(File(tmp.root, "controls"))
    private fun emu() = EmuStore(File(tmp.root, "emulation"), null)
    private fun model() = GameSettingsModel(tmp.root)

    private fun mockupGame() {
        video().saveGame(banjo, mapOf(VideoKey.Resolution to "2") + VideoSettings.LOOKS.getValue("sharp"))
        audio().saveGame(banjo, mapOf(AudioKey.Volume to "60"))
    }

    @Test fun nothingOfItsOwn() {
        val s = model().summary(banjo)
        assertEquals(0, s.total)
        assertEquals("Global", s.label)
        assertTrue(model().parts(banjo).isEmpty())
    }

    @Test fun theMockupsThreeChanges() {
        mockupGame()
        val s = model().summary(banjo)
        assertEquals("3 changed", s.label)
        assertEquals(2, s.count(GamePart.Video)) // Look counts as one, though it sets five filter rows
        assertEquals(listOf(
            GameChange(GamePart.Video, "Resolution", "2x", "Native (1x)", "res"),
            GameChange(GamePart.Video, "Look", "Sharp", "Original", "filters:look"),
            GameChange(GamePart.Audio, "Volume", "60%", "100%", "Volume")), s.changes)
        assertEquals(setOf(GamePart.Video, GamePart.Audio), model().parts(banjo))
    }

    @Test fun controlsRowsArePerPlayer() {
        val ed = ControlsEditor(controls(), banjo) { it }
        ed.set(1, ed.shown.players[1].copy(deadZone = 20, feel = StickFeel(range = 120)))
        val c = model().summary(banjo).changes.filter { it.part == GamePart.Controls }
        assertEquals(listOf("Player 2 · Dead zone", "Player 2 · Stick feel · Range"), c.map { it.name })
        assertEquals(listOf("20%", "120%"), c.map { it.value })
        assertEquals(listOf("10%", "100%"), c.map { it.allGames })
        assertEquals("p1", c[0].row)
    }

    @Test fun resetKeepsAllGames() {
        mockupGame()
        video().saveAll(mapOf(VideoKey.Aspect to "16:9"))
        model().reset(banjo)
        assertNull(video().game(banjo)); assertNull(audio().game(banjo)); assertNull(controls().game(banjo))
        assertEquals("16:9", video().all()[VideoKey.Aspect])
    }

    @Test fun copyReplacesThePartsPicked() {
        mockupGame()
        video().saveGame(tooie, mapOf(VideoKey.Aspect to "16:9", VideoKey.Resolution to "4"))
        audio().saveGame(tooie, mapOf(AudioKey.Latency to "safe"))
        model().copy(banjo, tooie, setOf(GamePart.Video))
        assertEquals(video().game(banjo), video().game(tooie))      // replaced, not mixed: no 16:9 left
        assertEquals(mapOf(AudioKey.Latency to "safe"), audio().game(tooie)) // Audio not picked: untouched
        model().copy(banjo, tooie, setOf(GamePart.Audio))
        assertEquals(mapOf(AudioKey.Volume to "60"), audio().game(tooie))
    }

    @Test fun copyingAPartWithNothingClearsIt() {
        mockupGame()
        val ed = ControlsEditor(controls(), tooie) { it }
        ed.set(0, ed.shown.players[0].copy(deadZone = 25))
        model().copy(banjo, tooie, setOf(GamePart.Controls))
        assertNull(controls().game(tooie))
    }

    @Test fun emulationRowsAndCopy() {
        emu().saveAll(mapOf(EmuKey.Overclock to "2x"))
        emu().saveGame(banjo, mapOf(EmuKey.Overclock to "normal", EmuKey.ExpansionPak to "off"))
        val c = model().summary(banjo).changes
        assertEquals(listOf(
            GameChange(GamePart.Emulation, "Overclock", "Normal", "2x", "Overclock"),
            GameChange(GamePart.Emulation, "Expansion Pak", "Off", "On", "ExpansionPak")), c)
        assertEquals(setOf(GamePart.Emulation), model().parts(banjo))
        model().copy(banjo, tooie, setOf(GamePart.Emulation))
        assertEquals(emu().game(banjo), emu().game(tooie))
        model().reset(banjo)
        assertNull(emu().game(banjo))
    }
}
