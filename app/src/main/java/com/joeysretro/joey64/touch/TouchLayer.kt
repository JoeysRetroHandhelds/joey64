package com.joeysretro.joey64.touch

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.joeysretro.joey64.JLog

/**
 * The touch controls over the game (approved mockup 2026-11-09). The game draws straight into the activity's
 * window, so the controls are a panel window of their own over it that takes touches but never focus (the pad
 * still goes to the game). While they're hidden (a controller was used, or Hide when not touched) the window is
 * removed, and the next touch on the game's window ([touchedWhileHidden]) brings them back without pressing.
 */
class TouchLayer(private val activity: Activity, private val host: Host) {
    interface Host {
        /** Player 1's buttons and stick from touch changed (bits: RetroPad; stick: null when no finger is on it). */
        fun onTouchInput(bits: Int, stick: Pair<Float, Float>?)
        fun onTouchAction(c: TouchControl, down: Boolean)
        /** One finger came down and went up on no control, at these screen points. */
        fun onEmptyTap(downX: Float, downY: Float, upX: Float, upY: Float)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var settings = TouchSettings()
    private var added = false
    /** Hidden by a controller's press or by Hide when not touched; the next touch shows them. */
    private var hidden = false
    /** Off while the pause menu or another screen is over the game. */
    private var active = false
    private var lastBits = 0
    private var lastStick: Pair<Float, Float>? = null
    /** A lone finger that came down on no control: where (raw screen points), for [Host.onEmptyTap]. */
    private var emptyDown: Pair<Float, Float>? = null

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) (activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    } catch (e: Exception) { null }

    private val input = TouchInput(object : TouchInput.Listener {
        override fun onAction(c: TouchControl, down: Boolean) = host.onTouchAction(c, down)
        override fun onPress() {
            if (!settings.haptics) return
            try { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) } catch (e: Exception) {}
        }
    })

    private val fadeOut = Runnable { if (!input.holding) hide("not touched") else touched() }

    private val view: View = object : View(activity) {
        val draw = TouchDraw(activity)
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { setupInput() }
        override fun onDraw(canvas: Canvas) {
            draw.draw(canvas, settings.layout(portrait()), width.toFloat(), height.toFloat(), portrait(), settings.opacity, input.drawState())
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val i = e.actionIndex
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    input.down(e.getPointerId(i), e.getX(i), e.getY(i))
                    emptyDown = if (e.actionMasked == MotionEvent.ACTION_DOWN && input.holdsNothing(e.getPointerId(i))) e.rawX to e.rawY else null
                }
                MotionEvent.ACTION_MOVE -> for (k in 0 until e.pointerCount) input.move(e.getPointerId(k), e.getX(k), e.getY(k))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val id = e.getPointerId(i)
                    val tap = emptyDown?.takeIf { e.actionMasked == MotionEvent.ACTION_UP && input.holdsNothing(id) }
                    input.up(id)
                    emptyDown = null
                    if (tap != null) { send(); host.onEmptyTap(tap.first, tap.second, e.rawX, e.rawY); return true }
                }
                MotionEvent.ACTION_CANCEL -> { input.cancel(); emptyDown = null }
            }
            touched()
            send()
            invalidate()
            return true
        }
    }

    private fun portrait(): Boolean = view.height > view.width

    private fun setupInput() {
        input.setup(settings.layout(portrait()), view.width.toFloat(), view.height.toFloat(), portrait(), settings)
    }

    /** The settings for this play (All games, or the game's own); shown at once unless they're for when no controller is in use. */
    fun apply(s: TouchSettings, controllerConnected: Boolean) {
        settings = s
        hidden = s.show == TouchShow.NoController && controllerConnected
        setupInput()
        view.invalidate()
        update()
        JLog.i(TAG, "touch controls: ${s.show.name}${if (hidden) ", hidden until the screen is touched" else ""}")
    }

    /** Changed settings (the pause menu's Controls): shown or hidden as they were, unless Show touch controls changed. */
    fun reapply(s: TouchSettings) {
        val was = settings
        settings = s
        if (s.show != was.show) hidden = false
        setupInput()
        view.invalidate()
        update()
    }

    /** Shown now, whatever hid them (Use touch controls when a controller disconnects). */
    fun showNow() {
        hidden = false
        update()
    }

    /** The game is playing (true) or something is over it (false: the pause menu, a page). */
    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        if (!on) release()
        update()
    }

    /** A controller was used: When no controller hides them. */
    fun controllerUsed() {
        if (settings.show == TouchShow.NoController && !hidden && added) hide("a controller was used")
    }

    /**
     * A touch on the game's own window, so the controls are hidden: they come back, and the touch presses
     * nothing. True when it was taken.
     */
    fun touchedWhileHidden(e: MotionEvent): Boolean {
        if (!active || settings.show == TouchShow.Never) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN && hidden) {
            hidden = false
            update()
            touched()
            JLog.i(TAG, "touch controls back")
        }
        return true
    }

    val showing: Boolean get() = added

    private fun hide(why: String) {
        hidden = true
        release()
        update()
        JLog.i(TAG, "touch controls hidden: $why")
    }

    private fun touched() {
        handler.removeCallbacks(fadeOut)
        if (settings.hideAfter > 0) handler.postDelayed(fadeOut, settings.hideAfter * 1000L)
    }

    private fun release() {
        input.cancel()
        send()
        view.invalidate()
    }

    private fun send() {
        val bits = input.buttonBits(); val stick = input.stick()
        if (bits == lastBits && stick == lastStick) return
        lastBits = bits; lastStick = stick
        host.onTouchInput(bits, stick)
    }

    private fun update() {
        val want = active && !hidden && settings.show != TouchShow.Never
        if (want == added) return
        if (want) {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT,
            ).apply {
                token = activity.window.decorView.windowToken
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                title = "Joey64 touch controls"
            }
            try { activity.windowManager.addView(view, lp); added = true; touched() }
            catch (e: Exception) { JLog.e(TAG, "touch controls: can't add their window: $e") }
        } else {
            handler.removeCallbacks(fadeOut)
            try { activity.windowManager.removeView(view) } catch (e: Exception) {}
            added = false
        }
    }

    fun destroy() {
        active = false
        release()
        update()
    }

    companion object { private const val TAG = "Joey64" }
}
