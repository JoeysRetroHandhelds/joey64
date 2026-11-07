package com.joeysretro.joey64.controls

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Controls › Stick feel's Response curve (approved mockup 2026-11-07, screen 3). */
enum class StickCurve(val label: String, val detail: String, val power: Float) {
    Linear("Linear", "The game gets what you tilt", 1f),
    Precise("Precise", "Small tilts move less: easier to walk and aim", 1.8f),
    Quick("Quick", "Small tilts move more: less tilt to run", 0.6f),
}

/** Controls › Stick feel's Invert (screen 5). */
enum class StickInvert(val label: String, val detail: String?, val x: Boolean, val y: Boolean) {
    Off("Off", null, false, false),
    UpDown("Up/down", "Push up to go down, as flight games' option", false, true),
    LeftRight("Left/right", null, true, false),
    Both("Both", null, true, true),
}

/**
 * One player's stick feel (Controls › Stick feel, approved mockup 2026-11-07: scratchpad build_stick.py).
 * [apply] turns the stick's axes (calibrated, -1..1) into the N64 stick, where 1 is a real N64 stick's
 * full tilt (80); with Range above 100% or the octagon gate it can reach [MAX]. Defaults are what
 * Joey64 did before: the round dead zone, linear, full tilt 80, triggers count from halfway.
 */
data class StickFeel(
    /** Percent of a real N64 stick's reach at full tilt (80 = 100%). */
    val range: Int = 100,
    val curve: StickCurve = StickCurve.Linear,
    /** Full diagonals reach the N64's eight-sided gate (70, 70) instead of a circle's (57, 57). */
    val gate: Boolean = false,
    val invert: StickInvert = StickInvert.Off,
    /** Up/down and left/right swapped. */
    val swap: Boolean = false,
    /** Percent the smallest tilt past the dead zone already gives (games with a dead zone of their own). */
    val antiDeadZone: Int = 0,
    /** Percent an analog L2 / R2 goes down before it counts as pressed. */
    val triggerThreshold: Int = 50,
) {
    val isDefault get() = this == StickFeel()

    /** Rows that differ from [o] (the yellow dots), as "Feel.<row>". */
    fun differences(o: StickFeel): Set<String> = buildSet {
        if (range != o.range) add(RANGE)
        if (curve != o.curve) add(CURVE)
        if (gate != o.gate) add(GATE)
        if (invert != o.invert) add(INVERT)
        if (swap != o.swap) add(SWAP)
        if (antiDeadZone != o.antiDeadZone) add(ANTI)
        if (triggerThreshold != o.triggerThreshold) add(TRIGGER)
    }

    /** This feel with [row] taken from [from] (Y on a row). */
    fun withRow(row: String, from: StickFeel): StickFeel = when (row) {
        RANGE -> copy(range = from.range)
        CURVE -> copy(curve = from.curve)
        GATE -> copy(gate = from.gate)
        INVERT -> copy(invert = from.invert)
        SWAP -> copy(swap = from.swap)
        ANTI -> copy(antiDeadZone = from.antiDeadZone)
        TRIGGER -> copy(triggerThreshold = from.triggerThreshold)
        else -> this
    }

    /** The N64 stick for the stick's [x], [y] (Android axes: y is down) with a round [deadZone] percent. */
    fun apply(x: Float, y: Float, deadZone: Int): Pair<Float, Float> {
        var ax = if (swap) y else x
        var ay = if (swap) x else y
        if (invert.x) ax = -ax
        if (invert.y) ay = -ay
        val r = sqrt(ax * ax + ay * ay)
        val dz = deadZone / 100f
        if (r <= dz || r == 0f) return 0f to 0f
        var t = ((r - dz) / (1f - dz)).coerceAtMost(1f)
        if (curve.power != 1f) t = t.pow(curve.power)
        val a = antiDeadZone / 100f
        if (a > 0f) t = a + (1f - a) * t
        t *= range / 100f
        if (gate) t *= octagon(ax, ay)
        val k = t / r
        return (ax * k).coerceIn(-MAX, MAX) to (ay * k).coerceIn(-MAX, MAX)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("range", range); put("curve", curve.name); put("gate", gate); put("invert", invert.name); put("swap", swap)
        put("antiDeadZone", antiDeadZone); put("triggerThreshold", triggerThreshold)
    }

    companion object {
        const val RANGE = "Feel.Range"; const val CURVE = "Feel.Curve"; const val GATE = "Feel.Gate"; const val INVERT = "Feel.Invert"
        const val SWAP = "Feel.Swap"; const val ANTI = "Feel.Anti"; const val TRIGGER = "Feel.Trigger"
        val ROWS = listOf(RANGE, CURVE, GATE, INVERT, SWAP, ANTI, TRIGGER)

        /** The furthest the N64 stick goes each way, in full tilts (120 of the N64's 80): the host scales by it. */
        const val MAX = 1.5f
        val RANGE_STEPS = (50..150 step 5).toList()
        val ANTI_STEPS = (0..30 step 5).toList()
        val TRIGGER_STEPS = (10..90 step 10).toList()
        /** An N64 stick's full diagonal: about 70 each way of the 80 straight. */
        const val DIAGONAL = 70f / 80f

        /**
         * How far the N64's eight-sided gate reaches in the direction of ([x], [y]), against a circle of 1:
         * 1 straight up, down, left or right, 1.24 on a diagonal, along the gate's straight edges between.
         */
        fun octagon(x: Float, y: Float): Float {
            var phi = atan2(abs(y), abs(x)).toDouble()
            if (phi > Math.PI / 4) phi = Math.PI / 2 - phi
            // The edge from (1, 0) to (DIAGONAL, DIAGONAL): s (cos + sin (1 - DIAGONAL) / DIAGONAL) = 1.
            return (1.0 / (cos(phi) + sin(phi) * (1 - DIAGONAL) / DIAGONAL)).toFloat()
        }

        fun fromJson(o: JSONObject, fallback: StickFeel): StickFeel = StickFeel(
            range = o.optInt("range", fallback.range).coerceIn(50, 150),
            curve = StickCurve.entries.firstOrNull { it.name == o.optString("curve") } ?: fallback.curve,
            gate = o.optBoolean("gate", fallback.gate),
            invert = StickInvert.entries.firstOrNull { it.name == o.optString("invert") } ?: fallback.invert,
            swap = o.optBoolean("swap", fallback.swap),
            antiDeadZone = o.optInt("antiDeadZone", fallback.antiDeadZone).coerceIn(0, 30),
            triggerThreshold = o.optInt("triggerThreshold", fallback.triggerThreshold).coerceIn(10, 90),
        )
    }
}
