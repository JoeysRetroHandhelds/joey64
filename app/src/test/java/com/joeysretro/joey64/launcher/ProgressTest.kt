package com.joeysretro.joey64.launcher

import com.joeysretro.joey64.launcher.library.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    @Test fun noGuessAtTheStart() {
        assertNull(Progress(1, 100, startedAt = 0).secondsLeft(1000))
        assertNull(Progress(1, 100, startedAt = 0).secondsLeft(10_000)) // 1% done
        assertEquals("1 of 100", Progress(1, 100, startedAt = 0).text(1000))
    }

    @Test fun guessFromThePace() {
        // A quarter done in 10 s: 30 s to go.
        assertEquals(30L, Progress(25, 100, startedAt = 0).secondsLeft(10_000))
        assertEquals("25 of 100 · about 30 s left", Progress(25, 100, startedAt = 0).text(10_000))
        // By bytes when they're known: 10% of the bytes in 20 s, 180 s left, though half the files are done.
        val p = Progress(50, 100, startedAt = 0, doneBytes = 10, totalBytes = 100)
        assertEquals(180L, p.secondsLeft(20_000))
        assertEquals("50 of 100 · about 3 min left", p.text(20_000))
        assertEquals("99 of 100 · a few seconds left", Progress(99, 100, startedAt = 0).text(10_000))
    }

    @Test fun roundsUpToFiveSeconds() {
        // 50% in 11 s: 11 s left, shown as 15.
        assertEquals("50 of 100 · about 15 s left", Progress(50, 100, startedAt = 0).text(11_000))
    }
}
