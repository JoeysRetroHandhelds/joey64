package com.joeysretro.joey64.touch

import com.joeysretro.joey64.controls.N64Button
import com.joeysretro.joey64.controls.StickFeel
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Touch controls (approved mockup 2026-11-09, scratchpad build_touch3.py / touch3_mock.png, "Approve as drawn";
 * the 2026-11-05 design redrawn): what is on the screen, where, and how it behaves. Player 1's.
 *
 * Places are in units: a 374th of the screen's height in landscape, of its width in portrait (the approved
 * drawings were 832x374 units for a phone). A control keeps its distance from the edge it's nearest, so
 * the same layout fits a 20:9 phone and a 16:9 handheld (on 16:9 the right side moves in, mockup screen 2),
 * and in portrait the controls keep their place above the bottom edge.
 */
enum class TouchKind(val label: String) {
    L("L button"), R("R button"), Z("Z button"), Start("Start button"), Menu("Menu button"), Stick("Stick"),
    C("C buttons"), B("B button"), A("A button"),
    DPad("D-pad"), FastForward("Fast-forward"), SaveState("Save state"), LoadState("Load state"), Screenshot("Screenshot"), Macro("Macro");

    val added: Boolean get() = ordinal >= DPad.ordinal

    /** The N64 button it presses (the C buttons and D-pad press one of four by where the thumb is). */
    val button: N64Button? get() = when (this) {
        L -> N64Button.L; R -> N64Button.R; Z -> N64Button.Z; Start -> N64Button.Start; B -> N64Button.B; A -> N64Button.A
        else -> null
    }
}

enum class TouchShape { Pill, Circle, Stick, Diamond, Cross }

/**
 * One control: [x], [y] its centre in units from the left (or, [fromRight], the right) edge and from the top
 * (or, [fromBottom], the bottom); [w], [h] its drawn size at 100% (a circle's are its diameter); [size]
 * scales it; [opacity] is its own (null: the page's Opacity); [macro] names Player 1's macro on a Macro button.
 */
data class TouchControl(
    val kind: TouchKind,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val fromRight: Boolean = false,
    val fromBottom: Boolean = false,
    val size: Int = 100,
    val opacity: Int? = null,
    val hidden: Boolean = false,
    val macro: String? = null,
) {
    val shape: TouchShape get() = when (kind) {
        TouchKind.Stick -> TouchShape.Stick
        TouchKind.C -> TouchShape.Diamond
        TouchKind.DPad -> TouchShape.Cross
        TouchKind.L, TouchKind.R, TouchKind.Z, TouchKind.Start, TouchKind.SaveState, TouchKind.LoadState, TouchKind.Macro -> TouchShape.Pill
        else -> TouchShape.Circle
    }

    /** What the editor's sheet calls it ("Z button", or the macro's name). */
    val title: String get() = if (kind == TouchKind.Macro) macro ?: "Macro" else kind.label

    /** Same control (the editor keeps its pick across edits): the kind, and the macro for a macro button. */
    fun sameAs(o: TouchControl) = kind == o.kind && macro == o.macro

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("x", x.toDouble()).put("y", y.toDouble())
        .put("w", w.toDouble()).put("h", h.toDouble()).put("right", fromRight).put("bottom", fromBottom).put("size", size)
        .also { o -> opacity?.let { o.put("opacity", it) }; if (hidden) o.put("hidden", true); macro?.let { o.put("macro", it) } }

    companion object {
        fun fromJson(o: JSONObject): TouchControl? {
            val kind = TouchKind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
            return TouchControl(kind, o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(),
                o.optDouble("w", 40.0).toFloat().coerceIn(8f, 400f), o.optDouble("h", 40.0).toFloat().coerceIn(8f, 400f),
                o.optBoolean("right"), o.optBoolean("bottom"), o.optInt("size", 100).coerceIn(TouchLayouts.SIZE_MIN, TouchLayouts.SIZE_MAX),
                if (o.has("opacity")) o.optInt("opacity").coerceIn(10, 100) else null, o.optBoolean("hidden"),
                o.optString("macro").takeIf { o.has("macro") })
        }
    }
}

/** A control's place on a screen, in pixels: its centre and drawn size. */
data class Placed(val c: TouchControl, val cx: Float, val cy: Float, val w: Float, val h: Float) {
    val r: Float get() = min(w, h) / 2
}

object TouchLayouts {
    const val UNITS = 374f
    const val SIZE_MIN = 50
    const val SIZE_MAX = 200
    /** The C buttons' and D-pad's arms: a C button's centre is this far (in its own diameters) from the middle. */
    const val C_SPREAD = 30f / 32f

