package com.joeysretro.joey64

import android.app.Activity
import android.view.ViewGroup
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
import com.joeysretro.joey64.launcher.ingame.PauseActions
import com.joeysretro.joey64.launcher.ingame.PausePopup
import com.joeysretro.joey64.launcher.ingame.PauseScreen
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.launcher.theme.ThemeChoice

/**
 * The game screen's Compose UI (plan, Phase 3: the pause menu, the slot picker). The game draws
 * straight into the activity's window (MainActivity: takeSurface), so its views never draw; this
 * view only hosts the composition, and what it shows opens as real popup windows ([PausePopup] is a
 * JoeyDialog) over the game. MainActivity is a plain Activity, so the view gets an owner of its own
 * for lifecycle and saved state, which Compose needs.
 */
class GameOverlay(activity: Activity, private val actions: PauseActions) {
    var screen by mutableStateOf<PauseScreen?>(null)
    var title by mutableStateOf("")
    var saveSlotName by mutableStateOf("Slot 1")

    private val owner = OverlayOwner()
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

    init {
        activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun destroy() {
        screen = null
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
