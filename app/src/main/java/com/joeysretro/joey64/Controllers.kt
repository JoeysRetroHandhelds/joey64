package com.joeysretro.joey64

import android.content.SharedPreferences
import android.view.InputDevice
import android.view.KeyEvent

// Controller profiles and stick calibration (M5 starts here; a press-to-bind remap screen comes next).
//
// The N64 mapping is by position: bottom face button = N64 A, left = N64 B, right = C-down, top = C-up,
// L2 = Z, right stick = C buttons. Android names the face buttons A/B/X/Y, and most pads send them by
// position (A bottom, B right, X left, Y top, the Xbox layout). Some send them by printed label instead:
// the AYN Odin 3 has Nintendo labels (A right, B bottom, X top, Y left) and sends BUTTON_A for its A
// (checked with getevent, 2026-11-04), so position-based code put N64 A on its right button.
object Controllers {
    enum class FaceCodes { POSITION, NINTENDO_LABELS }

    /** [stickReach]: the starting guess for how far each stick direction reaches (0..1); calibration learns the real value. */
    class Profile(val name: String, val faceCodes: FaceCodes, val stickReach: Float)

    val DEFAULT = Profile("generic", FaceCodes.POSITION, 0.9f)

    // By USB vendor/product id. Odin 3 built-in pad: "Odin Controller", 2020:0111; its stick reached
    // 77% forward and 93% back in getevent.
    private val KNOWN = mapOf(
        (0x2020 shl 16) or 0x0111 to Profile("AYN Odin 3", FaceCodes.NINTENDO_LABELS, 0.75f),
    )

    fun profile(dev: InputDevice?): Profile =
        dev?.let { KNOWN[(it.vendorId shl 16) or it.productId] } ?: DEFAULT

    private const val BOTTOM = 0
    private const val RIGHT = 1
    private const val LEFT = 2
    private const val TOP = 3

    private fun position(keyCode: Int, p: Profile): Int = when (p.faceCodes) {
        FaceCodes.POSITION -> when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> BOTTOM
            KeyEvent.KEYCODE_BUTTON_B -> RIGHT
            KeyEvent.KEYCODE_BUTTON_X -> LEFT
            KeyEvent.KEYCODE_BUTTON_Y -> TOP
            else -> -1
        }
        FaceCodes.NINTENDO_LABELS -> when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> RIGHT
            KeyEvent.KEYCODE_BUTTON_B -> BOTTOM
            KeyEvent.KEYCODE_BUTTON_X -> TOP
            KeyEvent.KEYCODE_BUTTON_Y -> LEFT
            else -> -1
        }
    }

    /** RetroPad id of a face button (mupen64plus-next: B = N64 A, Y = N64 B, A = C-down, X = C-up), or -1. */
    fun faceRetroId(keyCode: Int, p: Profile): Int = when (position(keyCode, p)) {
        BOTTOM -> 0 // RETRO_DEVICE_ID_JOYPAD_B → N64 A
        LEFT -> 1   // RETRO_DEVICE_ID_JOYPAD_Y → N64 B
        RIGHT -> 8  // RETRO_DEVICE_ID_JOYPAD_A → C-down
        TOP -> 9    // RETRO_DEVICE_ID_JOYPAD_X → C-up
        else -> -1
    }

    /**
     * Stick calibration: each direction of each axis is scaled by the furthest it has reached, so a stick
     * whose hardware stops short (Odin 3 forward: 77%) still gives full N64 deflection, and then the pair is
     * clamped to the unit circle. Reach starts at the profile's guess and only grows; it is kept per
     * device (InputDevice.descriptor) across launches.
     */
    class StickCal(private val key: String, start: Float, prefs: SharedPreferences) {
        // [axis pair][x+, x-, y+, y-]
        private val reach = Array(2) { s -> FloatArray(4) { i -> prefs.getFloat("$key:$s:$i", start) } }
        private var changed = false

        /** Returns the calibrated x, y in -1..1 for stick [s] (0 left, 1 right). */
        fun apply(s: Int, x: Float, y: Float): Pair<Float, Float> {
            val r = reach[s]
            fun one(v: Float, pos: Int): Float {
                val i = if (v >= 0) pos else pos + 1
                val a = kotlin.math.abs(v)
                if (a > r[i]) { r[i] = a; changed = true }
                return v / r[i]
            }
            var cx = one(x, 0)
            var cy = one(y, 2)
            val len = kotlin.math.sqrt(cx * cx + cy * cy)
            if (len > 1f) { cx /= len; cy /= len }
            return cx to cy
        }

        fun save(prefs: SharedPreferences) {
            if (!changed) return
            val e = prefs.edit()
            for (s in 0..1) for (i in 0..3) e.putFloat("$key:$s:$i", reach[s][i])
            e.apply()
            changed = false
        }
    }
}
