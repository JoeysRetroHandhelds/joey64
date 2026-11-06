package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.controls.ControlBus
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import com.joeysretro.joey64.launcher.boxart.rememberCover
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.PromptText
import java.io.File
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
import androidx.compose.runtime.remember
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
 * box-art tiles in as many columns as the screen shape gives (4 at 16:9). A game without a cover
 * (none found, or not downloaded yet) shows its name on a plain tile, as the mockups draw it. A plays.
 * Focus starts on the first game and comes back to the one you played.
 *
 * Start (or a long-press) opens the focused game's options ([GamePopups]: Play, Details).
 * Not built yet, so not shown: the Filter button (collections and filters, Phase 5) and Search.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun LibraryScreen(model: LibraryModel) {
    val games by model.games.collectAsStateWithLifecycle()
    val reading by model.reading.collectAsStateWithLifecycle()
    val landing = LocalFocusLanding.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { landing?.arm() }
    if (model.offline.collectAsStateWithLifecycle().value) OfflinePopup(model::offlineSeen)

    // The game whose tile has focus, for Start; null while focus is on the rail.
    var focusedGame by remember { mutableStateOf<LibraryGame?>(null) }
    var popup by remember { mutableStateOf<Pair<Long, GamePopup>?>(null) }
    val currentFocused by rememberUpdatedState(focusedGame)
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { control ->
            val g = currentFocused
            if (control == Control.Options && g != null) { popup = g.id to GamePopup.Options; true } else false
        }
        ControlBus.setHandler(handler)
        onDispose { ControlBus.clearHandler(handler) }
    }

    val c = JoeyTheme.colors
    val list = games ?: return
    popup?.let { (id, which) ->
        val g = list.firstOrNull { it.id == id }
        if (g == null) popup = null
        else GamePopups(g, which, model, onPopup = { next -> popup = next?.let { id to it } }) { model.play(g) { context.startActivity(it) } }
    }
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
        // Focus moving to a row half off screen scrolls it fully in with a row gap to spare, so the
        // selected tile and its outline are never the cut-off ones (approved grid, 2026-11-04).
        // Compose's default stops with the tile flush against the hint row, outline cut.
        val gap = with(LocalDensity.current) { 16.dp.toPx() }
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(gap) { RowGapSpec(gap) }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(LocalScreenLayout.current.boxArtColumns),
            contentPadding = PaddingValues(horizontal = JoeyTheme.spacing.margin, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().focusRestorer().focusGroup(),
        ) {
            itemsIndexed(list, key = { _, g -> g.id }) { i, g ->
                GameTile(g, model.cover(g), Modifier.initialFocus(i == 0),
                    onFocus = { on -> if (on) focusedGame = g else if (focusedGame?.id == g.id) focusedGame = null },
                    onOptions = { popup = g.id to GamePopup.Options }) { model.play(g) { context.startActivity(it) } }
            }
        }
        }
    }
}

/** Scrolls an item into view with [margin] around it; an item already that far inside stays put. */
@OptIn(ExperimentalFoundationApi::class)
private class RowGapSpec(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - margin
        val end = offset + size + margin
        return when {
            start < 0f -> start
            end > containerSize -> minOf(end - containerSize, start)
            else -> 0f
        }
    }
}

/**
 * A game's tile: its cover, filling the tile (libretro's are ~1.43:1, the tile 1.45:1, so a sliver
 * is cropped), or without one, its name on a plain tile the size and shape of a cover. While a
 * cover loads the tile is plain, so a name doesn't flash up first.
 */
@Composable
private fun GameTile(game: LibraryGame, cover: File?, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit,
                     onOptions: () -> Unit, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    LaunchedEffect(focused) { onFocus(focused) }
    val picture by rememberCover(cover)
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.45f)
            .coverSelection(focused)
            .background(c.chip, shape)
            .pressable(source, onLongClick = onOptions, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val p = picture
        when {
            p != null -> Image(p, contentDescription = game.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
            cover == null -> Text(game.title, style = JoeyTheme.type.tile, color = c.textSecondary, textAlign = TextAlign.Center,
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
        }
    }
}

/**
 * No connection when covers were due (approved 2026-11-05, scratchpad build_offline.py): once per
 * scan that finds new games and can't connect. Covers then fill in by themselves once online.
 * The approved text also names cheats; they say so once cheats download (Phase 6).
 */
@Composable
private fun OfflinePopup(onOk: () -> Unit) {
    JoeyDialog(onDismiss = onOk, hints = listOf(Hint("A", "OK")), width = 440.dp) {
        Column {
            PopupTitle("Go online to get box art")
            PromptText(AnnotatedString("Joey64 couldn't connect. Box art downloads by itself next time you're online."))
            JoeyOption("OK", modifier = Modifier.initialFocus(), onClick = onOk)
        }
    }
}