    private fun l(kind: TouchKind, x: Float, y: Float, w: Float, h: Float) = TouchControl(kind, x, y, w, h)
    /** Right side of the 832-unit drawing: kept from the right edge. */
    private fun r(kind: TouchKind, x: Float, y: Float, w: Float, h: Float) = TouchControl(kind, 832f - x, y, w, h, fromRight = true)

    /** The approved landscape layout (mockup screen 1, in the 832x374 drawing's units; centres). */
    val LANDSCAPE: List<TouchControl> = listOf(
        l(TouchKind.L, 12f + 58f, 12f + 17f, 116f, 34f),
        l(TouchKind.Start, 14f + 36f, 58f + 14f, 72f, 28f),
        l(TouchKind.Menu, 112f, 72f, 36f, 36f),
        l(TouchKind.Stick, 100f, 262f, 150f, 150f),
        r(TouchKind.R, 704f + 58f, 12f + 17f, 116f, 34f),
        r(TouchKind.C, 752f, 134f, 92f, 92f),
        r(TouchKind.B, 712f, 232f, 54f, 54f),
        r(TouchKind.A, 774f, 282f, 64f, 64f),
        r(TouchKind.Z, 668f + 29f, 288f + 20f, 58f, 40f),
    )

    /** Portrait (mockup screen 6, a 374 x 831 phone): kept above the bottom edge. */
    private const val PORTRAIT_H = 2400f / (1080f / UNITS)
    private fun p(kind: TouchKind, x: Float, y: Float, w: Float, h: Float) = TouchControl(kind, x, PORTRAIT_H - y, w, h, fromBottom = true)
    val PORTRAIT: List<TouchControl> = listOf(
        p(TouchKind.L, 14f + 55f, 336f + 17f, 110f, 34f),
        p(TouchKind.R, 250f + 55f, 336f + 17f, 110f, 34f),
        p(TouchKind.C, 300f, 438f, 92f, 92f),
        p(TouchKind.Stick, 100f, 560f, 150f, 150f),
        p(TouchKind.B, 262f, 540f, 56f, 56f),
        p(TouchKind.A, 322f, 590f, 66f, 66f),
        p(TouchKind.Z, 196f + 31f, 600f + 20f, 62f, 40f),
        p(TouchKind.Start, 120f + 36f, 760f + 14f, 72f, 28f),
        p(TouchKind.Menu, 222f, 774f, 36f, 36f),
    )

    /** What Add puts in the middle: its drawn size. */
    fun newControl(kind: TouchKind, screenW: Float, screenH: Float, portrait: Boolean, macro: String? = null): TouchControl {
        val (w, h) = when (kind) {
            TouchKind.DPad -> 110f to 110f
            TouchKind.SaveState, TouchKind.LoadState -> 72f to 28f
            TouchKind.Macro -> 96f to 30f
            else -> 40f to 40f
        }
        val u = unit(screenW, screenH, portrait)
        val cy = if (portrait) screenH * 0.62f / u else screenH / 2 / u
        return TouchControl(kind, screenW / 2 / u, if (portrait) screenH / u - cy else cy, w, h, fromBottom = portrait, macro = macro)
    }

    fun unit(screenW: Float, screenH: Float, portrait: Boolean) = (if (portrait) screenW else screenH) / UNITS

    fun place(c: TouchControl, screenW: Float, screenH: Float, portrait: Boolean): Placed {
        val u = unit(screenW, screenH, portrait)
        val k = c.size / 100f
        return Placed(c, if (c.fromRight) screenW - c.x * u else c.x * u, if (c.fromBottom) screenH - c.y * u else c.y * u, c.w * u * k, c.h * u * k)
    }

    /**
     * [c] moved so its centre is at ([cx], [cy]) pixels: kept from the nearer side (and in portrait from the bottom),
     * [snap] units on the grid when the editor's Grid is on.
     */
    fun moveTo(c: TouchControl, cx: Float, cy: Float, screenW: Float, screenH: Float, portrait: Boolean, snap: Float = 0f): TouchControl {
        val u = unit(screenW, screenH, portrait)
        fun s(v: Float) = if (snap > 0f) Math.round(v / snap) * snap else v
        val x = cx.coerceIn(0f, screenW) / u
        val y = cy.coerceIn(0f, screenH) / u
        val right = cx > screenW / 2
        val wu = screenW / u; val hu = screenH / u
        return if (portrait) c.copy(x = s(x), y = s(hu - y), fromRight = false, fromBottom = true)
        else c.copy(x = s(if (right) wu - x else x), y = s(y), fromRight = right, fromBottom = false)
    }

