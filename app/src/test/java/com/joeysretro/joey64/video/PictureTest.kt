package com.joeysretro.joey64.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PictureTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun v(vararg kv: Pair<VideoKey, String>) = VideoSettings(kv.toMap())
    private val noPresets: (String) -> ShaderPreset? = { null }

    @Test fun autoIsSharpPixelsOnlyWhenPixelsWouldBeUneven() {
        assertTrue(Picture.autoSharp(1, 1080))   // 4.5 times 240
        assertFalse(Picture.autoSharp(1, 960))   // 4 times
        assertFalse(Picture.autoSharp(1, 720))   // 3 times
        assertFalse(Picture.autoSharp(2, 1080))  // upscaled: None
        assertEquals(listOf("sharp"), Picture.setup(VideoSettings.DEFAULTS, noPresets, autoSharp = true).stack)
        assertTrue(Picture.setup(VideoSettings.DEFAULTS, noPresets, autoSharp = false).stack.isEmpty())
        assertEquals("Sharp pixels on this screen", Picture.autoLabel(true))
        assertEquals("None on this screen", Picture.autoLabel(false))
    }

    @Test fun theDefaultsSendNothingButAutosSharpPixels() {
        val n = Picture.native(VideoSettings.DEFAULTS, Picture.setup(VideoSettings.DEFAULTS, noPresets, false))
        assertArrayEquals(IntArray(4), n.ids)
        assertEquals(0, n.sides); assertEquals(0, n.overlay); assertEquals(0, n.colourBlind)
        assertNull(n.sidesImage); assertNull(n.overlayImage)
    }

    @Test fun aStackWithItsSettingsReachesTheHost() {
        val s = v(VideoKey.Shader to "ntsc+crt_heavy", VideoKey.ShaderParams to "crt_heavy=40,1,2,0,5", VideoKey.ShaderScale to "3")
        val setup = Picture.setup(s, noPresets, true)
        assertEquals(listOf("ntsc", "crt_heavy"), setup.stack)
        assertEquals("NTSC composite + CRT heavy", setup.label)
        assertEquals("NTSC composite + CRT heavy", Picture.rowLabel(s))
        val n = Picture.native(s, setup)
        assertArrayEquals(intArrayOf(6, 4, 0, 0), n.ids)
        assertEquals(listOf(50, 50), n.params.slice(0..1))          // NTSC's defaults
        assertEquals(listOf(40, 1, 2, 0, 5), n.params.slice(8..12))  // CRT heavy's own
        assertEquals(3, n.scale)
    }

    @Test fun crtHeavyDefaultsAreTheMockups() {
        assertEquals(listOf(60, 0, 1, 20, 10), Shaders.CRT_HEAVY.defaults)
        assertEquals(listOf("Scanlines", "Mask", "Curve", "Glow", "Brightness"), Shaders.CRT_HEAVY.params.map { it.label })
        assertEquals("Aperture grille", Shaders.CRT_HEAVY.params[1].label(0))
        assertEquals("Low", Shaders.CRT_HEAVY.params[2].label(1))
        assertEquals("+10%", Shaders.CRT_HEAVY.params[4].label(10))
        assertEquals(listOf("Scanlines", "Glow"), Shaders.CRT_LIGHT.params.map { it.label })
        assertEquals(listOf("Grid", "Ghosting"), Shaders.LCD.params.map { it.label })
        assertEquals(listOf("Blur", "Colour bleed"), Shaders.NTSC.params.map { it.label })
        assertTrue(Shaders.SHARP.params.isEmpty() && Shaders.SMOOTH.params.isEmpty())
        for (d in Shaders.BUILT_IN) assertTrue(d.params.size <= 8)
    }

    @Test fun shaderIdsMatchLookGlsl() {
        val glsl = File("src/main/cpp/shaders/look.glsl").readText()
        val ids = Regex("""#define SH_(\w+) (\d+)u""").findAll(glsl).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(mapOf("SHARP" to 1, "SMOOTH" to 2, "CRT_LIGHT" to 3, "CRT_HEAVY" to 4, "LCD" to 5, "NTSC" to 6), ids)
        for (d in Shaders.BUILT_IN) assertEquals(d.label, ids[d.id.uppercase()], d.nativeId)
    }

    @Test fun paramsRoundTripAndUnknownShadersAreDropped() {
        val m = Picture.paramsOf("lcd=50,30;bogus=1,2;crt_heavy=60,0,1,20,10")
        assertEquals(setOf("lcd", "crt_heavy"), m.keys)
        assertEquals(m, Picture.paramsOf(Picture.paramsString(m)))
        assertEquals(listOf("ntsc", "lcd"), Picture.stackOf("ntsc+nope+lcd"))
        assertEquals(4, Picture.stackOf("sharp+smooth+lcd+ntsc+crt_heavy").size)
    }

    @Test fun sidesOverlayColourBlindAndPlace() {
        val s = v(VideoKey.Sides to "image", VideoKey.SidesImage to "bezel.png", VideoKey.SidesPlace to "240,130,520",
            VideoKey.Overlay to "image", VideoKey.OverlayImage to "grid.png", VideoKey.OverlayOpacity to "30", VideoKey.ColourBlind to "green")
        val n = Picture.native(s, ShaderSetup(emptyList()))
        assertEquals(3, n.sides); assertEquals("bezel.png", n.sidesImage)
        assertEquals(240, n.placeX); assertEquals(130, n.placeY); assertEquals(520, n.placeW)
        assertEquals(30, n.overlay); assertEquals("grid.png", n.overlayImage)
        assertEquals(2, n.colourBlind)
        assertEquals("Left 24% · Top 13% · Width 52%", Picture.placeLabel(240, 130, 520))
        // Your own image picked but no file: no sides; the frame: the generated picture.
        assertEquals(0, Picture.native(v(VideoKey.Sides to "image"), ShaderSetup(emptyList())).sides)
        val frame = Picture.native(v(VideoKey.Sides to "frame"), ShaderSetup(emptyList()))
        assertEquals(2, frame.sides); assertEquals(Picture.FRAME, frame.sidesImage)
        // The overlay at 0% or Off sends nothing.
        assertEquals(0, Picture.native(v(VideoKey.Overlay to "off", VideoKey.OverlayImage to "grid.png"), ShaderSetup(emptyList())).overlay)
    }

    @Test fun sidesRowSaysNoneAtWideShapes() {
        val s = v(VideoKey.Sides to "blur")
        assertEquals("Blurred game", Picture.sidesLabel(s, false))
        assertEquals("None at this shape", Picture.sidesLabel(VideoSettings(s.values + (VideoKey.Aspect to "stretch")), false))
        assertEquals("None at this shape", Picture.sidesLabel(s, widescreenOn = true))
        assertEquals("Blurred game", Picture.sidesLabel(VideoSettings(s.values + (VideoKey.Aspect to "4:3")), true))
    }

    @Test fun presetsSaveRenameAndDelete() {
        val dir = tmp.newFolder()
        val store = PictureStore(dir)
        val video = VideoStore(dir)
        val setup = ShaderSetup(listOf("crt_heavy"), mapOf("crt_heavy" to listOf(70, 1, 2, 30, 15)), "2")
        store.save(ShaderPreset("Zelda CRT", setup))
        assertEquals(setup, store.preset("zelda crt")!!.setup)
        video.saveAll(mapOf(VideoKey.Shader to "preset:Zelda CRT"))
        video.saveGame("Zelda", mapOf(VideoKey.Shader to "preset:Zelda CRT"))
        val s = video.forGame("Zelda")
        assertEquals("Zelda CRT", Picture.rowLabel(s))
        assertEquals(listOf(70, 1, 2, 30, 15), Picture.setup(s, store::preset, false).paramsOf("crt_heavy"))
        assertTrue(store.rename("Zelda CRT", "Night CRT", video))
        assertEquals("preset:Night CRT", video.all()[VideoKey.Shader])
        assertEquals("preset:Night CRT", video.game("Zelda")!![VideoKey.Shader])
        store.save(ShaderPreset("Other", ShaderSetup(listOf("lcd"))))
        assertFalse(store.rename("Night CRT", "other", video)) // taken, any case
        store.remove("Night CRT")
        assertEquals(listOf("Other"), store.presets().map { it.name })
        // A row set to a deleted preset is Auto.
        assertEquals(listOf("sharp"), Picture.setup(video.forGame("Zelda"), store::preset, true).stack)
    }

    @Test fun nextShaderSteps() {
        val list = Picture.cycle(listOf("Zelda CRT"))
        assertEquals(9, list.size) // Auto, None, six built-ins, a preset: "6 of 9" is CRT heavy
        assertEquals("crt_heavy", list[5])
        assertEquals("CRT heavy", Picture.valueLabel(list[5]))
        assertEquals("Zelda CRT", Picture.valueLabel(list[8]))
    }

    @Test fun previewDrawsWhatItIsGiven() {
        val pic = LookPreview.builtIn()
        val plain = LookPreview.render(pic, ShaderSetup(emptyList()), 64, 48, 0, 0, 64, 48)
        val crt = LookPreview.render(pic, ShaderSetup(listOf("crt_heavy")), 64, 48, 0, 0, 64, 48)
        assertEquals(64 * 48, plain.px.size)
        assertFalse(plain.px.contentEquals(crt.px))
        // Blurred sides fill the bars beside a 4:3 picture.
        val wide = LookPreview.render(pic, ShaderSetup(emptyList()), 85, 48, 10, 0, 64, 48, sides = 1)
        assertTrue((wide.px[20 * 85 + 2] and 0xFFFFFF) != 0)
        // Colour-blind help leaves grey alone.
        val g = LookPreview.colourBlind(0.5f, 0.5f, 0.5f, 2)
        assertEquals(0.5f, g[1], 0.01f); assertEquals(0.5f, g[2], 0.01f)
    }
}
