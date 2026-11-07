package com.joeysretro.joey64.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaultsAreTodaysPicture() {
        val v = VideoSettings.DEFAULTS
        assertEquals(1, v.int(VideoKey.Resolution))
        assertEquals("original", v.look)
        val a = VideoApply.of(v, parallel = true)
        // The core's own defaults, so a user who never opens Video plays as before.
        assertEquals("1x", a.coreOptions["mupen64plus-parallel-rdp-upscaling"])
        assertEquals("True", a.coreOptions["mupen64plus-parallel-rdp-vi-aa"])
        assertEquals("True", a.coreOptions["mupen64plus-parallel-rdp-native-tex-rect"])
        assertEquals("disable", a.coreOptions["mupen64plus-parallel-rdp-downscaling"])
        assertEquals("Bob", a.coreOptions["mupen64plus-parallel-rdp-deinterlace-method"])
        assertTrue(a.env.isEmpty())
        assertFalse(a.sharpScale)
        assertEquals(4 to 3, VideoApply.aspect(v, widescreenOn = false))
    }

    @Test fun gameKeysOverAllGames() {
        val all = mapOf(VideoKey.Resolution to "2", VideoKey.Aspect to "4:3")
        val game = mapOf(VideoKey.Resolution to "4")
        val v = VideoSettings.resolve(all, game)
        assertEquals(4, v.int(VideoKey.Resolution))
        assertEquals("4:3", v[VideoKey.Aspect])
        assertEquals("off", v[VideoKey.Integer])
    }

    @Test fun lookPresetsMatchTheDevSwitches() {
        val sharp = VideoSettings(VideoSettings.LOOKS.getValue("sharp"))
        assertEquals("sharp", sharp.look)
        val a = VideoApply.of(sharp, parallel = true)
        assertEquals("False", a.coreOptions["mupen64plus-parallel-rdp-vi-aa"])
        assertEquals("False", a.coreOptions["mupen64plus-parallel-rdp-vi-bilinear"])
        assertEquals("True", a.coreOptions["mupen64plus-parallel-rdp-dither-filter"])
        assertTrue(a.sharpScale)
        val sharper = VideoSettings(VideoSettings.LOOKS.getValue("sharper"))
        assertEquals("False", VideoApply.of(sharper, true).coreOptions["mupen64plus-parallel-rdp-dither-filter"])
        assertEquals("custom", VideoSettings(mapOf(VideoKey.Divot to "off")).look)
    }

    @Test fun glidenGetsItsResolutionFactorOnly() {
        val a = VideoApply.of(VideoSettings(mapOf(VideoKey.Resolution to "2")), parallel = false)
        assertEquals(mapOf("mupen64plus-EnableNativeResFactor" to "2"), a.coreOptions)
        assertFalse(a.sharpScale)
    }

    @Test fun pacingAndThermalGoToTheEnvironment() {
        assertEquals("1", VideoApply.of(VideoSettings(mapOf(VideoKey.Pacing to "steady")), true).env["N64_SWAP_INTERVAL"])
        assertEquals("2", VideoApply.of(VideoSettings(mapOf(VideoKey.Pacing to "timed")), true).env["N64_SWAP_INTERVAL"])
        assertEquals("1", VideoApply.of(VideoSettings(mapOf(VideoKey.Thermal to "on")), true).env["N64_THERMAL_GOV"])
    }

    @Test fun aspectChoices() {
        fun asp(s: String, ws: Boolean = false) = VideoApply.aspect(VideoSettings(mapOf(VideoKey.Aspect to s)), ws)
        assertEquals(16 to 9, asp("auto", ws = true))
        assertEquals(16 to 9, asp("16:9"))
        assertEquals(4 to 3, asp("4:3", ws = true))
        assertEquals(0 to 0, asp("stretch"))
    }

    @Test fun storeKeepsOnlyAGamesOwnKeys() {
        val store = VideoStore(tmp.root)
        store.saveAll(mapOf(VideoKey.Resolution to "2"))
        store.saveGame("Mario Kart 64 (USA)", mapOf(VideoKey.Aspect to "16:9"))
        assertEquals(listOf("Mario Kart 64 (USA)"), store.gamesWithOwn())
        val v = store.forGame("Mario Kart 64 (USA)")
        assertEquals(2, v.int(VideoKey.Resolution))
        assertEquals("16:9", v[VideoKey.Aspect])
        assertEquals(1, store.game("Mario Kart 64 (USA)")!!.size)
        // Using All games for the last changed key leaves nothing of the game's own.
        store.saveGame("Mario Kart 64 (USA)", emptyMap())
        assertNull(store.game("Mario Kart 64 (USA)"))
        assertTrue(store.gamesWithOwn().isEmpty())
        assertEquals(2, store.forGame(null).int(VideoKey.Resolution))
    }

    @Test fun unknownKeysAreIgnored() {
        val o = org.json.JSONObject("""{"version":1,"resolution":"4","nope":"x"}""")
        assertEquals(mapOf(VideoKey.Resolution to "4"), VideoSettings.fromJson(o))
    }
}
