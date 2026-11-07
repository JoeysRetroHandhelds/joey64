package com.joeysretro.joey64.controls

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlsEditorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val byPos: (PlayerSetup) -> PlayerSetup = { AutoMap.byPosition(it.device, AutoMap.FaceCodes.POSITION) }
    private val r1 = PadInput.Key(KeyEvent.KEYCODE_BUTTON_R1)

    @Test fun allGamesEditsSaveAndResetToAutoMap() {
        val store = ControlsStore(tmp.root)
        val e = ControlsEditor(store, null, byPos)
        e.set(0, e.shown.players[0].with(N64Button.A, r1).copy(deadZone = 25))
        assertEquals(r1, store.all().players[0].buttons[N64Button.A])
        assertTrue(e.changed(0).isEmpty()) // no dots on All games
        e.resetRow(0, "A"); e.resetRow(0, "DeadZone")
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_A), store.all().players[0].buttons[N64Button.A])
        assertEquals(10, store.all().players[0].deadZone)
    }

    @Test fun aGamesFirstChangeCopiesAllGamesAndDotsTheRow() {
        val store = ControlsStore(tmp.root)
        val e = ControlsEditor(store, "Mario Kart 64 (USA)", byPos)
        assertNull(store.game("Mario Kart 64 (USA)"))
        assertEquals(store.all(), e.shown)
        e.set(0, e.shown.players[0].with(N64Button.Z, r1))
        assertEquals(setOf("Z"), e.changed(0))
        assertEquals(1, e.changeCount("Mario Kart 64 (USA)"))
        assertEquals(r1, store.forGame("Mario Kart 64 (USA)").players[0].buttons[N64Button.Z])
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_L2), store.all().players[0].buttons[N64Button.Z])
        // Use All games
        e.resetRow(0, "Z")
        assertTrue(e.changed(0).isEmpty())
        e.removeGame("Mario Kart 64 (USA)")
        assertTrue(store.gamesWithOwn().isEmpty())
    }

    @Test fun pickingAnotherPlayersControllerSwapsTheTwo() {
        val store = ControlsStore(tmp.root)
        val e = ControlsEditor(store, null, byPos)
        val pad = DeviceChoice.Pad("d", "8BitDo 64")
        e.setDevice(1, pad, AutoMap.byPosition(pad, AutoMap.FaceCodes.POSITION).with(N64Button.A, r1))
        assertEquals(pad, e.shown.players[1].device)
        e.setDevice(0, pad)
        assertEquals(pad, e.shown.players[0].device)
        assertEquals(r1, e.shown.players[0].buttons[N64Button.A]) // its setup came with it
        assertEquals(DeviceChoice.BuiltIn, e.shown.players[1].device)
    }

    @Test fun aGamesControllerIsAlwaysAllGames() {
        val store = ControlsStore(tmp.root)
        val e = ControlsEditor(store, "g", byPos)
        e.set(0, e.shown.players[0].copy(deadZone = 0))
        e.setDevice(1, DeviceChoice.Pad("p", "Pad"))
        assertEquals(DeviceChoice.Pad("p", "Pad"), store.all().players[1].device)
        assertEquals(DeviceChoice.Pad("p", "Pad"), e.shown.players[1].device)
        assertEquals(0, store.forGame("g").players[0].deadZone)
    }

    @Test fun groupsClearAndResetTogether() {
        val store = ControlsStore(tmp.root)
        val e = ControlsEditor(store, null, byPos)
        e.set(0, N64Button.C.fold(e.shown.players[0]) { s, b -> s.with(b, null) })
        assertEquals(InputNames.NOT_SET, InputNames.group(e.shown.players[0], N64Button.C))
        e.resetGroup(0, N64Button.C)
        assertEquals("Right stick", InputNames.group(e.shown.players[0], N64Button.C))
    }

    @Test fun hotkeysAreKept() {
        val store = ControlsStore(tmp.root)
        val e = ControlsEditor(store, null, byPos)
        e.setHotkeys(e.hotkeys.copy(button = KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals(KeyEvent.KEYCODE_BUTTON_THUMBL, ControlsEditor(store, "x", byPos).hotkeys.button)
    }
}