    fun toJson(list: List<TouchControl>) = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
    fun fromJson(a: JSONArray?): List<TouchControl>? = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(TouchControl::fromJson) } }

    /** The C buttons' four centres around a diamond at [p]: up, down, left, right. */
    fun cCentres(p: Placed): List<Pair<N64Button, Pair<Float, Float>>> {
        val d = p.w / 2 * (1f - 32f / 92f)
        return listOf(N64Button.CUp to (p.cx to p.cy - d), N64Button.CDown to (p.cx to p.cy + d),
            N64Button.CLeft to (p.cx - d to p.cy), N64Button.CRight to (p.cx + d to p.cy))
    }

    fun cRadius(p: Placed) = p.w * 16f / 92f
}

enum class TouchShow(val label: String, val detail: String) {
    NoController("When no controller", "A controller's press hides them; touch the screen to bring them back."),
    Always("Always", "Shown while you play, controller or not."),
    Never("Never", "No touch controls."),
}

enum class TouchStick(val label: String, val detail: String) {
    Fixed("Fixed in place", "The stick stays where the layout puts it."),
    Recentre("Recentres where you touch", "Touch anywhere on the stick and that spot becomes the centre."),
    LeftHalf("Anywhere on the left half", "Touch anywhere left of the game; the stick appears under your thumb."),
}

