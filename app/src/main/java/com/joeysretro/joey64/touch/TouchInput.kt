package com.joeysretro.joey64.touch

import com.joeysretro.joey64.controls.N64Button
import kotlin.math.hypot

/**
 * The touch controls' fingers: which control each one holds, the N64 buttons and stick they give Player 1,
 * and the other controls' presses (menu, fast-forward, states, screenshot, macros). Any number of fingers at
 * once. Pure, so it's tested on the JVM; the game screen's layer feeds it MotionEvents' pointers.
 *
 * Slide between buttons On: a finger moved from one button onto another lets the first go and presses the
 * second (the N64 buttons, the C buttons and the D-pad; never onto the menu, states or the stick). Off: a
 * finger keeps the button it came down on.
 */
class TouchInput(private val listener: Listener) {
    interface Listener {
        /** A control that isn't an N64 button ([TouchKind.Menu], [TouchKind.FastForward], ...) pressed or let go. */
        fun onAction(c: TouchControl, down: Boolean)
        /** Something new is held (haptics). */
        fun onPress()
    }

    private var list: List<TouchControl> = emptyList()
    private var placed: List<Placed> = emptyList()
    private var w = 0f
    private var h = 0f
    private var u = 1f
    var settings = TouchSettings()
        private set

    private sealed interface Hold { val index: Int }
    private data class Btn(override val index: Int, val buttons: Set<N64Button>) : Hold
    private data class Action(override val index: Int) : Hold
    private data class Stick(override val index: Int, val ox: Float, val oy: Float, var x: Float, var y: Float, val moved: Boolean) : Hold
    /** A finger on nothing, kept for sliding onto a button. */
    private data class Nothing(override val index: Int = -1) : Hold

    private val holds = HashMap<Int, Hold>()

    fun setup(list: List<TouchControl>, w: Float, h: Float, portrait: Boolean, settings: TouchSettings) {
        if (list != this.list || w != this.w || h != this.h) cancel()
        this.list = list; this.w = w; this.h = h; this.settings = settings
        u = TouchLayouts.unit(w, h, portrait)
        placed = list.map { TouchLayouts.place(it, w, h, portrait) }
    }

    /** Lets every finger go (the pause menu, the layer hidden); actions held get their release. */
    fun cancel() {
        for (hd in holds.values) if (hd is Action) listener.onAction(list[hd.index], false)
        holds.clear()
    }

    val holding: Boolean get() = holds.isNotEmpty()

    /** Finger [id] is on no control (a tap there may be for a pop-up under the controls). */
    fun holdsNothing(id: Int): Boolean = holds[id] is Nothing

    fun down(id: Int, x: Float, y: Float) {
        val hd = pick(x, y, buttonsOnly = false)
        holds[id] = hd
        when (hd) {
            is Action -> { listener.onPress(); listener.onAction(list[hd.index], true) }
            is Btn -> if (hd.buttons.isNotEmpty()) listener.onPress()
            is Stick -> listener.onPress()
            is Nothing -> {}
        }
    }

    fun move(id: Int, x: Float, y: Float) {
        val hd = holds[id] ?: return
        when (hd) {
            is Stick -> { hd.x = x; hd.y = y }
            is Btn -> {
                val p = placed[hd.index]
                // The D-pad follows the thumb round, slide or not; so does the C diamond with Slide on.
                if (p.c.kind == TouchKind.DPad && TouchMath.distance(p, x, y) <= margin(p)) {
                    val b = TouchMath.dpad(x - p.cx, y - p.cy, p.r)
                    if (b != hd.buttons) { holds[id] = Btn(hd.index, b); if ((b - hd.buttons).isNotEmpty()) listener.onPress() }
                    return
                }
                if (!settings.slide) return
                val next = pick(x, y, buttonsOnly = true)
                if (next is Btn && next != hd) { holds[id] = next; if (next.buttons.isNotEmpty()) listener.onPress() }
                else if (next is Nothing && TouchMath.distance(p, x, y) > margin(p) * 2) holds[id] = Nothing()
            }
            is Nothing -> if (settings.slide) {
                val next = pick(x, y, buttonsOnly = true)
                if (next is Btn) { holds[id] = next; if (next.buttons.isNotEmpty()) listener.onPress() }
            }
            is Action -> {}
        }
    }

