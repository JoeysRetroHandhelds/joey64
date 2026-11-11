package com.joeysretro.joey64.launcher.update

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdatesRetryTest {
    @Test fun waitDoublesThenStaysAtFifteenMinutes() {
        val minutes = (1..8).map { AppUpdates.retryWait(it) / 60_000L }
        assertEquals(listOf(1L, 2L, 4L, 8L, 15L, 15L, 15L, 15L), minutes)
    }
}