/** Settings › Controls › Touch controls (mockup screen 8), All games or one game. Null layouts: the approved ones. */
data class TouchSettings(
    val show: TouchShow = TouchShow.NoController,
    val opacity: Int = 60,
    val haptics: Boolean = true,
    /** Seconds without a touch before they fade; 0 never. */
    val hideAfter: Int = 0,
    val stick: TouchStick = TouchStick.Recentre,
    val sensitivity: Int = 100,
    val deadZone: Int = 8,
    val fullAt: Int = 85,
    val slide: Boolean = true,
    val landscape: List<TouchControl>? = null,
    val portrait: List<TouchControl>? = null,
) {
    fun layout(portrait: Boolean): List<TouchControl> = (if (portrait) this.portrait else landscape)
        ?: if (portrait) TouchLayouts.PORTRAIT else TouchLayouts.LANDSCAPE

    fun withLayout(portrait: Boolean, list: List<TouchControl>?) = if (portrait) copy(portrait = list) else copy(landscape = list)

    /**
     * Macro buttons whose macro isn't in [seen]: renamed by [renames] when the new name is there, dropped when their
     * macro is in [removed]; others stay as they are (TouchStore.macrosChanged).
     */
    fun withMacros(renames: Map<String, String>, removed: Set<String>, seen: Set<String>): TouchSettings {
        fun fix(list: List<TouchControl>?) = list?.mapNotNull { c ->
            val m = c.macro
            when {
                c.kind != TouchKind.Macro || m == null || m in seen -> c
                renames[m]?.let { it in seen } == true -> c.copy(macro = renames[m])
                m in removed -> null
                else -> c
            }
        }
        return copy(landscape = fix(landscape), portrait = fix(portrait))
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("show", show.name).put("opacity", opacity).put("haptics", haptics)
        .put("hideAfter", hideAfter).put("stick", stick.name).put("sensitivity", sensitivity).put("deadZone", deadZone)
        .put("fullAt", fullAt).put("slide", slide)
        .also { o -> landscape?.let { o.put("landscape", TouchLayouts.toJson(it)) }; portrait?.let { o.put("portrait", TouchLayouts.toJson(it)) } }

    /** The rows that differ from [o] (a game's dots against All games). */
    fun changedFrom(o: TouchSettings): Set<String> = buildSet {
        if (show != o.show) add(ROW_SHOW); if (opacity != o.opacity) add(ROW_OPACITY); if (haptics != o.haptics) add(ROW_HAPTICS)
        if (hideAfter != o.hideAfter) add(ROW_HIDE); if (stick != o.stick) add(ROW_STICK); if (sensitivity != o.sensitivity) add(ROW_SENS)
        if (deadZone != o.deadZone) add(ROW_DEAD); if (fullAt != o.fullAt) add(ROW_FULL); if (slide != o.slide) add(ROW_SLIDE)
        if (layout(false) != o.layout(false)) add(ROW_LANDSCAPE); if (layout(true) != o.layout(true)) add(ROW_PORTRAIT)
    }

    /** [row] set as [from] has it (Y: a game's row back to All games', All games' to the default). */
    fun rowFrom(row: String, from: TouchSettings): TouchSettings = when (row) {
        ROW_SHOW -> copy(show = from.show); ROW_OPACITY -> copy(opacity = from.opacity); ROW_HAPTICS -> copy(haptics = from.haptics)
        ROW_HIDE -> copy(hideAfter = from.hideAfter); ROW_STICK -> copy(stick = from.stick); ROW_SENS -> copy(sensitivity = from.sensitivity)
        ROW_DEAD -> copy(deadZone = from.deadZone); ROW_FULL -> copy(fullAt = from.fullAt); ROW_SLIDE -> copy(slide = from.slide)
        ROW_LANDSCAPE -> copy(landscape = from.landscape); ROW_PORTRAIT -> copy(portrait = from.portrait)
        else -> this
    }

    companion object {
        const val ROW_SHOW = "tShow"; const val ROW_OPACITY = "tOpacity"; const val ROW_HAPTICS = "tHaptics"; const val ROW_HIDE = "tHide"
        const val ROW_STICK = "tStick"; const val ROW_SENS = "tSens"; const val ROW_DEAD = "tDead"; const val ROW_FULL = "tFull"
        const val ROW_SLIDE = "tSlide"; const val ROW_LANDSCAPE = "tLandscape"; const val ROW_PORTRAIT = "tPortrait"

        val OPACITY_STEPS = (10..100 step 10).toList()
        val SENS_STEPS = (50..200 step 10).toList()
        val DEAD_STEPS = listOf(0, 2, 4, 6, 8, 10, 12, 15, 20, 25, 30)
        val FULL_STEPS = (50..100 step 5).toList()
        val HIDE_STEPS = listOf(0, 5, 10, 30)
        fun hideLabel(s: Int) = if (s == 0) "Never" else "After $s seconds"

        fun fromJson(o: JSONObject): TouchSettings {
            val d = TouchSettings()
            return TouchSettings(
                show = TouchShow.entries.firstOrNull { it.name == o.optString("show") } ?: d.show,
                opacity = o.optInt("opacity", d.opacity).coerceIn(10, 100),
                haptics = o.optBoolean("haptics", d.haptics),
                hideAfter = o.optInt("hideAfter", d.hideAfter).coerceIn(0, 600),
                stick = TouchStick.entries.firstOrNull { it.name == o.optString("stick") } ?: d.stick,
                sensitivity = o.optInt("sensitivity", d.sensitivity).coerceIn(50, 200),
                deadZone = o.optInt("deadZone", d.deadZone).coerceIn(0, 30),
                fullAt = o.optInt("fullAt", d.fullAt).coerceIn(50, 100),
                slide = o.optBoolean("slide", d.slide),
                landscape = TouchLayouts.fromJson(o.optJSONArray("landscape")),
                portrait = TouchLayouts.fromJson(o.optJSONArray("portrait")),
            )
        }

        /** The next step of [steps] from [now] by [dir] (◀ ▶). */
        fun step(steps: List<Int>, now: Int, dir: Int): Int {
            val i = (steps.indexOfFirst { it >= now }.takeIf { it >= 0 } ?: steps.lastIndex) + dir
            return steps[i.coerceIn(0, steps.lastIndex)]
        }
    }
}

object TouchMath {
    /**
     * The N64 stick for a thumb ([dx], [dy]) pixels from the stick's centre, [radius] the knob's reach:
     * Sensitivity shortens the reach, inside Dead zone is still, Full speed at is full tilt, and the
     * N64's eight-sided gate shapes it (a diagonal reaches 70, 70 of 80). 1 = full tilt each way.
     */
    fun stick(dx: Float, dy: Float, radius: Float, s: TouchSettings): Pair<Float, Float> {
        val reach = radius * 100f / s.sensitivity
        val d = hypot(dx, dy)
        if (d == 0f || reach <= 0f) return 0f to 0f
        val m = d / reach
        val dz = s.deadZone / 100f
        val full = max(dz + 0.05f, s.fullAt / 100f)
        if (m <= dz) return 0f to 0f
        val t = ((m - dz) / (full - dz)).coerceIn(0f, 1f) * StickFeel.octagon(dx, dy)
        return dx / d * t to dy / d * t
    }

    /** The D-pad's buttons for a thumb at ([dx], [dy]) from its middle: 8 ways, still near the middle. */
    fun dpad(dx: Float, dy: Float, r: Float): Set<N64Button> {
        if (hypot(dx, dy) < r * 0.2f) return emptySet()
        val a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) // 0 right, 90 down
        val out = HashSet<N64Button>()
        if (a > -67.5 && a < 67.5) out += N64Button.DRight
        if (a > 112.5 || a < -112.5) out += N64Button.DLeft
        if (a > 22.5 && a < 157.5) out += N64Button.DDown
        if (a < -22.5 && a > -157.5) out += N64Button.DUp
        return out
    }

    /** How far ([x], [y]) is outside [p], in pixels (0 inside; what touches pick the nearest by). */
    fun distance(p: Placed, x: Float, y: Float): Float = when (p.c.shape) {
        TouchShape.Pill -> {
            val ox = max(0f, abs(x - p.cx) - p.w / 2); val oy = max(0f, abs(y - p.cy) - p.h / 2)
            hypot(ox, oy)
        }
        else -> max(0f, hypot(x - p.cx, y - p.cy) - p.r)
    }
}
