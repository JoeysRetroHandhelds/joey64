package com.joeysretro.joey64.controls

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * What the Controls page shows for a button or stick direction (approved mockups: "A (bottom)",
 * "X (left)", "L2", "Right stick", "R stick ←"). The face buttons say where they sit when the
 * pad's face codes are known ([face] non-null); for N64-style pads the plain name is clearer.
 */
object InputNames {
    /** A cleared row. No dash glyph: UI strings have no em or en dashes (Joey's rule). */
    const val NOT_SET = "Not set"

    fun of(input: PadInput?, face: AutoMap.FaceCodes?): String = when (input) {
        null -> NOT_SET
        is PadInput.Key -> key(input.code, face)
        is PadInput.Axis -> axis(input)
    }

    fun key(code: Int, face: AutoMap.FaceCodes?): String {
        val where = when (face) {
            AutoMap.FaceCodes.POSITION -> mapOf(KeyEvent.KEYCODE_BUTTON_A to "bottom", KeyEvent.KEYCODE_BUTTON_B to "right",
                KeyEvent.KEYCODE_BUTTON_X to "left", KeyEvent.KEYCODE_BUTTON_Y to "top")
            AutoMap.FaceCodes.NINTENDO_LABELS -> mapOf(KeyEvent.KEYCODE_BUTTON_A to "right", KeyEvent.KEYCODE_BUTTON_B to "bottom",
                KeyEvent.KEYCODE_BUTTON_X to "top", KeyEvent.KEYCODE_BUTTON_Y to "left")
            null -> emptyMap()
        }
        val name = when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> "A"; KeyEvent.KEYCODE_BUTTON_B -> "B"
            KeyEvent.KEYCODE_BUTTON_X -> "X"; KeyEvent.KEYCODE_BUTTON_Y -> "Y"
            KeyEvent.KEYCODE_BUTTON_C -> "C"; KeyEvent.KEYCODE_BUTTON_Z -> "Z"
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1"; KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
            KeyEvent.KEYCODE_BUTTON_L2 -> "L2"; KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"; KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
            KeyEvent.KEYCODE_BUTTON_START -> "Start"; KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
            KeyEvent.KEYCODE_BUTTON_MODE -> "Home"; KeyEvent.KEYCODE_BACK -> "Back"
            KeyEvent.KEYCODE_DPAD_UP -> "D-pad up"; KeyEvent.KEYCODE_DPAD_DOWN -> "D-pad down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "D-pad left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "D-pad right"
            KeyEvent.KEYCODE_DPAD_CENTER -> "D-pad centre"
            in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 -> "Button ${code - KeyEvent.KEYCODE_BUTTON_1 + 1}"
            else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
        }
        return where[code]?.let { "$name ($it)" } ?: name
    }

    fun axis(a: PadInput.Axis): String = when (a.axis) {
        MotionEvent.AXIS_Z -> if (a.positive) "R stick →" else "R stick ←"
        MotionEvent.AXIS_RZ -> if (a.positive) "R stick ↓" else "R stick ↑"
        MotionEvent.AXIS_X -> if (a.positive) "L stick →" else "L stick ←"
        MotionEvent.AXIS_Y -> if (a.positive) "L stick ↓" else "L stick ↑"
        else -> "Axis ${a.axis}${if (a.positive) "+" else "-"}"
    }

    /** A group's value when its four directions are one thing ("D-pad", "Right stick"), or null (then "Split"). */
    fun group(setup: PlayerSetup, dirs: List<N64Button>): String? {
        val v = dirs.map { setup.buttons[it] }
        if (dirs == N64Button.C && v == N64Button.C.map { PadInput.RIGHT_STICK[it] }) return "Right stick"
        if (dirs == N64Button.DPAD && v == N64Button.DPAD.map { PadInput.DPAD_KEYS[it] }) return "D-pad"
        if (dirs == N64Button.C && v == N64Button.C.map { PadInput.DPAD_KEYS[N64Button.DPAD[N64Button.C.indexOf(it)]] }) return "D-pad"
        if (v.all { it == null }) return NOT_SET
        return null
    }
}
