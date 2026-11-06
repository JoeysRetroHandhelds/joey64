package com.joeysretro.joey64.launcher

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
import com.joeysretro.joey64.launcher.screens.LibraryScreen
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.storage.RomFolders
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.launch

/**
 * The app's home (plan, decision 2): first run until a ROMs folder is picked, then the library.
 * Opening Joey64 from its icon lands here; a frontend or file manager that hands over a ROM goes
 * straight to the game screen (MainActivity), as before.
 */
// RestrictedApi: androidx.core's ComponentActivity marks its override of the public
// Activity.dispatchKeyEvent as library-internal, so lint flags the override and the super call.
@SuppressLint("RestrictedApi")
class LauncherActivity : ComponentActivity() {
    private val keys = KeyNormaliser { super.dispatchKeyEvent(it) }
    private val model: LibraryModel by viewModels()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.dispatch(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent { LauncherApp(model) }
    }
}

@Composable
private fun LauncherApp(model: LibraryModel) {
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
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LauncherRail(RailPlace.Library)
                Box(Modifier.weight(1f).fillMaxSize()) {
                    ProvideScreenLayout {
                        CompositionLocalProvider(LocalFocusLanding provides landing) {
                            if (folders.isEmpty()) FirstRunScreen(model) { tree ->
                                scope.launch {
                                    RomFolders.keep(context, tree, write = false)
                                    store.addRomFolder(tree.toString())
                                }
                            } else LibraryScreen(model)
                        }
                    }
                }
            }
            HintRow(if (folders.isEmpty()) listOf(Hint("A", "Select")) else listOf(Hint("A", "Play")))
        }
    }
}
