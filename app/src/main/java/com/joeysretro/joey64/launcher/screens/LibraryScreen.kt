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
import androidx.compose.ui.focus.focusRequester
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
 * Start (or a long-press) opens the focused game's options ([GamePopups]: Play, Details, Add to
 * collection). Above the grid, the Filter button (Phase 5, approved 2026-11-04) names what's shown
 * and is blue when it isn't All games; A opens [ShowPopup]. Y opens Search ([onSearch]).
 */
@Composable
fun LibraryScreen(model: LibraryModel, onSearch: () -> Unit) {
    val games by model.games.collectAsStateWithLifecycle()
    val reading by model.reading.collectAsStateWithLifecycle()
    val art by model.artProgress.collectAsStateWithLifecycle()
    val artPaused by model.artPaused.collectAsStateWithLifecycle()
    val view by model.view.collectAsStateWithLifecycle()
    val facts by model.facts.collectAsStateWithLifecycle()
    val landing = LocalFocusLanding.current
    val context = LocalContext.current
    // On opening, and after picking what to show: focus lands on the first game.
    LaunchedEffect(view) { landing?.arm() }
    if (model.offline.collectAsStateWithLifecycle().value) OfflinePopup(model::offlineSeen)
    model.recovery.collectAsStateWithLifecycle().value?.let { offer ->
        RecoveryPopup(offer) { carryOn -> model.recoveryAnswered(carryOn) { context.startActivity(it) } }
    }
    model.askContinue.collectAsStateWithLifecycle().value?.let { ask ->
        AskContinuePopup(ask) { carryOn -> model.continueAnswered(carryOn) { context.startActivity(it) } }
    }
    var showing by remember { mutableStateOf(false) }
    if (showing) ShowPopup(model) { showing = false }

    val c = JoeyTheme.colors
    val all = games ?: return
    Column(Modifier.fillMaxSize()) {
        if (all.isEmpty()) {
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
        // The filter chip, and at the right end of its line (Joey 2026-11-07) covers downloading, the same
        // progress line as reading, or why they paused.
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(start = JoeyTheme.spacing.margin, end = JoeyTheme.spacing.margin, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            com.joeysretro.joey64.launcher.components.PageChip(view.label, com.joeysretro.joey64.R.drawable.ic_filter,
                on = view != com.joeysretro.joey64.launcher.library.LibraryView.All) { showing = true }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            art?.takeIf { reading == null }?.let { a ->
                Column {
                    Text("Getting box art", style = JoeyTheme.type.note, color = c.textDim, modifier = Modifier.padding(bottom = 6.dp))
                    ProgressLine(a, width = 320.dp, centred = false)
                }
            }
            if (artPaused && art == null && reading == null) {
                Text("Box art paused · ScreenScraper is busy. Joey64 will try again later.", style = JoeyTheme.type.note, color = c.textDim)
            }
        }
        val f = facts
        val list = remember(all, view, f) { if (f == null) all else com.joeysretro.joey64.launcher.library.LibraryViews.show(all, view, f) }
        // Taking the last game out of a collection from its own options empties the view: the grid
        // stays composed (empty), so the popup it holds stays up and B steps back through it.
        if (list.isEmpty()) Text("No games here yet", style = JoeyTheme.type.note, color = c.textDim,
            modifier = Modifier.padding(start = JoeyTheme.spacing.margin, top = 20.dp))
        // A new view starts at its first game.
        androidx.compose.runtime.key(view) { GamesGrid(list, model, onSearch) }
    }
}

/**
 * A grid of games (the library, a view of it, or Search's results): A plays, Start or a
 * long-press opens the game's options, Y [onSearch]. Focus starts on the first game.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun GamesGrid(list: List<LibraryGame>, model: LibraryModel, onSearch: () -> Unit, firstFocus: Boolean = true, onFocusGame: (Boolean) -> Unit = {},
    /** Focuses the first tile (Search's Enter: the first result, not the tile under the box). */
    firstTile: androidx.compose.ui.focus.FocusRequester? = null) {
    val context = LocalContext.current
    // The game whose tile has focus, for Start; null while focus is elsewhere.
    var focusedGame by remember { mutableStateOf<LibraryGame?>(null) }
    var popup by remember { mutableStateOf<Pair<Long, GamePopup>?>(null) }
    val currentFocused by rememberUpdatedState(focusedGame)
    val search by rememberUpdatedState(onSearch)
    LaunchedEffect(focusedGame != null) { onFocusGame(focusedGame != null) }
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { control ->
            val g = currentFocused
            when {
                control == Control.Options && g != null -> { popup = g.id to GamePopup.Options; true }
                control == Control.Search -> { search(); true }
                else -> false
            }
        }
        ControlBus.setHandler(handler)
        onDispose { ControlBus.clearHandler(handler) }
    }
    popup?.let { (id, which) ->
        val g = list.firstOrNull { it.id == id } ?: model.games.value?.firstOrNull { it.id == id }
        if (g == null) popup = null
        else GamePopups(g, which, model, onPopup = { next -> popup = next?.let { id to it } }) { model.playAsked(g) { context.startActivity(it) } }
    }
    run {
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
                GameTile(g, model.cover(g), Modifier.initialFocus(firstFocus && i == 0)
                    .then(if (i == 0 && firstTile != null) Modifier.focusRequester(firstTile) else Modifier),
                    onFocus = { on -> if (on) focusedGame = g else if (focusedGame?.id == g.id) focusedGame = null },
                    onOptions = { popup = g.id to GamePopup.Options }) { model.playAsked(g) { context.startActivity(it) } }
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

/**
 * Settings › Saves › Continue on Play is Ask (approved Saves mockup 2026-11-07, screen 4): the game's
 * name, when it was quit, Continue or Start over. B closes it and nothing starts.
 */
@Composable
internal fun AskContinuePopup(ask: LibraryModel.AskContinue, onAnswer: (Boolean?) -> Unit) {
    JoeyDialog(onDismiss = { onAnswer(null) }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 440.dp) {
        Column {
            PopupTitle(ask.game.title, "You quit this game ${quitAgo(ask.quitAt, System.currentTimeMillis())}. Go on from there?")
            JoeyOption("Continue", modifier = Modifier.initialFocus()) { onAnswer(true) }
            JoeyOption("Start over") { onAnswer(false) }
        }
    }
}

/** "2 hours ago", "yesterday" ... for the Ask popup. */
internal fun quitAgo(at: Long, now: Long): String {
    val min = ((now - at) / 60_000L).coerceAtLeast(0)
    return when {
        min < 1 -> "less than a minute ago"
        min == 1L -> "a minute ago"
        min < 60 -> "$min minutes ago"
        min < 120 -> "an hour ago"
        min < 24 * 60 -> "${min / 60} hours ago"
        min < 48 * 60 -> "yesterday"
        else -> "${min / (24 * 60)} days ago"
    }
}

/**
 * After a game closed without quitting (approved problem screens, 2026-11-05): carry on from the
 * recovery state, or drop it. [onAnswer] true = Continue, false = Not now (the state is deleted),
 * null = B: only closed, the state stays, and the popup comes back when the launcher is in front again.
 */
@Composable
private fun RecoveryPopup(offer: LibraryModel.RecoveryOffer, onAnswer: (Boolean?) -> Unit) {
    val ago = when (val m = offer.minutesBefore) {
        0 -> "less than a minute"
        1 -> "1 minute"
        else -> "$m minutes"
    }
    JoeyDialog(onDismiss = { onAnswer(null) }, hints = listOf(Hint("A", "Select")), width = 440.dp) {
        Column {
            PopupTitle("Joey64 closed while you were playing")
            PromptText(androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = JoeyTheme.colors.text))
                append(offer.game.title)
                pop()
                append(" has a recovery state from $ago before it closed. Carry on from there?")
            })
            JoeyOption("Continue", modifier = Modifier.initialFocus()) { onAnswer(true) }
            JoeyOption("Not now") { onAnswer(false) }
        }
    }
}
