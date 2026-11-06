package com.joeysretro.joey64.launcher.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plan's layout rules, at the screen sizes of the handhelds we test on (dp, landscape). */
class ScreenLayoutTest {

    @Test fun sixteenByNineIsFourColumnsWithRail() {
        val l = ScreenLayout.of(960f, 540f)   // 1920x1080 at 2x (Odin 3, Thor top)
        assertEquals(ScreenShape.Wide, l.shape)
        assertEquals(4, l.boxArtColumns)
        assertFalse(l.bottomBar)
        assertFalse(l.singleColumnSettings)
    }

    @Test fun phoneTwentyByNineIsWide() {
        assertEquals(4, ScreenLayout.of(915f, 412f).boxArtColumns)
    }

    @Test fun sixteenByTenIsWide() {
        assertEquals(ScreenShape.Wide, ScreenLayout.shapeOf(1280f, 800f))
    }

    @Test fun fourByThreeIsThreeColumns() {
        val l = ScreenLayout.of(640f, 480f)   // Retroid Pocket Flip 2 / Classic style
        assertEquals(ScreenShape.Standard, l.shape)
        assertEquals(3, l.boxArtColumns)
        assertFalse(l.bottomBar)
    }

    @Test fun threeByTwoIsStandard() {
        assertEquals(ScreenShape.Standard, ScreenLayout.shapeOf(720f, 480f))
    }

    @Test fun squareIsTwoColumnsOneColumnSettings() {
        val l = ScreenLayout.of(480f, 480f)
        assertEquals(ScreenShape.Square, l.shape)
        assertEquals(2, l.boxArtColumns)
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
        // A small window (split screen) in landscape keeps the rail but stacks Settings.
        val l = ScreenLayout.of(560f, 300f)
        assertFalse(l.bottomBar)
        assertTrue(l.singleColumnSettings)
    }

    @Test fun mockupFrameIsTheDefault() {
        val l = ScreenLayout.of(832f, 468f)
        assertEquals(4, l.boxArtColumns)
        assertFalse(l.singleColumnSettings)
    }
}
