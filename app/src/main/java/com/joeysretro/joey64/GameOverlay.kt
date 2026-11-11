package com.joeysretro.joey64

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.joeysretro.joey64.achievements.PopupSpot
import com.joeysretro.joey64.achievements.RaSettings
import com.joeysretro.joey64.launcher.ingame.ChallengeBadges
import com.joeysretro.joey64.launcher.ingame.InGamePopup
import com.joeysretro.joey64.launcher.ingame.InGamePopupCard
import com.joeysretro.joey64.launcher.ingame.PauseActions
import com.joeysretro.joey64.launcher.ingame.PausePopup
import com.joeysretro.joey64.launcher.ingame.PauseScreen
import com.joeysretro.joey64.launcher.ingame.PopupQueue
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.launcher.theme.ThemeChoice
import java.io.File

/**
 * The game screen's Compose UI (plan, Phase 3: the pause menu, the slot picker). The game draws
 * straight into the activity's window (MainActivity: takeSurface), so its views never draw; this
 * view only hosts the composition, and what it shows opens as real popup windows ([PausePopup] is a
 * JoeyDialog) over the game. MainActivity is a plain Activity, so the view gets an owner of its own
 * for lifecycle and saved state, which Compose needs.
 *
 * Pop-ups ("Slot 3 saved") are different: they show while the game runs and must not take the pad
 * or touches, so they're drawn in a panel window of their own in the top left corner, like the FPS
 * counter (top right), that can't be focused or touched. Where they go and how long they stay is
 * Settings › RetroAchievements › Pop-up place / Pop-ups stay for. Challenge badges (the
 * achievements a player is attempting right now) get a second such window at Badge place.
 */
class GameOverlay(private val activity: Activity, private val actions: PauseActions) {
    var screen by mutableStateOf<PauseScreen?>(null)
    var title by mutableStateOf("")
    /** Settings › On screen: pop-ups keep out of the stats' corner. */
    var onScreen = com.joeysretro.joey64.onscreen.OnScreen()
    var saveSlotName by mutableStateOf("Slot 1")

    private val owner = OverlayOwner()

