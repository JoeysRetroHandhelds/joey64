package com.joeysretro.joey64.netplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingBurstTest {
    @Test fun aRoundShowsItsBest() {
        val p = PingBurst()
        assertNull(p.shown)
        p.start(1000)
        // the first ping waits for the dozing radio, the next two don't
        assertEquals(210, p.answer(1000, 1210))
        assertEquals(12, p.answer(1015, 1027))
        assertEquals(11, p.answer(1030, 1041))
    }

    @Test fun laterRoundsShowTheLastFinishedRound() {
        val p = PingBurst()
        p.start(1000); p.answer(1000, 1200); p.answer(1015, 1025)
        p.start(2000)
        assertEquals(10, p.shown)
        // the new round's slow first answer doesn't show while it runs
        assertEquals(10, p.answer(2000, 2190))
        assertEquals(10, p.answer(2015, 2030))
        p.start(3000)
        assertEquals(15, p.shown)
    }

    @Test fun aRoundWithNoAnswerKeepsTheLastOne() {
        val p = PingBurst()
        p.start(1000); p.answer(1000, 1020)
        p.start(2000)
        p.start(3000)
        assertEquals(20, p.shown)
    }

    @Test fun lateAnswersOnlyFillAGap() {
        val p = PingBurst()
        p.start(1000)
        p.start(2000)
        assertEquals(1300, p.answer(1000, 2300))
        assertEquals(8, p.answer(2000, 2008))
        val q = PingBurst()
        q.start(1000); q.answer(1000, 1009)
        q.start(2000)
        assertEquals(9, q.answer(1015, 2100))
    }

    @Test fun neverUnderOneMillisecond() {
        val p = PingBurst()
        p.start(1000)
        assertEquals(1, p.answer(1000, 1000))
    }
}
