package com.joeysretro.joey64.launcher.controls

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.joeysretro.joey64.controls.AxisPicker
import com.joeysretro.joey64.controls.PadInput

/**
 * The remap popup's "press a button" (approved mockup 5: any button counts, even B): while a capture
 * is on, the next button, D-pad direction, trigger or stick push is taken raw, before the launcher's
 * A / B / Start handling turns it into a press, Back or Options.
 *
 * [CaptureInput] hooks the popup's own window (keys go to the focused window first). The button's
 * release then reaches whichever window is in front once the popup has closed, so [swallowRelease]
 * keeps it from landing as a B (Back) or an A there: the launcher's [KeyNormaliser] and the game
 * screen ask it first.
 */
object PadCapture {
    private var swallowUp = -1

    /** True when [e] is the release of a button a capture took; it's then consumed. */
    fun swallowRelease(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_UP || e.keyCode != swallowUp) return false
        swallowUp = -1
        return true
    }

    /** A key a capture takes: any pad button, the D-pad, Back, or a keyboard key. Volume and power stay Android's. */
    private fun takes(code: Int) = code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN &&
        code != KeyEvent.KEYCODE_POWER && code != KeyEvent.KEYCODE_HOME

    /** The input a key event names, or null (consumed either way while capturing). */
    fun keyInput(e: KeyEvent): PadInput? {
        if (!takes(e.keyCode)) return null
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return null
        swallowUp = e.keyCode
        return PadInput.Key(e.keyCode)
    }

    /** A hat D-pad press, as the D-pad key it is (no rest or release wait: it's a button). */
    fun hatInput(e: MotionEvent): PadInput? {
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        return when {
            hy < -0.5f -> PadInput.Key(KeyEvent.KEYCODE_DPAD_UP)
            hy > 0.5f -> PadInput.Key(KeyEvent.KEYCODE_DPAD_DOWN)
            hx < -0.5f -> PadInput.Key(KeyEvent.KEYCODE_DPAD_LEFT)
            hx > 0.5f -> PadInput.Key(KeyEvent.KEYCODE_DPAD_RIGHT)
            else -> null
        }
    }

    /** A stick or trigger reading, for [AxisPicker] (its guards: seen at rest first, biggest move, given on release). */
    fun feed(picker: AxisPicker, e: MotionEvent): PadInput? = picker.feed(
        STICK_AXES.associateWith { e.getAxisValue(it) },
        maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)),
        maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)),
        android.os.SystemClock.uptimeMillis(),
    )

    private val STICK_AXES = listOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
}

/**
 * Inside a popup ([com.joeysretro.joey64.launcher.components.JoeyDialog]): while [active], the popup's
 * window hands every key and stick move to [onInput] before anything else sees it ([keysOnly]: stick
 * pushes are ignored, as for the hotkeys, which are buttons; a hat D-pad and triggers still count).
 * Sticks and triggers go through [AxisPicker], so the popup stays until the picked one is let go.
 */
@Composable
fun CaptureInput(active: Boolean, keysOnly: Boolean = false, onInput: (PadInput, KeyEvent?) -> Unit) {
    val view = LocalView.current
    val current = rememberUpdatedState(onInput)
    DisposableEffect(view, active, keysOnly) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val original = window?.callback
        val picker = AxisPicker(sticks = !keysOnly)
        // A stick held still sends no events: time moves the pick on (window, release wait).
        val ticker = object : Runnable {
            override fun run() {
                picker.tick(android.os.SystemClock.uptimeMillis())?.let { current.value(it, null) }
                if (picker.waiting) view.postDelayed(this, 25)
            }
        }
        if (window != null && original != null && active) {
            window.callback = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (PadCapture.swallowRelease(event)) return true
                    PadCapture.keyInput(event)?.let { current.value(it, event); return true }
                    return if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                        original.dispatchKeyEvent(event) else true
                }
                override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                    if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return true
                    PadCapture.hatInput(event)?.let { current.value(it, null); return true }
                    val wasWaiting = picker.waiting
                    PadCapture.feed(picker, event)?.let { current.value(it, null) }
                    if (picker.waiting && !wasWaiting) view.postDelayed(ticker, 25)
                    return true
                }
            }
        }
        onDispose {
            view.removeCallbacks(ticker)
            if (window != null && original != null && active) window.callback = original
        }
    }
}
