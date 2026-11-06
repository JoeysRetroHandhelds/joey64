package com.joeysretro.joey64.states

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramePictureTest {
    private fun capture(w: Int, h: Int, pixels: Int = w * h): ByteArray {
        val b = ByteArray(8 + pixels * 4)
        for (i in 0 until 4) { b[i] = (w shr (8 * i)).toByte(); b[4 + i] = (h shr (8 * i)).toByte() }
        return b
    }

    @Test fun readsTheSize() {
        assertEquals(640 to 480, FramePicture.size(capture(640, 480)))
        assertEquals(320 to 240, FramePicture.size(capture(320, 240)))
    }

    @Test fun showsAtFourByThree() {
        assertEquals(640 to 480, FramePicture.shownSize(640, 240))
        assertEquals(320 to 240, FramePicture.shownSize(320, 240))
        assertEquals(640 to 480, FramePicture.shownSize(640, 480))
    }

    @Test fun rejectsWrongLengths() {
        assertNull(FramePicture.size(ByteArray(4)))
        assertNull(FramePicture.size(capture(0, 480, 0)))
        assertNull(FramePicture.size(capture(640, 480, 640 * 479)))
    }
}
