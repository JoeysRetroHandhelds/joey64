package com.joeysretro.joey64.launcher

import com.joeysretro.joey64.LogFiles
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.components.JoeyToast
import com.joeysretro.joey64.launcher.screens.SettingsScreen
import com.joeysretro.joey64.launcher.screens.UpdatePopup
import com.joeysretro.joey64.launcher.update.UpdateModel
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.joeysretro.joey64.launcher.components.FocusLanding
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.HintRow
import com.joeysretro.joey64.launcher.components.LauncherRail
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.RailPlace
import com.joeysretro.joey64.launcher.controls.KeyNormaliser
import com.joeysretro.joey64.launcher.layout.ProvideScreenLayout
import com.joeysretro.joey64.launcher.screens.FirstRunScreen
import com.joeysretro.joey64.launcher.screens.ImportScreen
import com.joeysretro.joey64.launcher.screens.LibraryScreen
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.storage.RomFolders
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.launch

/**
 * The app's home (plan, decision 2): first run (a ROMs folder, then Import from RetroArch) until
 * it's finished, then the library.
 * Opening Joey64 from its icon lands here; a frontend or file manager that hands over a ROM goes
 * straight to the game screen (MainActivity), as before.
 */
// RestrictedApi: androidx.core's ComponentActivity marks its override of the public
// Activity.dispatchKeyEvent as library-internal, so lint flags the override and the super call.
@SuppressLint("RestrictedApi")
class LauncherActivity : ComponentActivity() {
    private val keys = KeyNormaliser { super.dispatchKeyEvent(it) }
    private val model: LibraryModel by viewModels()
    private val importModel: ImportModel by viewModels()
    private val updates: UpdateModel by viewModels()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.dispatch(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The session log (files/logs) starts here when the app opens to the launcher, so first run, the
        // import and library reads are in what a tester sends, not only what happens once a game is up.
        LogFiles.start(this, intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent { LauncherApp(model, importModel, updates) }
    }
}

@Composable
private fun LauncherApp(model: LibraryModel, importModel: ImportModel, updates: UpdateModel) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val settings by store.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val s = settings
    JoeyTheme(s?.theme ?: com.joeysretro.joey64.launcher.theme.ThemeChoice.Dark) {
        val landing = remember { FocusLanding() }
        // A controller screen first: focus shows at once, even after a tap opened the app.
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }

        Column(Modifier.fillMaxSize().background(JoeyTheme.colors.page)) {
            if (s == null) return@Column
            val folders = s.romFolders.map { it.toUri() }
            if (folders.isNotEmpty()) {
                LifecycleResumeEffect(folders) {
                    model.refresh(folders)
                    onPauseOrDispose { }
                }
            }
            val home = folders.isNotEmpty() && s.setupDone
            var place by remember { mutableStateOf(RailPlace.Library) }
            var settingsHints by remember { mutableStateOf(listOf(Hint("B", "Back"))) }
            var onRail by remember { mutableStateOf(false) }
            if (home) UpdateChecks(updates)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LauncherRail(if (home) place else RailPlace.Library, enabled = home, onFocused = { onRail = it }) { place = it }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    ProvideScreenLayout {
                        CompositionLocalProvider(LocalFocusLanding provides landing) {
                            when {
                                folders.isEmpty() -> FirstRunScreen(model) { trees ->
                                    scope.launch {
                                        for (tree in trees) {
                                            RomFolders.keep(context, tree, write = false)
                                            store.addRomFolder(tree.toString())
                                        }
                                    }
                                }
                                // Then Import from RetroArch, offered once; Skip or Continue finishes first run.
                                !s.setupDone -> ImportScreen(importModel, s.dataFolder) { scope.launch { store.setSetupDone() } }
                                place == RailPlace.Settings -> SettingsScreen(updates, onHints = { settingsHints = it }) { place = RailPlace.Library }
                                else -> LibraryScreen(model)
                            }
                        }
                    }
                    if (home) UpdatedToast(updates, Modifier.align(Alignment.TopEnd))
                }
            }
            HintRow(when {
                !home -> listOf(Hint("A", "Select"))
                onRail -> listOf(Hint("A", "Open"))
                place == RailPlace.Settings -> settingsHints
                else -> listOf(Hint("A", "Play"))
            })
            if (home) updates.prompt.collectAsStateWithLifecycle().value?.let { UpdatePopup(it, updates) }
        }
    }
}

/**
 * The updater's checks (plan, Phase 0b): on every fresh start and every 15 minutes while the
 * launcher is in front (a failed check is tried again a minute later), never while a game is up,
 * since the launcher is paused then. Coming back from Android's settings picks up a waiting install.
 */
@Composable
private fun UpdateChecks(updates: UpdateModel) {
    LifecycleResumeEffect(Unit) {
        updates.resumed()
        updates.autoCheck()
        onPauseOrDispose { }
    }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { delay(60_000L); updates.autoCheck() }
        }
    }
}

/** "Updated, Joey64 is now 0.3.0" on the first start after an update, for 5 seconds. */
@Composable
private fun UpdatedToast(updates: UpdateModel, modifier: Modifier) {
    val version = updates.updated.collectAsStateWithLifecycle().value ?: return
    LaunchedEffect(version) { delay(5_000L); updates.toastShown() }
    JoeyToast(R.drawable.ic_circle_check, "Updated", "Joey64 is now $version", "What's new is in Settings \u203a About", modifier)
}
