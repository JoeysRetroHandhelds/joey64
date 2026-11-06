package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.coverSelection
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.ProgressLine
import com.joeysretro.joey64.launcher.components.pressable
import com.joeysretro.joey64.launcher.components.rememberFocusState
import com.joeysretro.joey64.launcher.data.LibraryGame
import com.joeysretro.joey64.launcher.layout.LocalScreenLayout
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * The library grid (approved library design, 2026-11-04): every game in the picked ROM folders,
 * box-art tiles in as many columns as the screen shape gives (4 at 16:9). Until box art comes
 * (Phase 2) a tile shows the game's name, as the mockups draw a game without art. A plays.
 * Focus starts on the first game and comes back to the one you played.
 *
 * Not built yet, so not shown: the Filter button (collections and filters, Phase 5), Search,
 * Start for options.
 */
@Composable
fun LibraryScreen(model: LibraryModel) {
    val games by model.games.collectAsStateWithLifecycle()
    val reading by model.reading.collectAsStateWithLifecycle()
    val landing = LocalFocusLanding.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { landing?.arm() }

    val c = JoeyTheme.colors
    val list = games ?: return
    Column(Modifier.fillMaxSize()) {
        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (reading != null) "Reading your games" else "No N64 games found",
                    style = JoeyTheme.type.heading, color = c.text)
                reading?.let { ProgressLine(it, Modifier.padding(top = 20.dp)) }
            }
            return
        }
        // New games appear as they're read; the bar says how far it has got.
        reading?.let { r ->
            Column(Modifier.padding(start = JoeyTheme.spacing.margin, top = 12.dp)) {
                Text("Reading your games", style = JoeyTheme.type.note, color = c.textDim, modifier = Modifier.padding(bottom = 6.dp))
                ProgressLine(r, width = 320.dp, centred = false)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(LocalScreenLayout.current.boxArtColumns),
            contentPadding = PaddingValues(horizontal = JoeyTheme.spacing.margin, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().focusRestorer().focusGroup(),
        ) {
            itemsIndexed(list, key = { _, g -> g.id }) { i, g ->
                GameTile(g, Modifier.initialFocus(i == 0)) { model.play(g) { context.startActivity(it) } }
            }
        }
    }
}

/** A game without box art: its name on a plain tile, the size and shape of a cover. */
@Composable
private fun GameTile(game: LibraryGame, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.45f)
            .coverSelection(focused)
            .background(c.chip, RoundedCornerShape(4.dp))
            .pressable(source, onClick = onClick)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(game.title, style = JoeyTheme.type.tile, color = c.textSecondary, textAlign = TextAlign.Center,
            maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}
