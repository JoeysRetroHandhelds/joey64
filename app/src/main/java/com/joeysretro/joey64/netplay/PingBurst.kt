package com.joeysretro.joey64.netplay

/**
 * A ping that shows the link's own time. A phone's Wi-Fi dozes between packets that come once a second, which adds
 * 100-300 ms (2026-11-11: the Wi-Fi list said 211 ms for the Odin 2 Portal's room, whose game then ran at 10 ms with
 * the Duo), so each round sends [COUNT] pings [GAP_MS] apart: the first wakes the radio, and the round's best shows.
 */
class PingBurst {
    private var round = Long.MIN_VALUE
    private var best: Int? = null
    private var finished = false

    /** What to show: the last finished round's best (the first round's best so far until one has finished). */
    @Volatile var shown: Int? = null
        private set

    /** A new round, its first ping sent at [t]. */
    @Synchronized fun start(t: Long) {
        best?.let { shown = it; finished = true }
        round = t
        best = null
    }

    /** The answer to the ping sent at [sentAt], heard at [now]; what to show now. */
    @Synchronized fun answer(sentAt: Long, now: Long): Int? {
        val ms = (now - sentAt).toInt().coerceAtLeast(1)
        if (sentAt < round) { // an earlier round's late answer: only when there's nothing to show
            if (shown == null) shown = ms
            return shown
        }
        val b = best?.let { minOf(it, ms) } ?: ms
        best = b
        if (!finished) shown = b
        return shown
    }

    companion object {
        const val COUNT = 3
        const val GAP_MS = 15L
    }
}
