package com.joeysretro.joey64.controls

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Controls extras (approved mockup 2026-11-08): tap to hold, macros, profiles. */
class ControlsExtrasTest {
    @get:Rule val tmp = TemporaryFolder()

    private val byPos: (PlayerSetup) -> PlayerSetup = { AutoMap.byPosition(it.device, AutoMap.FaceCodes.POSITION) }
    private val zBit = 1 shl N64Button.Z.retroBit
    private val aBit = 1 shl N64Button.A.retroBit

    @Test fun tapToHoldTogglesOnEachPress() {
        val p = PlayerInput(ControlsSet.defaults().players[0].copy(latch = setOf(N64Button.Z)))
        p.setTriggers(1f, 0f)                 // Z (L2) pressed
        assertEquals(zBit, p.buttons())
        p.setTriggers(0f, 0f)                 // let go: still held
        assertEquals(zBit, p.buttons())
        assertEquals(setOf(N64Button.Z), p.latched())
        p.key(KeyEvent.KEYCODE_BUTTON_A, true) // other buttons work as always
        assertEquals(zBit or aBit, p.buttons())
        p.key(KeyEvent.KEYCODE_BUTTON_A, false)
        assertEquals(zBit, p.buttons())
        p.setTriggers(1f, 0f)                 // tapped again: let go
        assertEquals(0, p.buttons())
        p.setTriggers(0f, 0f)
        assertEquals(0, p.buttons())
        assertTrue(p.latched().isEmpty())
    }

    @Test fun releaseLetsGoOfHeldButtons() {
        val p = PlayerInput(ControlsSet.defaults().players[0].copy(latch = setOf(N64Button.Z)))
        p.setTriggers(1f, 0f); p.buttons(); p.setTriggers(0f, 0f); p.buttons()
        p.release()
        assertEquals(0, p.buttons())
        // Unticking the button lets go too.
        p.setTriggers(1f, 0f); p.buttons(); p.setTriggers(0f, 0f); p.buttons()
        p.setup = p.setup.copy(latch = emptySet())
        assertEquals(0, p.buttons())
    }

    @Test fun labels() {
        assertEquals("Off", latchLabel(emptySet()))
        assertEquals("Z, R", latchLabel(setOf(N64Button.R, N64Button.Z)))
        assertEquals("C up", latchLabel(setOf(N64Button.CUp)))
        assertEquals("Z held · tap Z to let go", heldLine(setOf(N64Button.Z)))
        val m = Macro("Fast text", KeyEvent.KEYCODE_BUTTON_THUMBR, repeat = true, steps = listOf(
            MacroStep(setOf(N64Button.A)), MacroStep(frames = 4), MacroStep(setOf(N64Button.A)), MacroStep(frames = 4), MacroStep(setOf(N64Button.A))))
        assertEquals("A, wait, A, wait, A · repeats while held", m.summary())
        assertEquals("Nothing", MacroStep(frames = 4).label())
        assertEquals("B + Stick left", MacroStep(setOf(N64Button.B), setOf(StickDir.Left)).label())
        assertEquals("About 33 ms", MacroStep(frames = 2).msLabel())
        assertEquals("1 frame", MacroStep(frames = 1).framesLabel())
        assertEquals("Macro 2", Macro.newName(listOf(Macro("Macro 1"), Macro("Macro 3"))))
    }

    @Test fun macroStepsGiveMaskAndStick() {
        val s = MacroStep(setOf(N64Button.A, N64Button.Z), setOf(StickDir.Up, StickDir.Right))
        assertEquals(aBit or zBit, s.mask())
        assertEquals(MacroStep.DIAGONAL to -MacroStep.DIAGONAL, s.stickXY())
        assertEquals(-1f to 0f, MacroStep(stick = setOf(StickDir.Left)).stickXY())
        assertEquals(0f to 0f, MacroStep(stick = setOf(StickDir.Left, StickDir.Right)).stickXY())
    }

