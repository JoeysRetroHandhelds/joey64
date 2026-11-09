package com.joeysretro.joey64.launcher.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.ControllerTextField
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.library.LibraryViews
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * Search (approved 2026-11-04, remaining_l.html): a full-width field at the top with a live
 * "N games" count; it opens typing, the Android keyboard over the bottom. The results are a games
 * grid like the library's. After typing: A Play, Start Options, Y Edit search, B Clear (B with
 * nothing typed goes back to the library, [onClose]). Every game is searched, whatever the
 * library's Filter button shows.
 */
@Composable
fun SearchScreen(model: LibraryModel, onHints: (List<Hint>) -> Unit, onClose: () -> Unit) {
    val games = model.games.collectAsStateWithLifecycle().value.orEmpty()
    // Play here asks too (Settings › Saves › Continue on Play).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    model.askContinue.collectAsStateWithLifecycle().value?.let { ask ->
        AskContinuePopup(ask) { carryOn -> model.continueAnswered(carryOn) { ctx.startActivity(it) } }
    }
    NeedsBiosPopup(model) { ctx.startActivity(it) }
    StartFailedPopup(model)
    LostFolderPopup(model)
    NeedsCartPopup(model) { ctx.startActivity(it) }
    var query by rememberSaveable { mutableStateOf("") }
    // Y on the results: back into the field, typing. Bumped to make a fresh field that starts typing.
    var edit by remember { mutableIntStateOf(0) }
    var onGame by remember { mutableStateOf(false) }
    val landing = LocalFocusLanding.current
    val found = remember(games, query) { LibraryViews.search(games, query) }
    val c = JoeyTheme.colors
    val firstResult = remember { androidx.compose.ui.focus.FocusRequester() }

    // The results went (cleared, or nothing matches): no game has focus any more.
    if (found.isEmpty() && onGame) onGame = false
    LaunchedEffect(onGame, query.isEmpty()) {
        onHints(when {
            onGame -> listOf(Hint("A", "Play"), Hint("Start", "Options"), Hint("Y", "Edit search"), Hint("B", "Clear"))
            else -> listOf(Hint("A", "Type"), Hint("B", if (query.isEmpty()) "Back" else "Clear"))
        })
    }
    BackHandler {
        if (query.isEmpty()) onClose() else { query = ""; edit++; landing?.arm() }
    }
    Column(Modifier.fillMaxSize()) {
        key(edit) {
            ControllerTextField(query, { query = it }, "Search your games",
                Modifier.initialFocus().padding(start = JoeyTheme.spacing.margin, end = JoeyTheme.spacing.margin, top = 12.dp),
                startTyping = true,
                // Enter goes to the first result (moving focus down landed on the tile under the box).
                onDone = { found.isNotEmpty() && runCatching { firstResult.requestFocus() }.isSuccess },
                trailing = {
                    if (query.isNotBlank()) Text(if (found.size == 1) "1 game" else "${found.size} games",
                        style = JoeyTheme.type.note, color = c.textDim)
                })
        }
        if (query.isNotBlank() && found.isEmpty()) {
            Text("No games match", style = JoeyTheme.type.note, color = c.textDim,
                modifier = Modifier.padding(start = JoeyTheme.spacing.margin, top = 20.dp))
        } else if (found.isNotEmpty()) {
            GamesGrid(found, model, onSearch = { edit++; landing?.arm() }, firstFocus = false, onFocusGame = { onGame = it }, firstTile = firstResult)
        }
    }
}