    /**
     * Pickers in the pause menu's pages (the Picture panel's Your own image, Thor 2026-11-08: without an
     * owner it crashed). The game screen is a plain Activity, so a pick launches through
     * startActivityForResult and MainActivity.onActivityResult hands the answer back here.
     */
    val results = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            try {
                activity.startActivityForResult(contract.createIntent(activity, input), requestCode, options?.toBundle())
            } catch (e: ActivityNotFoundException) {
                JLog.e("Joey64", "no app to pick with: $e")
                dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
            }
        }
    }
    private val resultsOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = results
    }
    private val popups = PopupQueue()
    /**
     * The pop-up showing. With none the window is made invisible: at 0 px high it kept its last
     * frame on screen ("Slot 1 loaded" stayed for minutes, Thor 2026-11-07).
     */
    private var popup: InGamePopup?
        get() = popupState
        set(v) { popupState = v; popupView.visibility = if (v == null) View.INVISIBLE else View.VISIBLE }
    private var popupState by mutableStateOf<InGamePopup?>(null)
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
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides resultsOwner) {
                    PausePopup(s, title, saveSlotName, System.currentTimeMillis(), actions)
                }
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
            val p = popupState
            if (p != null) JoeyTheme(popupTheme(settings)) {
                Across(popupSpot) { Box(Modifier.onGloballyPositioned { popupBox = it.boundsInWindow() }) { InGamePopupCard(p) } }
            }
        }
    }
    private var popupAdded = false
    private var popupSpot by mutableStateOf<PopupSpot?>(null)

    private var badgeFiles by mutableStateOf<List<File?>>(emptyList())
    private val badgeView = ComposeView(activity).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setContent {
            val store = remember { SettingsStore(context) }
            val settings by store.settings.collectAsState(initial = null)
            val b = badgeFiles
            if (b.isNotEmpty()) JoeyTheme(popupTheme(settings)) {
                Across(badgeSpot) { Box(Modifier.onGloballyPositioned { badgeBox = it.boundsInWindow() }) { ChallengeBadges(b) } }
            }
        }
    }
    private var badgeAdded = false
    private var badgeSpot by mutableStateOf<PopupSpot?>(null)
    private var badgeIds: List<Int> = emptyList()

    // Where the card and the badge box are in their windows, for taps (the windows themselves can't be
    // touched, so the game's window gets every touch and asks [tapAt]).
    private var popupBox: Rect? = null
    private var badgeBox: Rect? = null

    /** What a tap opens (plan: tap to open). */
    sealed interface Tap { data class Popup(val popup: InGamePopup) : Tap; data class Badges(val newest: Int?) : Tap }

    /** The pop-up or the badge box under a tap at screen [x], [y], or null. Main thread. */
    fun tapAt(x: Float, y: Float): Tap? {
        fun hit(v: View, box: Rect?): Boolean {
            if (box == null || v.visibility != View.VISIBLE || !v.isAttachedToWindow) return false
            val at = IntArray(2).also { v.getLocationOnScreen(it) }
            return box.contains(androidx.compose.ui.geometry.Offset(x - at[0], y - at[1]))
        }
        val p = popup
        if (p != null && hit(popupView, popupBox)) return Tap.Popup(p)
        if (badgeFiles.isNotEmpty() && hit(badgeView, badgeBox)) return Tap.Badges(badgeIds.lastOrNull())
        return null
    }

    /** Settings › Appearance › Pop-ups for the pop-up and badge windows; the pause menu follows Theme. */
    private fun popupTheme(s: com.joeysretro.joey64.launcher.settings.LauncherSettings?) =
        (s?.popupTheme ?: com.joeysretro.joey64.launcher.theme.PopupTheme.Dark).resolve(s?.theme ?: ThemeChoice.Dark)

    init {
        activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Shows [p] at Pop-up place (after the one showing, unless it's about the same thing). Main thread. */
    fun show(p: InGamePopup) {
        val o = RaSettings.current(activity)
        popups.stayMs = o.popSeconds * 1_000L
        val place = onScreen.popupSpot(o.popPlace)
        placeWindow(popupView, popupAdded, popupSpot, place)?.let { popupAdded = it; if (it) popupSpot = place }
        popups.add(p, SystemClock.uptimeMillis())
        popup = popups.current
        handler.removeCallbacks(popupTick)
        handler.postAtTime(popupTick, popups.until)
    }

    /**
     * Ends the pop-up with [key] now if it's the one showing (rcheevos hides a progress one itself). One
     * still waiting behind an unlock stays: its count is still news when its turn comes. Main thread.
     */
    fun hide(key: String, waiting: Boolean = false) {
        if (popups.current?.key != key) {
            // [waiting]: one waiting behind newer pop-ups goes too (Paused, under a volume pop-up).
            if (waiting) popups.remove(key, SystemClock.uptimeMillis())
            return
        }
        popups.remove(key, SystemClock.uptimeMillis())
        popup = popups.current
        handler.removeCallbacks(popupTick)
        if (popups.current != null) handler.postAtTime(popupTick, popups.until)
    }

    /** The challenge badges at Badge place, newest last; an empty list takes them away. Main thread. */
    fun setChallengeBadges(ids: List<Int>, files: List<File?>) {
        badgeIds = ids
        if (files.isNotEmpty()) {
            val spot = RaSettings.current(activity).badgePlace
            placeWindow(badgeView, badgeAdded, badgeSpot, spot)?.let { badgeAdded = it; if (it) badgeSpot = spot }
        }
        badgeFiles = files
        badgeView.visibility = if (files.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun advancePopups() {
        popups.tick(SystemClock.uptimeMillis())
        popup = popups.current
        if (popups.current != null) handler.postAtTime(popupTick, popups.until)
    }

    private fun gravityOf(spot: PopupSpot) = when (spot) {
        PopupSpot.TopLeft, PopupSpot.TopMiddle, PopupSpot.TopRight -> Gravity.TOP
        else -> Gravity.BOTTOM
    }

    /**
     * The window is the screen's width (a WRAP_CONTENT one is measured at Android's dialog width
     * first, about 300 dp, which cut the unlock label short); the card sits left, middle or right in it.
     */
    @Composable
    private fun Across(spot: PopupSpot?, content: @Composable () -> Unit) {
        val align = when (spot) {
            PopupSpot.TopMiddle, PopupSpot.BottomMiddle -> Alignment.Center
            PopupSpot.TopRight, PopupSpot.BottomRight -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = align) { content() }
    }

    /**
     * Adds [v]'s panel window at [spot], or moves it there when the setting changed since. Null when
     * nothing was done, else whether the window is up.
     */
    private fun placeWindow(v: ComposeView, added: Boolean, at: PopupSpot?, spot: PopupSpot): Boolean? {
        if (added && at == spot) return null
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = gravityOf(spot)   // top left for pop-ups and bottom right for badges by default (approved)
            token = activity.window.decorView.windowToken
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            if (added) activity.windowManager.updateViewLayout(v, lp) else activity.windowManager.addView(v, lp)
            true
        } catch (e: Exception) {
            JLog.e(MainActivity.TAG, "pop-ups: can't place their window: $e")
            added
        }
    }

    fun destroy() {
        screen = null
        handler.removeCallbacks(popupTick)
        popups.clear()
        popup = null
        if (popupAdded) try { activity.windowManager.removeView(popupView) } catch (e: Exception) {}
        popupAdded = false
        badgeFiles = emptyList()
        badgeIds = emptyList()
        if (badgeAdded) try { activity.windowManager.removeView(badgeView) } catch (e: Exception) {}
        badgeAdded = false
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
