package com.joeysretro.joey64.launcher.screens

import com.joeysretro.joey64.launcher.components.Hint
import org.junit.Assert.assertEquals
import org.junit.Test

/** Start: Reset tab's hint (approved mockup B, 2026-11-07): before L/R, else before the last hint. */
class ResetTabTest {
    private val start = Hint("Start", "Reset tab")

    @Test fun goesBeforeTabs() {
        val h = listOf(Hint("A", "Change"), Hint("Y", "Reset to default"), Hint("L/R", "Tab"), Hint("B", "Back"))
        assertEquals(listOf(h[0], h[1], start, h[2], h[3]), withResetHint(h))
    }

    @Test fun withoutTabsGoesBeforeBack() {
        val h = listOf(Hint("A", "Change"), Hint("B", "Back"))
        assertEquals(listOf(h[0], start, h[1]), withResetHint(h))
    }

    @Test fun addedOnce() {
        val once = withResetHint(listOf(Hint("L/R", "Tab"), Hint("B", "Back")))
        assertEquals(once, withResetHint(once))
        assertEquals(emptyList<Hint>(), withResetHint(emptyList()))
    }

    @Test fun changesGo() {
        assertEquals("Its 1 change on this tab goes", changesGo(1, "on this tab"))
        assertEquals("Its 3 changes on this tab go", changesGo(3, "on this tab"))
    }
}
