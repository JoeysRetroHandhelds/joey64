package com.joeysretro.joey64.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaOverlayTest {
    @Test fun aBezelCfgAsLibretroShipsThem() {
        // libretro/overlay-borders' "1080 GCE Vectrex/Full Console Border/Star_Ship.cfg", with its credits lines.
        val text = "overlays = 1\n\noverlay0_overlay = Star_Ship.png\n\noverlay0_full_screen = true\n\noverlay0_descs = 0\n\nCredits\nTHK-Hyperspin\n"
        val cfg = RaOverlay.parse(text, "borders/Star_Ship.cfg")
        assertEquals(listOf(RaOverlay.Image("borders/Star_Ship.png", "")), cfg.images)
        assertFalse(cfg.touch)
    }

    @Test fun quotesCommentsAndRelativeImages() {
        val text = "# a bezel\noverlays = \"2\"\noverlay0_overlay = \"../art/tv land.png\" # landscape\noverlay0_name = \"landscape\"\n" +
            "overlay0_descs = 0\noverlay1_overlay = tv_portrait.png\noverlay1_name = portrait\noverlay1_descs = 0"
        val cfg = RaOverlay.parse(text, "N64/bezels/tv.cfg")
        assertEquals(listOf("N64/art/tv land.png", "N64/bezels/tv_portrait.png"), cfg.images.map { it.path })
        assertEquals("N64/art/tv land.png", RaOverlay.pick(cfg, portrait = false)?.path)
        assertEquals("N64/bezels/tv_portrait.png", RaOverlay.pick(cfg, portrait = true)?.path)
    }

    @Test fun touchGamepadsAreToldApart() {
        val text = "overlays = 1\noverlay0_overlay = pad.png\noverlay0_full_screen = true\noverlay0_descs = 2\n" +
            "overlay0_desc0 = \"a,0.9,0.8,radial,0.05,0.05\"\noverlay0_desc1 = \"b,0.8,0.9,radial,0.05,0.05\""
        assertTrue(RaOverlay.parse(text, "gamepads/neo/neo.cfg").touch)
    }

    @Test fun anImageOutsideThePickedFolderIsLeftOut() {
        assertTrue(RaOverlay.parse("overlays = 1\noverlay0_overlay = ../../x.png", "a.cfg").images.isEmpty())
    }

    @Test fun valuesAndNames() {
        assertEquals("Retro TV", RaOverlay.name("raov:overlays/borders/Retro TV.cfg"))
        assertEquals("RetroArch: Retro TV", Picture.raLabel("raov:overlays/borders/Retro TV.cfg"))
        assertEquals("RetroArch: each game", Picture.raLabel("match:N64"))
        assertEquals("RetroArch overlay", Picture.raLabel(""))
    }

    private fun alpha(w: Int, h: Int, clear: (Int, Int) -> Boolean) = IntArray(w * h) { if (clear(it % w, it / w)) 0 else 255 }

    @Test fun theHoleIsTheClearPartAroundTheMiddle() {
        // A 160x90 frame with a clear 80x60 hole at 40,15, and a clear speck in a corner that isn't the hole.
        val a = alpha(160, 90) { x, y -> (x in 40 until 120 && y in 15 until 75) || (x < 3 && y < 3) }
        assertEquals(RaOverlay.Hole(2500, 1666, 7500, 8333), RaOverlay.hole(a, 160, 90))
    }

    @Test fun noHoleWithoutAFrame() {
        assertNull(RaOverlay.hole(alpha(40, 30) { _, _ -> true }, 40, 30))        // clear everywhere
        assertNull(RaOverlay.hole(alpha(40, 30) { _, _ -> false }, 40, 30))       // solid in the middle
        assertNull(RaOverlay.hole(alpha(40, 30) { x, y -> x in 19..20 && y in 14..15 }, 40, 30)) // a speck
    }

    @Test fun theGameFitsTheHoleAtItsShape() {
        // A 4:3 hole in the middle half of a 1920x1080 image, on a 1920x1080 screen.
        val p = RaOverlay.placeIn(RaOverlay.Hole(3125, 2500, 6875, 7500), 1920, 1080, 1920, 1080)
        assertEquals(Triple(312, 250, 375), p)
        // A wide hole: the game as tall as it, centred across.
        val q = RaOverlay.placeIn(RaOverlay.Hole(0, 2500, 10000, 7500), 1920, 1080, 1920, 1080)
        assertEquals(Triple(312, 250, 375), q)
        // The same image covering a 16:10 screen is cut at the sides.
        val r = RaOverlay.placeIn(RaOverlay.Hole(3125, 2500, 6875, 7500), 1920, 1080, 1728, 1080)
        assertEquals(Triple(291, 250, 416), r)
    }

    @Test fun gamesMatchOverlaysByName() {
        val cfgs = listOf("GameBezels/N64/Super Mario 64 (USA).cfg", "GameBezels/N64/Banjo-Kazooie (USA).cfg", "GameBezels/N64/Dr. Mario 64 (USA).cfg")
        assertEquals(cfgs[0], RaOverlay.match(listOf("Super Mario 64 (USA)"), cfgs))
        assertEquals(cfgs[0], RaOverlay.match(listOf("super mario 64 (usa).z64"), cfgs))
        // Another region's copy gets the one named for the same game.
        assertEquals(cfgs[1], RaOverlay.match(listOf("Banjo-Kazooie (Europe) (En,Fr,De)"), cfgs))
        assertEquals(cfgs[2], RaOverlay.match(listOf("Dr. Mario 64 (USA)"), cfgs))
        assertNull(RaOverlay.match(listOf("Wave Race 64 (USA)"), cfgs))
    }

    @Test fun theHostGetsModeFourWithTheImageAndPlace() {
        val v = VideoSettings(mapOf(VideoKey.Sides to "retroarch", VideoKey.SidesRa to "raov:o/tv.cfg"))
        val n = Picture.native(v, ShaderSetup(emptyList()), Picture.RaUse("/x/tv.png", Triple(312, 250, 375)))
        assertEquals(4, n.sides); assertEquals("/x/tv.png", n.sidesImage)
        assertEquals(312, n.placeX); assertEquals(250, n.placeY); assertEquals(375, n.placeW)
        // Not ready yet: nothing beside the picture.
        assertEquals(0, Picture.native(v, ShaderSetup(emptyList())).sides)
        // Match each game, a game without one: Games without one.
        val m = VideoSettings(mapOf(VideoKey.Sides to "retroarch", VideoKey.SidesRa to "match:o", VideoKey.SidesRaOther to "frame"))
        assertEquals(2, Picture.native(m, ShaderSetup(emptyList())).sides)
        assertEquals(1, Picture.native(VideoSettings(mapOf(VideoKey.Sides to "retroarch", VideoKey.SidesRa to "match:o")), ShaderSetup(emptyList())).sides)
    }
}
