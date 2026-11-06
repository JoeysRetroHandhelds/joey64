package com.joeysretro.joey64

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.joeysretro.joey64.launcher.ingame.InGamePopup
import com.joeysretro.joey64.launcher.ingame.InGamePopupCard
import com.joeysretro.joey64.launcher.ingame.PauseActions
import com.joeysretro.joey64.launcher.ingame.PausePopup
import com.joeysretro.joey64.launcher.ingame.PauseScreen
import com.joeysretro.joey64.launcher.ingame.PopupQueue
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.launcher.theme.ThemeChoice

/**
 * The game screen's Compose UI (plan, Phase 3: the pause menu, the slot picker). The game draws
 * straight into the activity's window (MainActivity: takeSurface), so its views never draw; this
 * view only hosts the composition, and what it shows opens as real popup windows ([PausePopup] is a
 * JoeyDialog) over the game. MainActivity is a plain Activity, so the view gets an owner of its own
 * for lifecycle and saved state, which Compose needs.
 *
 * Pop-ups ("Slot 3 saved") are different: they show while the game runs and must not take the pad
 * or touches, so they're drawn in a panel window of their own in the top left corner, like the FPS
 * counter (top right), that can't be focused or touched.
 */
class GameOverlay(private val activity: Activity, private val actions: PauseActions) {
    var screen by mutableStateOf<PauseScreen?>(null)
    var title by mutableStateOf("")
    var saveSlotName by mutableStateOf("Slot 1")

    private val owner = OverlayOwner()
    private val popups = PopupQueue()
    private var popup by mutableStateOf<InGamePopup?>(null)
    private val handler = Handler(Looper.getMainLooper())
    private val popupTick = Runnable { advancePopups() }
    private val view = ComposeView(activity).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setContent {
            val store = remember { SettingsStore(context) }
            val settings by store.settings.collectAsState(initial = null)
            val s = screen
            if (s != null) JoeyTheme(settings?.theme ?: ThemeChoice.Dark) {
                PausePopup(s, title, saveSlotName, System.currentTimeMillis(), actions)
            }
        }
    }

    private val popupView = ComposeView(activity).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setContent {
            val store = remember { SettingsStore(context) }
            val settings by store.settings.collectAsState(initial = null)
            val p = popup
            if (p != null) JoeyTheme(settings?.theme ?: ThemeChoice.Dark) { InGamePopupCard(p) }
        }
    }
    private var popupAdded = false

    init {
        activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Shows [p] in the top left (after the one showing, unless it's about the same thing). Main thread. */
    fun show(p: InGamePopup) {
        if (!popupAdded) addPopupWindow()
        popups.add(p, SystemClock.uptimeMillis())
        popup = popups.current
        handler.removeCallbacks(popupTick)
        handler.postAtTime(popupTick, popups.until)
    }

    private fun advancePopups() {
        popups.tick(SystemClock.uptimeMillis())
        popup = popups.current
        if (popups.current != null) handler.postAtTime(popupTick, popups.until)
    }

    private fun addPopupWindow() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START   // Settings › Pop-up place: top left by default (approved)
            token = activity.window.decorView.windowToken
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            activity.windowManager.addView(popupView, lp)
            popupAdded = true
        } catch (e: Exception) {
            JLog.e(MainActivity.TAG, "pop-ups: can't add their window: $e")
        }
    }

    fun destroy() {
        screen = null
        handler.removeCallbacks(popupTick)
        popups.clear()
        popup = null
        if (popupAdded) try { activity.windowManager.removeView(popupView) } catch (e: Exception) {}
        popupAdded = false
        owner.destroy()
    }

    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        init {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
