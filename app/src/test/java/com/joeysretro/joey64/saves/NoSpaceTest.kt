package com.joeysretro.joey64.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Not enough space to save (approved problem screens 2026-11-08, screen 5). */
class NoSpaceTest {
    @Test fun fullOnlyWhenTheWriteCantFit() {
        assertTrue(NoSpace.isFull(1024L * 1024, 4L * 1024 * 1024))
        assertFalse(NoSpace.isFull(10L * 1024 * 1024 * 1024, 4L * 1024 * 1024))
        assertFalse(NoSpace.isFull(null, 4L * 1024 * 1024))
    }

    @Test fun textAsDrawn() {
        assertEquals("Slot 3 wasn't saved. Your data folder on the SD card has 1.0 MB free, and a state needs about 4.0 MB. " +
            "Free some space, or change the data folder in Settings › Saves.",
            NoSpace.text(NoSpace.What.State, "Slot 3", "SD card", 1024L * 1024, 4L * 1024 * 1024))
    }
}