    @Test fun macroForKeyNeedsAButtonAndAPress() {
        val r3 = KeyEvent.KEYCODE_BUTTON_THUMBR
        val ms = listOf(
            Macro("Only waits", r3, steps = listOf(MacroStep(frames = 4))),
            Macro("Spin", r3, withHotkey = true, steps = listOf(MacroStep(stick = setOf(StickDir.Left)))),
            Macro("Jump", r3, steps = listOf(MacroStep(setOf(N64Button.A)))),
        )
        assertEquals("Jump", Macro.forKey(ms, r3, false)?.name)
        assertEquals("Spin", Macro.forKey(ms, r3, true)?.name)
        assertNull(Macro.forKey(ms, KeyEvent.KEYCODE_BUTTON_Y, false))
    }

    @Test fun extrasRoundTripAndDot() {
        val base = ControlsSet.defaults().players[0]
        val s = base.copy(latch = setOf(N64Button.Z, N64Button.R), macros = listOf(
            Macro("Spin jump", KeyEvent.KEYCODE_BUTTON_Y, withHotkey = true, steps = listOf(
                MacroStep(stick = setOf(StickDir.Left), frames = 3), MacroStep(setOf(N64Button.B), frames = 60)))))
        assertEquals(s, PlayerSetup.fromJson(s.toJson(), base))
        assertEquals(setOf("Tap", "Macros"), s.differences(base))
        // A file from before the extras keeps the fallback's (none).
        val old = base.toJson().apply { remove("latch"); remove("macros") }
        assertEquals(base, PlayerSetup.fromJson(old, base))
    }

    @Test fun rowsResetToAllGames() {
        val store = ControlsStore(tmp.root)
        val e = ControlsEditor(store, "Mario Kart 64 (USA)", byPos)
        e.set(0, e.shown.players[0].copy(latch = setOf(N64Button.Z), macros = listOf(Macro("M"))))
        assertEquals(setOf("Tap", "Macros"), e.changed(0))
        e.resetRow(0, "Tap"); e.resetRow(0, "Macros")
        assertTrue(e.changed(0).isEmpty())
    }

    @Test fun profilesSaveLoadRenameDelete() {
        val store = ControlsStore(tmp.root)
        val e = ControlsEditor(store, null, byPos)
        val r1 = PadInput.Key(KeyEvent.KEYCODE_BUTTON_R1)
        e.set(0, e.shown.players[0].with(N64Button.A, r1).copy(latch = setOf(N64Button.Z), deadZone = 20, pak = Pak.Rumble))
        e.saveProfile(0, " Mario Kart ", "8BitDo pad", 1000L)
        e.saveProfile(0, "Shooters", "Built-in controls", 2000L)
        assertEquals(listOf("Shooters", "Mario Kart"), e.profiles().map { it.name })
        // Player 2 loads it: buttons, dead zone and tap to hold come; the controller and pak stay.
        e.loadProfile(1, e.profiles().first { it.name == "Mario Kart" })
        val p2 = store.all().players[1]
        assertEquals(r1, p2.buttons[N64Button.A])
        assertEquals(20, p2.deadZone)
        assertEquals(setOf(N64Button.Z), p2.latch)
        assertEquals(DeviceChoice.None, p2.device)
        assertEquals(Pak.Auto, p2.pak)
        // Replacing by name (any case) keeps one.
        e.saveProfile(0, "mario kart", "8BitDo pad", 3000L)
        assertEquals(listOf("mario kart", "Shooters"), e.profiles().map { it.name })
        assertFalse(e.renameProfile("mario kart", "SHOOTERS"))
        assertTrue(e.renameProfile("mario kart", "Kart"))
        assertEquals(3000L, e.profiles().first { it.name == "Kart" }.savedAt)
        e.removeProfile("Kart")
        assertEquals(listOf("Shooters"), e.profiles().map { it.name })
    }

