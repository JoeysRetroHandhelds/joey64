package com.joeysretro.joey64.launcher.layout

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.Hint
import java.io.File

/**
 * Android TV (approved mockup 2026-11-09, scratchpad build_tv.py / tv_mock.png, "Approve, banner A"): whether this is a
 * TV, and what a TV lacks. Debug builds can pretend: `files/dev_tv` makes any device a TV (no touchscreen, no motor),
 * for checking the TV screens on a handheld; with "nopad" in it, no controller counts as connected either (Connect a
 * controller).
 */
object Tv {
    private fun forced(ctx: Context) =
        com.joeysretro.joey64.BuildConfig.DEBUG && File(ctx.applicationContext.filesDir, "dev_tv").exists()

    fun isTv(ctx: Context): Boolean = forced(ctx) ||
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** A controller to play with is connected (a TV's remote isn't one). */
    fun controllerConnected(ctx: Context): Boolean {
        if (com.joeysretro.joey64.BuildConfig.DEBUG) {
            val f = File(ctx.applicationContext.filesDir, "dev_tv")
            if (f.exists() && runCatching { f.readText() }.getOrNull()?.contains("nopad") == true) return false
        }
        return com.joeysretro.joey64.controls.PadDevices.pads().isNotEmpty()
    }

    /** A touchscreen to put touch controls on (TVs have none). */
    fun hasTouch(ctx: Context): Boolean = !forced(ctx) && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    /** A motor in the device itself, for Rumble on's This device (TVs have none). */
    fun hasVibrator(ctx: Context): Boolean = !forced(ctx) &&
        (ctx.getSystemService(android.os.Vibrator::class.java)?.hasVibrator() == true)

    /** Android TV's overscan-safe margins: 5% in from each edge of a 960 x 540 dp screen. */
    val SAFE_SIDES = 48.dp
    val SAFE_ENDS = 27.dp
}

/** On a TV, [content] inside the safe area, the edges [edge] (mockup screen 4); elsewhere just [content]. */
@Composable
fun TvSafeArea(edge: Color, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val tv = remember { Tv.isTv(ctx) }
    if (!tv) return content()
    Box(Modifier.fillMaxSize().background(edge).padding(horizontal = Tv.SAFE_SIDES, vertical = Tv.SAFE_ENDS)) { content() }
}

/**
 * The TV remote (mockup screens 4-5): which hints to show and Hold OK. [active] is true while the last press came from
 * a remote rather than a controller; only on a TV. Hints then name the remote's buttons: A is OK, Start is Hold OK,
 * B is Back; the rest have no remote button and are left out (a controller press brings them back).
 */
object RemoteKeys {
    var active by mutableStateOf(false)
        private set

    /** Marks the confirm presses Joey64 makes itself (a pad's A as DPAD centre), so they aren't taken for the remote's OK. */
    const val SYNTH_SCAN = 0x4A36

    fun synth(action: Int) = KeyEvent(0L, 0L, action, KeyEvent.KEYCODE_DPAD_CENTER, 0, 0, -1, SYNTH_SCAN, 0, InputDevice.SOURCE_KEYBOARD)

    /** A press from a controller (its source or its device says so), not a remote or keyboard. */
    fun fromPad(e: KeyEvent): Boolean =
        e.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            KeyEvent.isGamepadButton(e.keyCode) ||
            e.device?.let { com.joeysretro.joey64.controls.PadDevices.isPad(it) } == true

    /** The remote's OK: DPAD centre or Enter that Joey64 didn't make itself, not from a controller. */
    fun isOk(e: KeyEvent): Boolean = e.scanCode != SYNTH_SCAN && !fromPad(e) &&
        (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)

    /** Each press says which hints to show (on a TV only). */
    fun note(e: KeyEvent, tv: Boolean) {
        if (!tv || e.action != KeyEvent.ACTION_DOWN || e.scanCode == SYNTH_SCAN) return
        val pad = fromPad(e)
        if (active == pad) active = !pad
    }

    /** A controller's stick moved: back to the controller's hints. */
    fun padMoved() { if (active) active = false }

    /** The hint row for the remote: A, Start and B renamed, the D-pad's arrows kept, the rest left out. */
    fun hints(hints: List<Hint>): List<Hint> = hints.mapNotNull { h ->
        when (h.key) {
            "A" -> h.copy(key = "OK")
            "Start" -> h.copy(key = "Hold OK")
            "B" -> h.copy(key = "Back")
            "", "◀▶", "◀ ▶", "▲▼", "D-pad" -> h
            else -> null
        }
    }

    /**
     * Hold OK = Start (mockup screen 4: "as a long-press does by touch"). The remote's OK is held back until it's let
     * go: a short press is then passed on as a press ([click]), a hold of the long-press time calls [hold] once.
     */
    class OkHold(private val longMs: Long = android.view.ViewConfiguration.getLongPressTimeout().toLong()) {
        private var down = -1L
        private var held = false

        /** True when [e] was the remote's OK and has been taken care of. */
        fun handle(e: KeyEvent, click: () -> Unit, hold: () -> Unit): Boolean {
            if (!isOk(e)) return false
            when (step(e.action == KeyEvent.ACTION_DOWN, e.repeatCount, e.downTime, e.eventTime, e.isLongPress, e.isCanceled)) {
                Step.Click -> click()
                Step.Hold -> hold()
                Step.Nothing -> {}
            }
            return true
        }

        enum class Step { Nothing, Click, Hold }

        /** One press edge of the OK key (pure, so TvTest checks it). */
        fun step(isDown: Boolean, repeat: Int, downTime: Long, eventTime: Long, longFlag: Boolean, canceled: Boolean): Step {
            if (isDown) {
                if (repeat == 0) { down = downTime; held = false; return Step.Nothing }
                if (!held && downTime == down && (longFlag || eventTime - downTime >= longMs)) { held = true; return Step.Hold }
                return Step.Nothing
            }
            val click = downTime == down && !held && !canceled
            down = -1L
            return if (click) Step.Click else Step.Nothing
        }
    }
}
