package com.joeysretro.joey64.launcher.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupNote
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.data.LibraryGame
import com.joeysretro.joey64.launcher.library.GameLauncher
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.states.GameStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "<What> for" (approved Controls mockup 8, also Settings › Video): All games, the games with
 * their own settings (X removes them), and Pick a game. One popup for every page with per-game
 * settings; [what] names them ("Controls", "Video").
 */
@Composable
fun GamesForPopup(
    title: String,
    subtitle: String,
    target: String?,
    gamesWithOwn: () -> List<String>,
    changeCount: (String) -> Int,
    removeLabel: String,
    onDismiss: () -> Unit,
    onPick: (String?, String?) -> Unit,
    onPickGame: () -> Unit,
    onRemoved: (String) -> Unit,
) {
    val context = LocalContext.current
    var games by remember { mutableStateOf(gamesWithOwn()) }
    var titles by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(games) {
        titles = withContext(Dispatchers.IO) {
            games.associateWith { g ->
                try { LibraryDb.get(context).gameWithFile(g, GameStates::saveNameOf)?.title ?: g } catch (e: Exception) { g }
            }
        }
    }
    var focusedGame by remember { mutableStateOf<String?>(null) }
    JoeyDialog(onDismiss = onDismiss, width = 470.dp,
        hints = if (focusedGame != null) listOf(Hint("A", "Select"), Hint("X", removeLabel), Hint("B", "Back"))
            else listOf(Hint("A", "Select"), Hint("B", "Back")),
        onPadButton = { code ->
            val g = focusedGame
            if (code == KeyEvent.KEYCODE_BUTTON_X && g != null) {
                onRemoved(g); games = gamesWithOwn(); focusedGame = null
                if (g == target) onPick(null, null)
            }
        }) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            PopupTitle(title, subtitle)
            JoeyRow("All games", Modifier.initialFocus(target == null), detail = "Every game without its own",
                trailing = if (target == null) { _ -> PickerTick() } else null) { onPick(null, null) }
            if (games.isNotEmpty()) PopupDivider()
            for (g in games) {
                val n = changeCount(g)
                JoeyRow(titles[g] ?: g, Modifier.initialFocus(g == target), detail = if (n == 1) "1 change" else "$n changes",
                    onFocused = { if (it) focusedGame = g else if (focusedGame == g) focusedGame = null },
                    trailing = if (g == target) { _ -> PickerTick() } else null) { onPick(g, titles[g] ?: g) }
            }
            PopupDivider()
            JoeyRow("Pick a game", value = "›") { onPickGame() }
        }
    }
}

/** Pick a game: the library; its settings start as All games' (on the first change). [exclude]: a game's title left out (Copy's own game). */
@Composable
fun PickGamePopup(subtitle: String, onDismiss: () -> Unit, exclude: String? = null, onPick: (String, String) -> Unit) {
    val context = LocalContext.current
    var games by remember { mutableStateOf<List<LibraryGame>?>(null) }
    LaunchedEffect(Unit) {
        games = withContext(Dispatchers.IO) { try { LibraryDb.get(context).libraryGames().filter { it.title != exclude } } catch (e: Exception) { JLog.e(TAG, "library: $e"); emptyList() } }
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Back")), width = 470.dp) {
        Column {
            PopupTitle("Pick a game", subtitle)
            val list = games
            when {
                list == null -> PopupNote("Reading the library")
                list.isEmpty() -> PopupNote("The library has no games yet.")
                else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list, key = { it.id }) { g ->
                        JoeyRow(g.title, if (g == list.first()) Modifier.initialFocus() else Modifier) {
                            val db = LibraryDb.get(context)
                            val save = GameLauncher(context, db).saveNameFor(g)
                            if (save != null) onPick(save, g.title) else JLog.e(TAG, "no readable file for ${g.title}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PickerTick() {
    Icon(painterResource(R.drawable.ic_tick), contentDescription = "Chosen", tint = JoeyTheme.colors.gold, modifier = Modifier.size(18.dp))
}

private const val TAG = "Joey64Games"
