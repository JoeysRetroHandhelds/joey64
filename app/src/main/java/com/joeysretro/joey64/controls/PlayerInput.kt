package com.joeysretro.joey64.controls

import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.sqrt

/**
 * One player's live input in game: the keys held and the stick axes, turned into the RetroPad bits
 * and analog values the host sends to that player's port. Pure, so it's tested on the JVM.
 *
 * D-pads that report a hat and triggers that report an axis come in as keys ([setHat], [setTriggers]),
 * so bindings only name keys and stick directions.
 */
class PlayerInput(setup: PlayerSetup) {
    var setup: PlayerSetup = setup
        set(v) { field = v; keyBits = keyBitsFor(v) }

    private val held = HashSet<Int>()
    private val axes = FloatArray(4) // X, Y, Z, RZ
    private var hatKeys = 0          // bits: up, down, left, right
    private var triggerKeys = 0      // bits: L2, R2
    private var keyBits = keyBitsFor(setup)

    /** Key code to the RetroPad bits it presses (one key may press more than one N64 button). */
    private fun keyBitsFor(s: PlayerSetup): Map<Int, Int> {
        val m = HashMap<Int, Int>()
        for ((b, input) in s.buttons) if (input is PadInput.Key) m[input.code] = (m[input.code] ?: 0) or (1 shl b.retroBit)
        return m
    }

    /** Whether this player uses [keyCode] for an N64 button (or, with the stick on the D-pad, for the stick). */
    fun uses(keyCode: Int): Boolean = keyCode in keyBits ||
        (setup.stick == StickSource.DPad && keyCode in DPAD_CODES)

    fun key(keyCode: Int, down: Boolean) { if (down) held.add(keyCode) else held.remove(keyCode) }

    fun setAxes(x: Float, y: Float, z: Float, rz: Float) { axes[0] = x; axes[1] = y; axes[2] = z; axes[3] = rz }

    fun setHat(hx: Float, hy: Float) {
        hatKeys = (if (hy < -0.5f) 1 else 0) or (if (hy > 0.5f) 2 else 0) or (if (hx < -0.5f) 4 else 0) or (if (hx > 0.5f) 8 else 0)
    }

    fun setTriggers(l: Float, r: Float) { triggerKeys = (if (l > 0.5f) 1 else 0) or (if (r > 0.5f) 2 else 0) }

    /** Lets go of everything (the pause menu opened, focus lost). */
    fun release() { held.clear(); hatKeys = 0; triggerKeys = 0; axes.fill(0f) }

    private fun isHeld(code: Int): Boolean = code in held || when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> hatKeys and 1 != 0
        KeyEvent.KEYCODE_DPAD_DOWN -> hatKeys and 2 != 0
        KeyEvent.KEYCODE_DPAD_LEFT -> hatKeys and 4 != 0
        KeyEvent.KEYCODE_DPAD_RIGHT -> hatKeys and 8 != 0
        KeyEvent.KEYCODE_BUTTON_L2 -> triggerKeys and 1 != 0
        KeyEvent.KEYCODE_BUTTON_R2 -> triggerKeys and 2 != 0
        else -> false
    }

    private fun axisValue(axis: Int): Float = when (axis) {
        MotionEvent.AXIS_X -> axes[0]; MotionEvent.AXIS_Y -> axes[1]
        MotionEvent.AXIS_Z -> axes[2]; MotionEvent.AXIS_RZ -> axes[3]
        else -> 0f
    }

    /** The RetroPad bits for the port: every N64 button whose input is held. */
    fun buttons(): Int {
        var mask = 0
        for ((b, input) in setup.buttons) {
            val on = when (input) {
                is PadInput.Key -> isHeld(input.code)
                is PadInput.Axis -> axisValue(input.axis).let { if (input.positive) it > 0.5f else it < -0.5f }
                null -> false
            }
            if (on) mask = mask or (1 shl b.retroBit)
        }
        return mask
    }

    /** The N64 stick, -1..1 each way, after the dead zone (calibration is applied to the axes before). */
    fun stick(): Pair<Float, Float> = when (setup.stick) {
        StickSource.LeftStick -> deadZone(axes[0], axes[1], setup.deadZone)
        StickSource.RightStick -> deadZone(axes[2], axes[3], setup.deadZone)
        StickSource.DPad -> {
            val x = (if (isHeld(KeyEvent.KEYCODE_DPAD_RIGHT)) 1f else 0f) - (if (isHeld(KeyEvent.KEYCODE_DPAD_LEFT)) 1f else 0f)
            val y = (if (isHeld(KeyEvent.KEYCODE_DPAD_DOWN)) 1f else 0f) - (if (isHeld(KeyEvent.KEYCODE_DPAD_UP)) 1f else 0f)
            x to y
        }
    }

    companion object {
        private val DPAD_CODES = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

        /** A round dead zone: inside [percent] of the reach is still, outside is scaled so full reach stays full. */
        fun deadZone(x: Float, y: Float, percent: Int): Pair<Float, Float> {
            val r = sqrt(x * x + y * y)
            val dz = percent / 100f
            if (r <= dz || r == 0f) return 0f to 0f
            val scale = ((r - dz) / (1f - dz)).coerceAtMost(1f) / r
            return x * scale to y * scale
        }

        /** The value the host sends for -1..1. */
        fun toRetro(v: Float): Int = (v.coerceIn(-1f, 1f) * 32767).toInt()

    }
}
