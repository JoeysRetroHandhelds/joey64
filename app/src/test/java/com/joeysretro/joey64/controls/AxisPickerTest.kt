package com.joeysretro.joey64.controls

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AxisPickerTest {
    private val x = MotionEvent.AXIS_X
    private val y = MotionEvent.AXIS_Y
    private val z = MotionEvent.AXIS_Z

    private fun AxisPicker.at(t: Long, sx: Float = 0f, sy: Float = 0f, sz: Float = 0f, lt: Float = 0f, rt: Float = 0f) =
        feed(mapOf(x to sx, y to sy, z to sz), lt, rt, t)

    @Test fun aPushIsGivenOnceLetGo() {
        val p = AxisPicker()
        assertNull(p.at(0))
        assertNull(p.at(10, sz = 0.9f))
        assertNull(p.tick(200)) // chosen, still pushed
        assertEquals(PadInput.Axis(z, true), p.at(220, sz = 0.05f))
        assertFalse(p.waiting)
    }

    @Test fun aStickAlreadyOffCentreIsNotTaken() {
        val p = AxisPicker()
        // A thumb on the right stick when the popup opened: never seen at rest, so the push doesn't count.
        assertNull(p.at(0, sz = -0.8f))
        assertNull(p.at(50, sz = -1f))
        assertNull(p.tick(500))
        assertFalse(p.waiting)
        // The left stick, at rest from the start, is taken.
        assertNull(p.at(600, sx = 0.95f, sz = -1f))
        p.tick(800)
        assertEquals(PadInput.Axis(x, true), p.at(820, sx = 0f, sz = -1f))
    }

    @Test fun aDiagonalTakesTheAxisThatMovedMost() {
        val p = AxisPicker()
        p.at(0)
        p.at(10, sx = 0.75f, sy = -0.3f)  // X passes the line first
        p.at(40, sx = 0.72f, sy = -0.98f) // but the push was meant up
        p.tick(150)
        assertEquals(PadInput.Axis(y, false), p.at(160))
    }

    @Test fun anInputThatStaysPressedIsDropped() {
        val p = AxisPicker()
        p.at(0)
        p.at(10, sx = -1f)
        p.tick(150)
        assertNull(p.tick(150 + AxisPicker.RELEASE_MS + 1)) // never let go: reads held while untouched
        assertFalse(p.waiting)
        assertNull(p.at(3000, sx = 0f)) // at rest again: can be picked again
        p.at(3010, sx = -1f); p.tick(3200)
        assertEquals(PadInput.Axis(x, false), p.at(3210))
    }

    @Test fun triggersAreTheirButtonsAndHotkeysIgnoreSticks() {
        val p = AxisPicker(sticks = false)
        p.at(0)
        p.at(10, sz = 1f, rt = 0.9f)
        p.tick(150)
        assertEquals(PadInput.Key(KeyEvent.KEYCODE_BUTTON_R2), p.at(160, sz = 1f, rt = 0f))
    }
}