    @Test fun aProfileLoadedOnAGameChangesThatGameOnly() {
        val store = ControlsStore(tmp.root)
        val all = ControlsEditor(store, null, byPos)
        all.set(0, all.shown.players[0].copy(latch = setOf(N64Button.R)))
        all.saveProfile(0, "Shield", "Built-in controls", 1L)
        all.set(0, all.shown.players[0].copy(latch = emptySet()))
        val g = ControlsEditor(store, "GoldenEye 007 (USA)", byPos)
        g.loadProfile(0, g.profiles().single())
        assertEquals(setOf(N64Button.R), store.forGame("GoldenEye 007 (USA)").players[0].latch)
        assertTrue(store.all().players[0].latch.isEmpty())
        assertEquals(setOf("Tap"), g.changed(0))
    }

    // ---- Turbo (approved mockup 2026-11-08) ----

    @Test fun turboLabelsMaskAndNames() {
        assertEquals("Off", turboLabel(emptySet()))
        assertEquals("A, B", turboLabel(setOf(N64Button.B, N64Button.A)))
        assertEquals("Z, C up", turboLabel(setOf(N64Button.CUp, N64Button.Z)))
        // A button held by a tap doesn't repeat; Start can't be ticked.
        assertEquals(1 shl N64Button.A.retroBit, turboMask(setOf(N64Button.A, N64Button.Z), setOf(N64Button.Z)))
        assertEquals(0, turboMask(setOf(N64Button.Start), emptySet()))
        assertEquals("A and B", turboNames(setOf(N64Button.A, N64Button.B), and = true))
        assertEquals("A, B and Z", turboNames(setOf(N64Button.Z, N64Button.A, N64Button.B), and = true))
        assertEquals("A, B", turboNames(setOf(N64Button.A, N64Button.B)))
        assertEquals(10, DEFAULT_TURBO_SPEED)
        assertEquals(listOf(5, 8, 10, 15, 20), TURBO_SPEEDS)
    }

    @Test fun turboRoundTripDotAndOldFiles() {
        val base = ControlsSet.defaults().players[0]
        assertTrue(base.turbo.isEmpty())
        assertEquals(10, base.turboSpeed)
        val s = base.copy(turbo = setOf(N64Button.A, N64Button.CDown), turboSpeed = 15)
        assertEquals(s, PlayerSetup.fromJson(s.toJson(), base))
        assertEquals(setOf("Turbo"), s.differences(base))
        assertEquals(setOf("Turbo"), base.copy(turboSpeed = 20).differences(base))
        val old = base.toJson().apply { remove("turbo"); remove("turboSpeed") }
        assertEquals(base, PlayerSetup.fromJson(old, base))
        // A speed that isn't a choice, or Start in the list, is dropped.
        val odd = s.toJson().put("turboSpeed", 7).put("turbo", org.json.JSONArray().put("Start").put("A"))
        val r = PlayerSetup.fromJson(odd, base)
        assertEquals(setOf(N64Button.A), r.turbo)
        assertEquals(10, r.turboSpeed)
    }

    @Test fun turboRowResetsAndProfilesKeepIt() {
        val store = ControlsStore(tmp.root)
        val e = ControlsEditor(store, "Star Fox 64 (USA)", byPos)
        e.set(0, e.shown.players[0].copy(turbo = setOf(N64Button.A, N64Button.B), turboSpeed = 20))
        assertEquals(setOf("Turbo"), e.changed(0))
        e.resetRow(0, "Turbo")
        assertTrue(e.changed(0).isEmpty())
        val prof = Profile("Shooter", 0L, "Pad", ControlsSet.defaults().players[0].copy(turbo = setOf(N64Button.Z), turboSpeed = 8))
        val applied = Profile.applyTo(ControlsSet.defaults().players[1], prof)
        assertEquals(setOf(N64Button.Z), applied.turbo)
        assertEquals(8, applied.turboSpeed)
    }

    @Test fun blankMacroIsOnlyOneWithNothingSet() {
        assertTrue(Macro("Macro 1").blank)
        assertTrue(Macro("Macro 1", steps = listOf(MacroStep())).blank)
        assertFalse(Macro("Macro 1", key = KeyEvent.KEYCODE_BUTTON_L1).blank)
        assertFalse(Macro("Macro 1", steps = listOf(MacroStep(buttons = setOf(N64Button.A)))).blank)
    }
}