    fun up(id: Int) {
        val hd = holds.remove(id) ?: return
        if (hd is Action) listener.onAction(list[hd.index], false)
    }

    /** The RetroPad bits held. */
    fun buttonBits(): Int {
        var m = 0
        for (hd in holds.values) if (hd is Btn) for (b in hd.buttons) m = m or (1 shl b.retroBit)
        return m
    }

    /** The N64 stick (1 = full tilt each way), or null when no finger is on the stick. */
    fun stick(): Pair<Float, Float>? {
        val s = holds.values.firstOrNull { it is Stick } as Stick? ?: return null
        val p = placed[s.index]
        return TouchMath.stick(s.x - s.ox, s.y - s.oy, reach(p), settings)
    }

    fun drawState(): TouchDrawState {
        val pressed = HashSet<Int>(); val buttons = HashSet<N64Button>()
        val knobs = HashMap<Int, Pair<Float, Float>>(); val bases = HashMap<Int, Pair<Float, Float>>()
        for (hd in holds.values) when (hd) {
            is Btn -> { if (hd.buttons.isNotEmpty()) pressed += hd.index; buttons += hd.buttons }
            is Action -> pressed += hd.index
            is Stick -> {
                val p = placed[hd.index]
                var dx = hd.x - hd.ox; var dy = hd.y - hd.oy
                val d = hypot(dx, dy); val max = reach(p)
                if (d > max) { dx *= max / d; dy *= max / d }
                knobs[hd.index] = dx to dy
                if (hd.moved) bases[hd.index] = hd.ox to hd.oy
            }
            is Nothing -> {}
        }
        return TouchDrawState(pressed, buttons, knobs, bases)
    }

    /** How far the knob travels: to the base's rim, less the knob. */
    private fun reach(p: Placed) = (p.r - 30 * u * p.c.size / 100f).coerceAtLeast(p.r * 0.3f)

    /** How far outside a control a touch still counts. */
    private fun margin(p: Placed) = 10 * u * p.c.size / 100f

    private fun stickFree() = holds.values.none { it is Stick }

    private fun pick(x: Float, y: Float, buttonsOnly: Boolean): Hold {
        var best = -1; var bestD = Float.MAX_VALUE
        placed.forEachIndexed { i, p ->
            if (p.c.hidden) return@forEachIndexed
            if (buttonsOnly && (p.c.kind == TouchKind.Stick || p.c.kind.button == null && p.c.kind != TouchKind.C && p.c.kind != TouchKind.DPad)) return@forEachIndexed
            if (p.c.kind == TouchKind.Stick && !stickFree()) return@forEachIndexed
            val d = TouchMath.distance(p, x, y)
            val limit = if (p.c.kind == TouchKind.Stick) p.r * 0.15f else margin(p)
            if (d <= limit && d <= bestD) { best = i; bestD = d }
        }
        if (best < 0) {
            // Anywhere on the left half: the stick comes to the thumb.
            val stick = placed.indexOfFirst { it.c.kind == TouchKind.Stick && !it.c.hidden }
            if (!buttonsOnly && stick >= 0 && settings.stick == TouchStick.LeftHalf && x < w / 2 && stickFree())
                return Stick(stick, x, y, x, y, moved = true)
            return Nothing()
        }
        val p = placed[best]
        return when (p.c.kind) {
            TouchKind.Stick -> if (settings.stick == TouchStick.Fixed) Stick(best, p.cx, p.cy, x, y, false) else Stick(best, x, y, x, y, false)
            TouchKind.C -> Btn(best, setOf(TouchLayouts.cCentres(p).minBy { (_, c) -> hypot(c.first - x, c.second - y) }.first))
            TouchKind.DPad -> Btn(best, TouchMath.dpad(x - p.cx, y - p.cy, p.r))
            else -> p.c.kind.button?.let { Btn(best, setOf(it)) } ?: Action(best)
        }
    }
}
