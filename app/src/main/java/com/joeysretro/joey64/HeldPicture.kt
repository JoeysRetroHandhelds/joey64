package com.joeysretro.joey64

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * The picture of the state a game starts from, held on screen from the moment the ROM is found until the game's own
 * frames from that state are up (queue "Never lose progress when Android kills the app": hold the last frame over the
 * resume). Without it, carrying on after Android ended the process, or Continue, showed about a second of black
 * between Android's splash and the game (Duo, recovery state, 2026-11-13).
 *
 * The game draws straight into the activity's window (MainActivity, window.takeSurface), so views there don't draw:
 * this is its own panel window, as the pop-ups are. The picture sits where the game's will (NativeBridge.pictureRect,
 * the host's own placement with the display settings now), on black, so the game takes over without a jump.
 */
class HeldPicture(private val activity: Activity, private val picture: Bitmap) {
    private val handler = Handler(Looper.getMainLooper())
    private val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    private val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(picture) }
    private var added = false
    private var gone = false
    private val onDrawn = mutableListOf<() -> Unit>()

    /** The window has drawn once: the picture is on screen. */
    var drawn = false
        private set

    /** Adds the window. False when it can't be (no window token yet): the start goes on without it. */
    fun show(): Boolean {
        val token = activity.window.decorView.windowToken ?: return false
        root.addView(image)
        place()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT, // for the fade out; the black behind the picture covers the rest
        ).apply {
            this.token = token
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            windowAnimations = 0
        }
        root.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (drawn) return
                drawn = true
                // After this draw has gone out: the start that waits for it may block the main thread (loadGame).
                handler.post {
                    root.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(this)
                    val run = onDrawn.toList(); onDrawn.clear(); run.forEach { it() }
                }
            }
        })
        return try {
            activity.windowManager.addView(root, lp)
            added = true
            handler.postDelayed({ hide() }, MAX_MS)
            JLog.i(MainActivity.TAG, "held picture: ${picture.width}x${picture.height} up")
            true
        } catch (e: Exception) {
            JLog.e(MainActivity.TAG, "held picture: can't add its window: $e"); false
        }
    }

    /** Nothing to wait for: the picture is on screen, gone, or never got its window. */
    val ready get() = drawn || gone || !added

    /** Runs [f] once the picture is on screen (at once if [ready]). */
    fun whenDrawn(f: () -> Unit) { if (ready) f() else onDrawn += f }

    /** Puts the picture where the game's goes with the display settings now (again after they change). */
    fun place() {
        val dv = activity.window.decorView
        var w = dv.width; var h = dv.height
        if (w <= 0 || h <= 0) activity.resources.displayMetrics.let { w = it.widthPixels; h = it.heightPixels }
        val r = NativeBridge.pictureRect(w, h, picture.width, picture.height)
        image.layoutParams = FrameLayout.LayoutParams(r[2], r[3]).apply { leftMargin = r[0]; topMargin = r[1] }
    }

    /**
     * Takes it away once the game has drawn [frames] more frames than now (the loaded state's own pictures are then
     * being shown), checked every 16 ms.
     */
    fun hideAfterFrames(frames: Int = 2) {
        val until = NativeBridge.framesDrawn() + frames
        val check = object : Runnable {
            override fun run() {
                if (gone) return
                if (NativeBridge.framesDrawn() >= until) hide(fade = true) else handler.postDelayed(this, 16)
            }
        }
        handler.post(check)
    }

    /** Takes it away (at once, or in a short fade: the game is already behind it). */
    fun hide(fade: Boolean = false) {
        if (gone) return
        gone = true
        handler.removeCallbacksAndMessages(null)
        val run = onDrawn.toList(); onDrawn.clear()
        if (!added) { run.forEach { it() }; return }
        fun remove() {
            try { activity.windowManager.removeViewImmediate(root) } catch (e: Exception) { }
            added = false
            JLog.i(MainActivity.TAG, "held picture: gone")
        }
        if (fade) root.animate().alpha(0f).setDuration(FADE_MS).withEndAction { remove() }.start() else remove()
        run.forEach { it() }
    }

    companion object {
        /** The longest it stays: a start that never gets its frames (paused, a load that hangs) isn't covered for good. */
        const val MAX_MS = 10_000L
        const val FADE_MS = 120L
    }
}
