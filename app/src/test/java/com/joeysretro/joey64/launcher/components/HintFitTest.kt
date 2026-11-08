package com.joeysretro.joey64.launcher.components

import org.junit.Assert.assertEquals
import org.junit.Test

class HintFitTest {
    @Test fun allFitAtTheNormalGap() {
        assertEquals(HintFit.Fit(listOf(0, 1, 2), 18), HintFit.fit(listOf(100, 100, 100), 336, 18, 10))
    }

    @Test fun tighterGapBeforeDropping() {
        assertEquals(HintFit.Fit(listOf(0, 1, 2), 10), HintFit.fit(listOf(100, 100, 100), 320, 18, 10))
    }

    @Test fun middleHintsGoFromTheRightFirstAndLastStay() {
        // A Change, Y Reset, Start Reset tab, L/R Tab, B Back on a narrow line.
        assertEquals(HintFit.Fit(listOf(0, 1, 4), 10), HintFit.fit(listOf(80, 120, 130, 60, 60), 300, 18, 10))
    }

    @Test fun neverFewerThanFirstAndLast() {
        assertEquals(listOf(0, 3), HintFit.fit(listOf(200, 200, 200, 200), 100, 18, 10).indices)
    }
}
