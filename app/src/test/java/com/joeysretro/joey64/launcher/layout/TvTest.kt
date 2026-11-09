package com.joeysretro.joey64.launcher.layout

import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.layout.RemoteKeys.OkHold.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class TvTest {
    /** Mockup screen 4: the library's hints with the remote. */
    @Test fun libraryHintsForTheRemote() {
        assertEquals(listOf(Hint("OK", "Play"), Hint("Hold OK", "Options")),
            RemoteKeys.hints(listOf(Hint("A", "Play"), Hint("Start", "Options"), Hint("Y", "Search"))))
    }

    /** Mockup screen 5: a popup's. Keys a remote doesn't have go; the D-pad's arrows stay. */
    @Test fun popupHintsForTheRemote() {
        assertEquals(listOf(Hint("OK", "Select"), Hint("Back", "Close")), RemoteKeys.hints(listOf(Hint("A", "Select"), Hint("B", "Close"))))
        assertEquals(listOf(Hint("◀▶", "Adjust"), Hint("Back", "Back")),
            RemoteKeys.hints(listOf(Hint("◀▶", "Adjust"), Hint("Y", "Reset to default"), Hint("L/R", "Player"), Hint("B", "Back"))))
    }

    @Test fun shortOkIsAPressOnRelease() {
        val h = RemoteKeys.OkHold(longMs = 500)
        assertEquals(Step.Nothing, h.step(true, 0, 1000, 1000, false, false))
        assertEquals(Step.Click, h.step(false, 0, 1000, 1100, false, false))
    }

    @Test fun heldOkIsOptionsOnceAndNoPress() {
        val h = RemoteKeys.OkHold(longMs = 500)
        h.step(true, 0, 1000, 1000, false, false)
        assertEquals(Step.Nothing, h.step(true, 1, 1000, 1400, false, false))   // a repeat before the long-press time
        assertEquals(Step.Hold, h.step(true, 2, 1000, 1550, false, false))
        assertEquals(Step.Nothing, h.step(true, 3, 1000, 1600, false, false))   // once only
        assertEquals(Step.Nothing, h.step(false, 0, 1000, 1700, false, false))  // and no press on release
        // adb's `input keyevent --longpress`: one repeat with the long-press flag.
        h.step(true, 0, 2000, 2000, false, false)
        assertEquals(Step.Hold, h.step(true, 1, 2000, 2001, true, false))
    }

    @Test fun aReleaseWithoutItsPressIsIgnored() {
        val h = RemoteKeys.OkHold(longMs = 500)
        assertEquals(Step.Nothing, h.step(false, 0, 5000, 5100, false, false)) // pressed in the game, let go here
        h.step(true, 0, 6000, 6000, false, false)
        assertEquals(Step.Nothing, h.step(false, 0, 6000, 6100, false, true))  // cancelled
    }
}
