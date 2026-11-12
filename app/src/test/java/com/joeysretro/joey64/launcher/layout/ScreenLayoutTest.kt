package com.joeysretro.joey64.launcher.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plan's layout rules, at the screen sizes of the handhelds we test on (dp, landscape). */
class ScreenLayoutTest {

    @Test fun sixteenByNineIsFiveColumnsUnderTheTopBar() {
        val l = ScreenLayout.of(960f, 540f)   // 1920x1080 at 2x (Odin 3, Thor top)
        assertEquals(ScreenShape.Wide, l.shape)
        assertEquals(5, l.boxArtColumns)
        assertFalse(l.bottomBar)
        assertFalse(l.singleColumnSettings)
    }

    @Test fun phoneTwentyByNineIsWide() {
        assertEquals(5, ScreenLayout.of(915f, 412f).boxArtColumns)
    }

    @Test fun sixteenByTenIsWide() {
        assertEquals(ScreenShape.Wide, ScreenLayout.shapeOf(1280f, 800f))
    }

    @Test fun fourByThreeIsFourColumns() {
        val l = ScreenLayout.of(640f, 480f)   // Retroid Pocket Flip 2 / Classic style
        assertEquals(ScreenShape.Standard, l.shape)
        assertEquals(4, l.boxArtColumns)
        assertFalse(l.bottomBar)
        // 640 dp: RG477M / RG476H / RP Nova (1280x960 at 2x) and RG405M (640x480 at 1x) stack Settings.
        assertTrue(l.singleColumnSettings)
        assertFalse(ScreenLayout.of(731f, 549f).singleColumnSettings)
    }

    @Test fun threeByTwoIsStandard() {
        assertEquals(ScreenShape.Standard, ScreenLayout.shapeOf(720f, 480f))
    }

    @Test fun squareIsThreeColumnsOneColumnSettings() {
        val l = ScreenLayout.of(480f, 480f)
        assertEquals(ScreenShape.Square, l.shape)
        assertEquals(3, l.boxArtColumns)
        assertFalse(l.bottomBar)
        assertTrue(l.singleColumnSettings)
    }

    @Test fun portraitIsTwoColumnsWithBottomBar() {
        val l = ScreenLayout.of(412f, 915f)
        assertEquals(ScreenShape.Portrait, l.shape)
        assertEquals(2, l.boxArtColumns)
        assertTrue(l.bottomBar)
        assertTrue(l.singleColumnSettings)
    }

    @Test fun narrowLandscapeStacksSettings() {
        // A small window (split screen) in landscape keeps the top bar but stacks Settings.
        val l = ScreenLayout.of(560f, 300f)
        assertFalse(l.bottomBar)
        assertTrue(l.singleColumnSettings)
    }

    @Test fun mockupFrameIsTheDefault() {
        val l = ScreenLayout.of(832f, 468f)
        assertEquals(5, l.boxArtColumns)
        assertFalse(l.singleColumnSettings)
    }
    @Test fun thorBottomScreenIsOneColumn() {
        // 1240x1080 at 369 dpi, the whole window.
        val l = ScreenLayout.of(538f, 468f)
        assertEquals(ScreenShape.Square, l.shape)
        assertTrue(l.singleColumnSettings)
        assertEquals(3, l.descriptionLines)
        assertEquals(3, l.boxArtColumns)
        assertEquals(2, ScreenLayout.of(832f, 468f).descriptionLines)
    }

    @Test fun devSizes() {
        assertEquals(512f to 384f, DevScreen.parse("512x384"))
        assertEquals(null, DevScreen.parse("off"))
        assertEquals(null, DevScreen.parse("100x100"))
    }
}
