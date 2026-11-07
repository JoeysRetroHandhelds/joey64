package com.joeysretro.joey64.controls

import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val allKeys: (IntArray) -> BooleanArray = { BooleanArray(it.size) { true } }
    private fun only(vararg codes: Int): (IntArray) -> BooleanArray = { q -> BooleanArray(q.size) { q[it] in codes } }

    @Test fun defaultsArePlayerOneOnTheBuiltInControlsByPosition() {
        val d = ControlsSet.defaults()
        assertEquals(DeviceChoice.BuiltIn, d.players[0].device)
        for (i in 1..3) assertEquals(DeviceChoice.None, d.players[i].device)
        val p = d.players[0]
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_A), p.buttons[N64Button.A])
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_X), p.buttons[N64Button.B])
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_L2), p.buttons[N64Button.Z])
        assertEquals("Right stick", InputNames.group(p, N64Button.C))
        assertEquals("D-pad", InputNames.group(p, N64Button.DPAD))
        assertEquals(StickSource.LeftStick, p.stick)
        assertEquals(10, p.deadZone)
        assertEquals(Pak.Auto, p.pak)
    }

    @Test fun nintendoLabelPadsPutAOnTheBottomButton() {
        val p = AutoMap.byPosition(DeviceChoice.BuiltIn, AutoMap.FaceCodes.NINTENDO_LABELS)
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_B), p.buttons[N64Button.A])
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_Y), p.buttons[N64Button.B])
    }

    @Test fun aSetSurvivesJsonWithClearedRowsAndPadDevices() {
        val s = ControlsSet.defaults()
            .with(0, ControlsSet.defaults().players[0].with(N64Button.Z, null).copy(deadZone = 5, pak = Pak.Rumble, stick = StickSource.DPad))
            .with(1, ControlsSet.defaults().players[1].copy(device = DeviceChoice.Pad("abc", "8BitDo 64")))
        val back = ControlsSet.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s, back)
        assertNull(back.players[0].buttons[N64Button.Z])
    }

    @Test fun aDamagedOrPartialFileFallsBackPerField() {
        val o = JSONObject("""{"players":[{"deadZone":99,"stick":"nope","buttons":{"A":"k99"}}]}""")
        val s = ControlsSet.fromJson(o)
        assertEquals(50, s.players[0].deadZone)
        assertEquals(StickSource.LeftStick, s.players[0].stick)
        assertEquals(PadInput.Key(99), s.players[0].buttons[N64Button.A])
        assertEquals(ControlsSet.defaults().players[0].buttons[N64Button.B], s.players[0].buttons[N64Button.B])
        assertEquals(ControlsSet.defaults().players[1], s.players[1])
    }

    @Test fun padInputsEncodeBothWays() {
        for (i in listOf(PadInput.Key(96), PadInput.Axis(MotionEvent.AXIS_RZ, false), PadInput.Axis(MotionEvent.AXIS_Z, true)))
            assertEquals(i, PadInput.decode(i.encode()))
        assertNull(PadInput.decode(""))
        assertNull(PadInput.decode("q1"))
    }

    @Test fun theStoreKeepsAllGamesGamesAndHotkeys() {
        val store = ControlsStore(tmp.root)
        assertEquals(ControlsSet.defaults(), store.all())
        val pad = DeviceChoice.Pad("d1", "Pad")
        store.saveAll(ControlsSet.defaults().with(1, ControlsSet.defaults().players[1].copy(device = pad)))
        val game = ControlsSet.defaults().with(0, ControlsSet.defaults().players[0].copy(deadZone = 3, device = DeviceChoice.None))
        store.saveGame("Mario Kart 64 (USA)", game)
        assertEquals(listOf("Mario Kart 64 (USA)"), store.gamesWithOwn())
        val f = store.forGame("Mario Kart 64 (USA)")
        assertEquals(3, f.players[0].deadZone)
        // Which controller each player has is always All games'.
        assertEquals(DeviceChoice.BuiltIn, f.players[0].device)
        assertEquals(pad, f.players[1].device)
        assertEquals(store.all(), store.forGame("Another game"))
        store.removeGame("Mario Kart 64 (USA)")
        assertTrue(store.gamesWithOwn().isEmpty())

        val h = Hotkeys().copy(button = KeyEvent.KEYCODE_BUTTON_THUMBL, keys = Hotkeys().keys + (HotkeyAction.Quit to null))
        store.saveHotkeys(h)
        assertEquals(h, store.hotkeys())
        assertNull(store.hotkeys().actionFor(KeyEvent.KEYCODE_BUTTON_B, alone = false))
        assertEquals(HotkeyAction.Save, store.hotkeys().actionFor(KeyEvent.KEYCODE_BUTTON_R1, alone = false))
        // Fast-forward has no button by default.
        assertNull(store.hotkeys().actionFor(KeyEvent.KEYCODE_BUTTON_THUMBR, alone = true))
    }

    @Test fun fastForwardLosesTheOldR3DefaultButKeepsAPickedButton() {
        val r3 = KeyEvent.KEYCODE_BUTTON_THUMBR
        // A file from before version 2 saved every default, R3 for fast-forward included.
        val old = Hotkeys.fromJson(JSONObject("""{"button":109,"keys":{"FastForward":$r3,"Save":103}}"""))
        assertNull(old.keys[HotkeyAction.FastForward])
        assertEquals(KeyEvent.KEYCODE_BUTTON_R1, old.keys[HotkeyAction.Save])
        val picked = Hotkeys.fromJson(JSONObject("""{"button":109,"keys":{"FastForward":${KeyEvent.KEYCODE_BUTTON_R2}}}"""))
        assertEquals(KeyEvent.KEYCODE_BUTTON_R2, picked.keys[HotkeyAction.FastForward])
        // Picked again after the change, R3 stays.
        val again = Hotkeys().copy(keys = Hotkeys().keys + (HotkeyAction.FastForward to r3))
        assertEquals(r3, Hotkeys.fromJson(again.toJson()).keys[HotkeyAction.FastForward])
    }

    @Test fun unsafeSaveNamesBecomeFileNames() {
        assertEquals("a_b_c", ControlsStore.safe("a/b:c"))
    }

    @Test fun differencesMarkTheChangedRows() {
        val a = ControlsSet.defaults().players[0]
        val b = a.with(N64Button.A, PadInput.Key(KeyEvent.KEYCODE_BUTTON_R1)).copy(deadZone = 5)
        assertEquals(setOf("A", "DeadZone"), b.differences(a))
    }

    // ---- Auto map ----

    @Test fun theSwitchOnlineN64HasTwoLayoutsPickedByAxisRx() {
        val generic = AutoMap.layoutsFor(0x057e, 0x2019, "N64 Controller", allKeys, hasRx = true)
        assertEquals(1, generic.size)
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_B), generic[0].buttons[N64Button.A])
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_C), generic[0].buttons[N64Button.CUp])
        val driver = AutoMap.layoutsFor(0x057e, 0x2019, "N64 Controller", allKeys, hasRx = false)
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_A), driver[0].buttons[N64Button.A])
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_SELECT), driver[0].buttons[N64Button.CUp])
    }

    @Test fun the8BitDo64HasItsCButtonsOnTheRightStick() {
        val l = AutoMap.layoutsFor(0x2dc8, 0x3019, "8BitDo 64", allKeys, hasRx = false).single()
        val s = AutoMap.fromLayout(DeviceChoice.Pad("x", "8BitDo 64"), l)
        assertEquals("Right stick", InputNames.group(s, N64Button.C))
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_L2), s.buttons[N64Button.Z])
        // The D-pad isn't in the table: it keeps the position rule's.
        assertEquals("D-pad", InputNames.group(s, N64Button.DPAD))
    }

    @Test fun linuxNumberedRowsResolveToTheNumberingThePadHas() {
        // A joystick-numbered pad (BUTTON_1..): Retrolink's A is Linux button 6, KEYCODE_BUTTON_7.
        val joy = AutoMap.layoutsFor(0x0079, 0x0006, "Generic   USB  Joystick", only(*(188..203).toList().toIntArray()), hasRx = false)
        assertEquals(2, joy.size)
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_7), joy[0].buttons[N64Button.A])
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_6), joy[1].buttons[N64Button.A])
        // retroUSB's RetroPort numbers like a gamepad: SDL's Android entry has its A on R1.
        val rp = AutoMap.layoutsFor(0x1234, 0x0004, "N64 RetroPort",
            only(96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 108, 109, 110, 106, 107), hasRx = false)
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_R1), rp[0].buttons[N64Button.A])
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_START), rp[0].buttons[N64Button.CUp])
    }

    @Test fun onePressPicksBetweenLayoutsSharingAnId() {
        val joy = AutoMap.layoutsFor(0x0079, 0x0006, "x", only(*(188..203).toList().toIntArray()), hasRx = false)
        assertEquals("Retrolink N64 USB", AutoMap.pickByPress(joy, KeyEvent.KEYCODE_BUTTON_7)?.name)
        assertEquals("N64-style USB pad", AutoMap.pickByPress(joy, KeyEvent.KEYCODE_BUTTON_6)?.name)
        assertNull(AutoMap.pickByPress(joy, KeyEvent.KEYCODE_BUTTON_1))
        val mf = AutoMap.layoutsFor(0x2f24, 0x00f5, "MAYFLASH N64 USB Adapter MF103", allKeys, hasRx = false)
        assertEquals(2, mf.size)
    }

    @Test fun unknownPadsGetNoLayoutAndBrawlerIsKnownByName() {
        assertTrue(AutoMap.layoutsFor(0x045e, 0x028e, "Xbox 360 Controller", allKeys, false).isEmpty())
        val b = AutoMap.layoutsFor(0x045e, 0x028e, "Retro Fighters Brawler64", allKeys, false).single()
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_THUMBR), b.buttons[N64Button.CUp])
    }

    // ---- In game ----

    @Test fun keysHatsAndTriggersPressTheirN64Buttons() {
        val p = PlayerInput(ControlsSet.defaults().players[0])
        p.key(KeyEvent.KEYCODE_BUTTON_A, true)
        assertEquals(1 shl N64Button.A.retroBit, p.buttons())
        p.key(KeyEvent.KEYCODE_BUTTON_A, false)
        p.setHat(0f, -1f)
        assertEquals(1 shl N64Button.DUp.retroBit, p.buttons())
        p.setHat(0f, 0f)
        p.setTriggers(1f, 0f)
        assertEquals(1 shl N64Button.Z.retroBit, p.buttons())
        p.setTriggers(0f, 0f)
        p.setAxes(0f, 0f, 0.9f, -0.9f)
        assertEquals((1 shl N64Button.CRight.retroBit) or (1 shl N64Button.CUp.retroBit), p.buttons())
        assertTrue(p.uses(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(p.uses(KeyEvent.KEYCODE_BUTTON_THUMBL))
        p.release()
        assertEquals(0, p.buttons())
    }

    @Test fun aClearedButtonPressesNothingAndARemapMovesIt() {
        val s = ControlsSet.defaults().players[0].with(N64Button.A, null).with(N64Button.Z, PadInput.Key(KeyEvent.KEYCODE_BUTTON_A))
        val p = PlayerInput(s)
        p.key(KeyEvent.KEYCODE_BUTTON_A, true)
        assertEquals(1 shl N64Button.Z.retroBit, p.buttons())
    }

    @Test fun theDeadZoneIsRoundAndKeepsFullReach() {
        assertEquals(0f to 0f, PlayerInput.deadZone(0.05f, 0.05f, 10))
        val (x, _) = PlayerInput.deadZone(1f, 0f, 10)
        assertEquals(1f, x, 1e-5f)
        val (h, _) = PlayerInput.deadZone(0.55f, 0f, 10)
        assertEquals(0.5f, h, 1e-5f)
    }

    @Test fun theStickOnTheDpadIsDigitalFullTilt() {
        val p = PlayerInput(ControlsSet.defaults().players[0].copy(stick = StickSource.DPad))
        assertTrue(p.uses(KeyEvent.KEYCODE_DPAD_LEFT))
        p.key(KeyEvent.KEYCODE_DPAD_LEFT, true)
        assertEquals(-1f to 0f, p.stick())
    }

    @Test fun autoPakIsRumbleForTheListedGames() {
        val rumble = setOf("5bd1fe107bf8106b2ab6650abecd54d6")
        assertEquals(Pak.Rumble, PakDb.auto(rumble, "5BD1FE107BF8106B2AB6650ABECD54D6"))
        assertEquals(Pak.Controller, PakDb.auto(rumble, "3a67d9986f54eb282924fca4cd5f6dff"))
        assertEquals(Pak.Controller, PakDb.auto(rumble, null))
    }

    @Test fun inputNamesForThePage() {
        assertEquals("A (bottom)", InputNames.key(KeyEvent.KEYCODE_BUTTON_A, AutoMap.FaceCodes.POSITION))
        assertEquals("X (left)", InputNames.key(KeyEvent.KEYCODE_BUTTON_X, AutoMap.FaceCodes.POSITION))
        assertEquals("A (right)", InputNames.key(KeyEvent.KEYCODE_BUTTON_A, AutoMap.FaceCodes.NINTENDO_LABELS))
        assertEquals("Button 7", InputNames.key(KeyEvent.KEYCODE_BUTTON_7, null))
        assertEquals("R stick ←", InputNames.of(PadInput.Axis(MotionEvent.AXIS_Z, false), null))
        assertEquals("Not set", InputNames.of(null, null))
        assertNotNull(InputNames.group(ControlsSet.defaults().players[0], N64Button.C))
        assertNull(InputNames.group(ControlsSet.defaults().players[0].with(N64Button.CUp, PadInput.Key(KeyEvent.KEYCODE_BUTTON_Y)), N64Button.C))
    }
}
