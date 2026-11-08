package com.joeysretro.joey64.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaultsAreTodaysSound() {
        val a = AudioSettings.DEFAULTS
        assertEquals(100, a.volume)
        assertFalse(a.on(AudioKey.Mute))
        assertFalse(a.on(AudioKey.Mono))
        assertEquals(0, a.ffMode)      // fast-forward muted, as before
        assertEquals(0, a.latencyMode) // Auto: today's adaptive target
    }

    @Test fun aGameKeepsOnlyVolumeAndLatency() {
        val all = mapOf(AudioKey.Volume to "80", AudioKey.Mono to "on")
        val game = mapOf(AudioKey.Volume to "50", AudioKey.Mono to "off", AudioKey.Latency to "safe")
        val a = AudioSettings.resolve(all, game)
        assertEquals(50, a.volume)
        assertEquals(2, a.latencyMode)
        assertTrue(a.on(AudioKey.Mono)) // the game's Mono is ignored: same for every game
    }

    @Test fun storeDropsKeysAGameCantHave() {
        val store = AudioStore(tmp.root)
        store.saveGame("Mario Kart 64 (USA)", mapOf(AudioKey.Volume to "70", AudioKey.Mute to "on"))
        assertEquals(mapOf(AudioKey.Volume to "70"), store.game("Mario Kart 64 (USA)"))
        assertEquals(listOf("Mario Kart 64 (USA)"), store.gamesWithOwn())
        store.saveGame("Mario Kart 64 (USA)", mapOf(AudioKey.Mute to "on"))
        assertNull(store.game("Mario Kart 64 (USA)"))
        assertTrue(store.gamesWithOwn().isEmpty())
    }

    @Test fun allGamesRoundTrips() {
        val store = AudioStore(tmp.root)
        val m = mapOf(AudioKey.Volume to "35", AudioKey.FfSound to "quieter", AudioKey.Apps to "on")
        store.saveAll(m)
        assertEquals(m, store.all())
        val a = store.forGame(null)
        assertEquals(35, a.volume)
        assertEquals(1, a.ffMode)
        assertTrue(a.on(AudioKey.Apps))
    }

    @Test fun labels() {
        assertEquals("100%", AudioChoices.label(AudioKey.Volume, "100"))
        assertEquals("Quieter", AudioChoices.label(AudioKey.FfSound, "quieter"))
        assertEquals("Safe", AudioChoices.label(AudioKey.Latency, "safe"))
    }
}
