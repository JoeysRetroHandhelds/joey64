package com.joeysretro.joey64.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Saves and Screenshots pages' pure parts (approved mockup 2026-11-08). */
class SaveManagerTest {
    @Test fun emptyAll() = assertEquals("Slots 1-10 are empty", SaveManager.emptySlots(emptySet()))
    @Test fun emptyRun() = assertEquals("Slots 3-10 are empty", SaveManager.emptySlots(setOf(0, 1, 2)))
    @Test fun emptyGaps() = assertEquals("Slots 2, 4-10 are empty", SaveManager.emptySlots(setOf(1, 3, 11)))
    @Test fun emptyOne() = assertEquals("Slot 10 is empty", SaveManager.emptySlots((1..9).toSet()))
    @Test fun emptyNone() = assertNull(SaveManager.emptySlots((1..10).toSet() + SaveManager.RECOVERY))

    @Test fun sizes() {
        assertEquals("300 bytes", SaveManager.sizeLabel(300))
        assertEquals("290 KB", SaveManager.sizeLabel(296_960))
        assertEquals("1.1 MB", SaveManager.sizeLabel(1_153_434))
    }

    @Test fun backupEndings() {
        assertEquals(".srm", SaveManager.backupExt("Mario Tennis (USA)", "Mario Tennis (USA).srm"))
        assertEquals(".ndd.disk_save.zip", SaveManager.backupExt("F-Zero X Expansion (Japan)", "F-Zero X Expansion (Japan).ndd.disk_save"))
    }

    @Test fun screenshotNames() {
        val g = "Mario Tennis (USA)"
        assertTrue(GameScreenshots.isShotOf(g, "$g-261108-211405.png"))
        assertTrue(GameScreenshots.isShotOf(g, "$g-261108-211405-1.png"))
        // Another game whose name starts the same, and files that aren't screenshots.
        assertFalse(GameScreenshots.isShotOf(g, "Mario Tennis (USA) (Rev 1)-261108-211405.png"))
        assertFalse(GameScreenshots.isShotOf(g, "$g.srm"))
        assertFalse(GameScreenshots.isShotOf("Mario.Tennis", "MarioXTennis-261108-211405.png"))
        assertNull(GameScreenshots.stampOf(g, "$g.png"))
        assertTrue(GameScreenshots.stampOf(g, "$g-261108-211405.png") != null)
    }
}
