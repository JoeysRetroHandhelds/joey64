package com.joeysretro.joey64.netplay

/**
 * A ping that shows the time the game will get. Two phones on Wi-Fi that send a packet a second jitter a lot as their
 * radios save power (2026-11-11, Duo and Odin 2 Portal: ICMP 6-239 ms; the Wi-Fi list said 211 ms for a room whose game
 * then ran at 10 ms, with the game's low-latency Wi-Fi lock and 60 packets a second). So each round sends [COUNT] pings
 * [GAP_MS] apart, and the shown time is the lowest round of the last [WINDOW] rounds (a round's best is its lowest).
 */
class PingBurst {
    private var round = Long.MIN_VALUE
    private var best: Int? = null
    private val finished = ArrayDeque<Int>()

    /** What to show: the lowest of the last [WINDOW] finished rounds' bests, or the first round's best so far. */
    @Volatile var shown: Int? = null
        private set

    /** A new round, its first ping sent at [t]. */
    @Synchronized fun start(t: Long) {
        best?.let {
            finished.addLast(it)
            while (finished.size > WINDOW) finished.removeFirst()
            shown = finished.min()
        }
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
        if (finished.isEmpty()) shown = b
        return shown
    }

    companion object {
        const val COUNT = 3
        const val GAP_MS = 15L
        /** Rounds come a second apart: the lowest of the last 5 s. */
        const val WINDOW = 5
    }
}
