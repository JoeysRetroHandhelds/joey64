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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "<What> for" (approved Controls mockup 8, also Settings › Video): All games, the games with
 * their own settings (X removes them, after asking: review sheet M28), and Pick a game. One popup
 * for every page with per-game settings; [what] names them in the question ("audio", "touch controls").
 */
@Composable
fun GamesForPopup(
    title: String,
    subtitle: String,
    target: String?,
    gamesWithOwn: () -> List<String>,
    changeCount: (String) -> Int,
    /** What X removes, for its question: "Remove the game's [what] settings?" ("audio", "touch controls"). */
    what: String,
    onDismiss: () -> Unit,
    onPick: (String?, String?) -> Unit,
    onPickGame: () -> Unit,
    onRemoved: (String) -> Unit,
) {
    val context = LocalContext.current
    var games by remember { mutableStateOf(gamesWithOwn()) }
    // A game's own file is named by its save name made safe (ControlsStore.safe: reserved characters replaced, cut at
    // 150): the library is asked by that form, and a pick passes on the game's real save name, so its tick and title
    // show for such a name too (code audit 3, p2 L10). The counts are read here once, off the main thread (p2 L8).
    var info by remember { mutableStateOf(emptyMap<String, OwnGame>()) }
    LaunchedEffect(games) {
        info = withContext(Dispatchers.IO) {
            val db = LibraryDb.get(context)
            games.associateWith { g ->
                val save = try { db.fileNameWith(g) { com.joeysretro.joey64.controls.ControlsStore.safe(GameStates.saveNameOf(it)) } } catch (e: Exception) { null }
                    ?.let(GameStates::saveNameOf) ?: g
                val title = try { db.gameWithFile(save, GameStates::saveNameOf)?.title } catch (e: Exception) { null } ?: g
                OwnGame(save, title, try { changeCount(g) } catch (e: Exception) { 0 })
            }
        }
    }
    val chosen = { g: String -> g == target || (target != null && g == com.joeysretro.joey64.controls.ControlsStore.safe(target)) }
    var focusedGame by remember { mutableStateOf<String?>(null) }
    // X asks first (review sheet M28, approved 2026-11-11): it took all of a game's own settings at once, no undo.
    var asking by remember { mutableStateOf<String?>(null) }
    JoeyDialog(onDismiss = onDismiss, width = 470.dp,
        hints = if (focusedGame != null) listOf(Hint("A", "Select"), Hint("X", "Remove"), Hint("B", "Close"))
            else listOf(Hint("A", "Select"), Hint("B", "Close")),
        onPadButton = { code ->
            val g = focusedGame
            if (code == KeyEvent.KEYCODE_BUTTON_X && g != null) asking = g
        }) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            PopupTitle(title, subtitle)
            JoeyRow("All games", Modifier.initialFocus(target == null), detail = "Every game without its own",
                trailing = if (target == null) { _ -> PickerTick() } else null) { onPick(null, null) }
            if (games.isNotEmpty()) PopupDivider()
            for (g in games) {
                val own = info[g]
                // "" until the count is read: the row keeps its height.
                JoeyRow(own?.title ?: g, Modifier.initialFocus(chosen(g)), detail = own?.changes?.let { n -> if (n == 1) "1 change" else "$n changes" } ?: "",
                    onFocused = { if (it) focusedGame = g else if (focusedGame == g) focusedGame = null },
                    trailing = if (chosen(g)) { _ -> PickerTick() } else null) { onPick(own?.save ?: g, own?.title ?: g) }
            }
            PopupDivider()
            JoeyRow("Pick a game", value = "›") { onPickGame() }
        }
    }
    asking?.let { g ->
        val own = info[g]
        val n = own?.changes ?: 0
        ResetTabPopup("Remove the game's $what settings?",
            "${own?.title ?: g}'s " + (if (n == 1) "1 change goes" else "$n changes go") + ", and it uses All games' again.",
            keep = "Keep them", reset = "Remove", onDismiss = { asking = null }) {
            asking = null
            onRemoved(g); games = gamesWithOwn(); focusedGame = null
            if (chosen(g)) onPick(null, null)
        }
    }
}

/** Pick a game: the library; its settings start as All games' (on the first change). [exclude]: a game's title left out (Copy's own game). */
@Composable
fun PickGamePopup(subtitle: String, onDismiss: () -> Unit, exclude: String? = null, onPick: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var games by remember { mutableStateOf<List<LibraryGame>?>(null) }
    // A game whose files can't be read now (its ROMs folder can't be reached): said under the title, where A did
    // nothing before; the look-up runs off the main thread, one at a time (code audit 3, p2 L9).
    var cantRead by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        games = withContext(Dispatchers.IO) { try { LibraryDb.get(context).libraryGames().filter { it.title != exclude } } catch (e: Exception) { JLog.e(TAG, "library: $e"); emptyList() } }
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle("Pick a game", subtitle)
            cantRead?.let { PopupNote("$it: its ROMs folder can't be reached") }
            val list = games
            when {
                list == null -> PopupNote("Reading the library")
                list.isEmpty() -> PopupNote("The library has no games yet.")
                else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list, key = { it.id }) { g ->
                        JoeyRow(g.title, if (g == list.first()) Modifier.initialFocus() else Modifier) {
                            if (looking) return@JoeyRow
                            looking = true
                            scope.launch {
                                val save = withContext(Dispatchers.IO) {
                                    try { GameLauncher(context, LibraryDb.get(context)).saveNameFor(g) } catch (e: Exception) { JLog.e(TAG, "${g.title}: $e"); null }
                                }
                                looking = false
                                if (save != null) onPick(save, g.title) else { JLog.e(TAG, "no readable file for ${g.title}"); cantRead = g.title }
                            }
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

/** A game with its own settings in a "<what> for" list: its real save name, title and how many rows it changes. */
private data class OwnGame(val save: String, val title: String, val changes: Int)

private const val TAG = "Joey64Games"
