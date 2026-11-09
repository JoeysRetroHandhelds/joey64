package com.joeysretro.joey64.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paks (approved paks mockup 2026-11-08, scratchpad build_paks2.py): Rumble on, Rumble strength, Transfer Pak. */
class PaksTest {
    private val gold = GameBoyFiles.Found("content://x/gb/Pokemon%20Gold.gbc", "Pokémon Gold (USA).gbc")

    @Test fun newFieldsRoundTrip() {
        val d = ControlsSet.defaults().players[0]
        val s = d.copy(pak = Pak.Transfer, rumbleOn = RumbleOn.Device, rumbleStrength = 40, gbGame = gold,
            gbSave = GameBoyFiles.Found("content://x/gb/s", "Pokémon Gold (USA).sav"))
        assertEquals(s, PlayerSetup.fromJson(s.toJson(), d))
        // An older file has none of them: the defaults.
        val old = PlayerSetup.fromJson(d.toJson().apply { remove("rumbleOn"); remove("rumbleStrength") }, d)
        assertEquals(RumbleOn.Controller, old.rumbleOn)
        assertEquals(100, old.rumbleStrength)
        assertNull(old.gbGame)
        // A strength off the steps falls back.
        assertEquals(100, PlayerSetup.fromJson(d.toJson().put("rumbleStrength", 33), d).rumbleStrength)
    }

    @Test fun differencesNameTheRows() {
        val d = ControlsSet.defaults().players[0]
        assertEquals(setOf("RumbleOn"), d.copy(rumbleOn = RumbleOn.Off).differences(d))
        assertEquals(setOf("RumbleStrength"), d.copy(rumbleStrength = 50).differences(d))
        assertEquals(setOf("Pak"), d.copy(gbGame = gold).differences(d))
    }

    @Test fun transferPakIsTheCoresValue() {
        assertEquals("transfer", Pak.Transfer.core)
    }

    @Test fun profilesKeepRumbleNotThePak() {
        val d = ControlsSet.defaults().players[0]
        val prof = Profile("x", 0, "pad", d.copy(rumbleOn = RumbleOn.Off, rumbleStrength = 30, pak = Pak.Transfer, gbGame = gold))
        val out = Profile.applyTo(d, prof)
        assertEquals(RumbleOn.Off, out.rumbleOn)
        assertEquals(30, out.rumbleStrength)
        assertEquals(d.pak, out.pak)
        assertNull(out.gbGame)
    }

    @Test fun gameBoyNames() {
        assertEquals("Pokémon Gold (USA)", GameBoyFiles.title("Pokémon Gold (USA).gbc"))
        assertTrue(GameBoyFiles.isGame("a.GBC")); assertTrue(GameBoyFiles.isGame("a.zip")); assertFalse(GameBoyFiles.isGame("a.sav"))
        assertTrue(GameBoyFiles.isSave("a.srm")); assertFalse(GameBoyFiles.isSave("a.gb"))
        assertTrue(GameBoyFiles.isGameBoyFolder("GBC")); assertTrue(GameBoyFiles.isGameBoyFolder("Game Boy")); assertFalse(GameBoyFiles.isGameBoyFolder("n64"))
    }

    @Test fun defaultSaveIsTheSameNameSavFirst() {
        val srm = GameBoyFiles.Found("u1", "Pokémon Gold (USA).srm")
        val sav = GameBoyFiles.Found("u2", "pokémon gold (usa).sav")
        val other = GameBoyFiles.Found("u3", "Pokémon Silver (USA).sav")
        assertEquals(sav, GameBoyFiles.saveFor(gold, listOf(srm, other, sav)))
        assertEquals(srm, GameBoyFiles.saveFor(gold, listOf(srm, other)))
        assertNull(GameBoyFiles.saveFor(gold, listOf(other)))
    }
}
