package com.joeysretro.joey64.controls

import android.view.KeyEvent

/**
 * Picks the stick direction or trigger a "press a button" capture means (Joey 2026-11-07: Dolphin and
 * NetherSX2 on the Thor sometimes map a stick as a full tilt in another direction). Three guards:
 *  1. An input counts only once it has been seen at rest since the capture opened, so a stick already
 *     off centre (a thumb on it, drift) or a trigger that reads pressed while untouched isn't taken.
 *  2. For [WINDOW_MS] after the first input passes its line, the one that moved most wins, so a
 *     diagonal push takes the axis that was meant, not the one checked first.
 *  3. The pick is given only once that input is back at rest, so the rest of the push never reaches
 *     the page behind the popup. One that doesn't come back within [RELEASE_MS] reads as held while
 *     untouched: it's dropped, and waits to be seen at rest again.
 * Sticks are axis values from -1 to 1 by MotionEvent axis id; triggers 0 to 1.
 */
class AxisPicker(private val sticks: Boolean = true) {
    private val rested = HashSet<String>()
    private var last: Map<String, Float> = emptyMap()
    private var firstAt = -1L
    private var best: String? = null
    private var chosen: String? = null
    private var chosenAt = 0L

    /** A new reading; returns the pick once it's made and let go. */
    fun feed(stickAxes: Map<Int, Float>, leftTrigger: Float, rightTrigger: Float, nowMs: Long): PadInput? {
        val v = HashMap<String, Float>()
        if (sticks) for ((axis, value) in stickAxes) { v["$axis+"] = maxOf(value, 0f); v["$axis-"] = maxOf(-value, 0f) }
        v[L] = leftTrigger; v[R] = rightTrigger
        last = v
        for ((id, x) in v) if (x < rest(id)) rested += id
        return tick(nowMs)
    }

    /** No new reading (a stick held still sends none): moves on by time alone. */
    fun tick(nowMs: Long): PadInput? {
        chosen?.let { id ->
            if ((last[id] ?: 0f) < rest(id)) { reset(); return input(id) }
            if (nowMs - chosenAt > RELEASE_MS) { rested -= id; reset() }
            return null
        }
        for ((id, x) in last) if (id in rested && x > line(id)) {
            if (firstAt < 0) firstAt = nowMs
            if (best == null || x > (last[best!!] ?: 0f)) best = id
        }
        if (firstAt >= 0 && nowMs - firstAt >= WINDOW_MS) { chosen = best; chosenAt = nowMs; firstAt = -1; best = null }
        return null
    }

    /** True while a pick waits (for the window, or for the input to be let go): keep ticking. */
    val waiting: Boolean get() = firstAt >= 0 || chosen != null

    private fun reset() { chosen = null; firstAt = -1; best = null }

    private fun rest(id: String) = if (id == L || id == R) TRIGGER_REST else STICK_REST
    private fun line(id: String) = if (id == L || id == R) TRIGGER_LINE else STICK_LINE

    private fun input(id: String): PadInput = when (id) {
        L -> PadInput.Key(KeyEvent.KEYCODE_BUTTON_L2)
        R -> PadInput.Key(KeyEvent.KEYCODE_BUTTON_R2)
        else -> PadInput.Axis(id.dropLast(1).toInt(), id.last() == '+')
    }

    companion object {
        const val WINDOW_MS = 100L
        const val RELEASE_MS = 2000L
        const val STICK_LINE = 0.7f
        const val STICK_REST = 0.3f
        const val TRIGGER_LINE = 0.5f
        const val TRIGGER_REST = 0.2f
        private const val L = "L"
        private const val R = "R"
    }
}
