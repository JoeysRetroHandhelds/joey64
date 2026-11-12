package com.joeysretro.joey64.netplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingBurstTest {
    @Test fun theFirstRoundShowsItsBestSoFar() {
        val p = PingBurst()
        assertNull(p.shown)
        p.start(1000)
        assertEquals(210, p.answer(1000, 1210))
        assertEquals(12, p.answer(1015, 1027))
        assertEquals(11, p.answer(1030, 1041))
    }

    @Test fun showsTheLowestOfTheLastFiveRounds() {
        val p = PingBurst()
        // the rounds' bests: 10, 197, 95, 87, 120, 150, 160
        var t = 1000L
        for (b in listOf(10, 197, 95, 87, 120, 150, 160)) { p.start(t); p.answer(t, t + b); t += 1000 }
        p.start(t)
        // the last five finished: 95, 87, 120, 150, 160
        assertEquals(87, p.shown)
        // a running round's slow answer doesn't show
        assertEquals(87, p.answer(t, t + 300))
    }

    @Test fun aLowRoundShowsAtOnceItFinishes() {
        val p = PingBurst()
        p.start(1000); p.answer(1000, 1200)
        p.start(2000); p.answer(2015, 2025)
        assertEquals(200, p.shown)
        p.start(3000)
        assertEquals(10, p.shown)
    }

    @Test fun aRoundWithNoAnswerKeepsWhatShows() {
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
