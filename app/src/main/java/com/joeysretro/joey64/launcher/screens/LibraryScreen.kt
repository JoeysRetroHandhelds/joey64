package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    // Settings › Library's order, regions and one version per game (approved 2026-11-08); hidden games left out.
    val games by model.shown.collectAsStateWithLifecycle()
    val reading by model.reading.collectAsStateWithLifecycle()
    val art by model.artProgress.collectAsStateWithLifecycle()
    val artPaused by model.artPaused.collectAsStateWithLifecycle()
    val view by model.view.collectAsStateWithLifecycle()
    val facts by model.facts.collectAsStateWithLifecycle()
    val landing = LocalFocusLanding.current
    val context = LocalContext.current
    // On opening, and after picking what to show: focus lands on the first game. Back from a game's page or a game,
    // on the game you left from (GamesGrid's keepPlace).
    LaunchedEffect(view) { landing?.arm() }
    if (model.offline.collectAsStateWithLifecycle().value) OfflinePopup(model::offlineSeen)
    model.recovery.collectAsStateWithLifecycle().value?.let { offer ->
        RecoveryPopup(offer) { carryOn -> model.recoveryAnswered(carryOn) { context.startActivity(it) } }
    }
    model.askContinue.collectAsStateWithLifecycle().value?.let { ask ->
        AskContinuePopup(ask) { carryOn -> model.continueAnswered(carryOn) { context.startActivity(it) } }
    }
    // Reports, asked once (approved reports mockup 2026-11-08, screen 5); never over another question.
    val askingSomething = model.recovery.collectAsStateWithLifecycle().value != null || model.askContinue.collectAsStateWithLifecycle().value != null
    if (model.askReports.collectAsStateWithLifecycle().value && !askingSomething) AskReportsPopup(model::reportsAnswered)
    NeedsBiosPopup(model) { context.startActivity(it) }
    NeedsCartPopup(model) { context.startActivity(it) }
    StartFailedPopup(model)
    NetEndedPopup(model)
    LostFolderPopup(model)
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
                ProgressLine(a, width = 160.dp, label = "Getting box art")
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
        androidx.compose.runtime.key(view) { GamesGrid(list, model, onSearch, keepPlace = true) }
    }
}

/**
 * A grid of games (the library, a view of it, or Search's results): A plays, Start or a
 * long-press opens the game's options, Y [onSearch]. Focus starts on the first game ([keepPlace]: on the one you
 * left from).
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun GamesGrid(list: List<LibraryGame>, model: LibraryModel, onSearch: () -> Unit, firstFocus: Boolean = true, onFocusGame: (Boolean) -> Unit = {},
    /** Focuses the first tile (Search's Enter: the first result, not the tile under the box). */
    firstTile: androidx.compose.ui.focus.FocusRequester? = null,
    /**
     * The library's own grid: it comes back where you left it ([LibraryModel.libraryFocus]), focus on that game and the
     * same scroll, after a game's page or a game put it away. Found on the Odin 3 Portal 2026-11-11: closing Game
     * settings, or quitting a game, focused the first cover.
     */
    keepPlace: Boolean = false,
    /** Without [keepPlace]: the game focus lands on when the landing is armed (Search, back from a game's page). */
    landOn: Long? = null,
    /** A game's tile took focus, or was tapped or long-pressed. */
    onFocused: (Long) -> Unit = {}) {
    val context = LocalContext.current
    // Settings › Library › Covers (approved mockup 2026-11-08): cover size and names.
    val store = remember { com.joeysretro.joey64.launcher.settings.SettingsStore(context) }
    val look = store.settings.collectAsStateWithLifecycle(initialValue = null).value
    val size = look?.coverSize ?: com.joeysretro.joey64.launcher.library.CoverSize.Medium
    val names = look?.names ?: com.joeysretro.joey64.launcher.library.NamesMode.DEFAULT
    val style = look?.nameStyle ?: com.joeysretro.joey64.launcher.library.NameStyle.Short
    val greyed by model.greyed.collectAsStateWithLifecycle()
    // keepPlace: the game focus lands on, read as the grid is made (null: the first game, as before).
    val landId = remember(list, landOn) { (if (keepPlace) model.libraryFocus else landOn)?.takeIf { id -> list.any { it.id == id } } }
    val landing = LocalFocusLanding.current
    fun keep(g: LibraryGame) { if (keepPlace) model.libraryFocus = g.id; onFocused(g.id) }
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
        val cols = com.joeysretro.joey64.launcher.library.LibraryArrange.columns(LocalScreenLayout.current.boxArtColumns, size)
        // keepPlace: the scroll it had (made again after a game's page, the grid started at the top).
        val start = remember { if (keepPlace) model.libraryScroll else 0 to 0 }
        val gridState = rememberLazyGridState(start.first, start.second)
        if (keepPlace) LaunchedEffect(gridState) {
            snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }.collect { model.libraryScroll = it }
        }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // Up and Down go by index (the tile [cols] before or after), so the column stays. Compose's own search goes by
        // where tiles are on screen, and with a key held the next row isn't laid out yet: holding Up from the last game
        // landed in another column (Joey 2026-11-10). Down from a row with nothing under it goes to the last game.
        val tileFocus = remember { HashMap<Long, FocusRequester>() }
        var focusedIndex by remember { mutableStateOf(-1) }
        var moving by remember { mutableStateOf(false) }
        val listNow by rememberUpdatedState(list)
        // The list changed under the focused game (Recently played puts the game just played first, off screen; found on
        // the Odin 3 Portal 2026-11-11 with nothing lit): its row is scrolled back in and it keeps focus.
        LaunchedEffect(list) {
            val g = currentFocused ?: return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
            val index = listNow.indexOfFirst { it.id == g.id }
            if (index < 0 || gridState.layoutInfo.visibleItemsInfo.any { it.index == index }) return@LaunchedEffect
            gridState.scrollToItem(index)
            androidx.compose.runtime.withFrameNanos { }
            tileFocus[g.id]?.requestFocus()
        }
        // The game to land on is off screen (the list changed while away: Recently played puts the game just played
        // first): its row is scrolled in, and focus lands on it as it's composed.
        if (landId != null && landing?.pending == true) LaunchedEffect(landId) {
            val laidOut = snapshotFlow { gridState.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
            val index = listNow.indexOfFirst { it.id == landId }
            if (index >= 0 && laidOut.visibleItemsInfo.none { it.index == index }) gridState.scrollToItem(index)
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(gap) { RowGapSpec(gap) }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            contentPadding = PaddingValues(horizontal = JoeyTheme.spacing.margin, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().focusRestorer().focusGroup().onPreviewKeyEvent { event ->
                val k = event.nativeKeyEvent
                val up = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                if (!up && k.keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return@onPreviewKeyEvent false
                // From the focused game's place now: a reorder moves it without a new focus event.
                val from = currentFocused?.let { f -> listNow.indexOfFirst { it.id == f.id } } ?: focusedIndex
                val games = listNow
                if (from !in games.indices) return@onPreviewKeyEvent false
                if (up && from < cols) return@onPreviewKeyEvent false   // the top row: up to the filter chip as before
                if (k.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
                if (moving) return@onPreviewKeyEvent true
                val to = when {
                    up -> from - cols
                    from + cols < games.size -> from + cols
                    from / cols < (games.size - 1) / cols -> games.size - 1
                    else -> return@onPreviewKeyEvent true   // the last row: stay
                }
                val id = games[to].id
                moving = true
                scope.launch {
                    try {
                        // Not laid out yet: scroll a row at a time until it is (a held key outruns the layout).
                        var n = 0
                        while (tileFocus[id] == null && n++ < 8) {
                            val row = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: break
                            gridState.scrollBy((if (up) -1f else 1f) * (row + gap))
                            androidx.compose.runtime.withFrameNanos { }
                        }
                        if (tileFocus[id] == null) { gridState.scrollToItem(to); androidx.compose.runtime.withFrameNanos { } }
                        tileFocus[id]?.requestFocus()
                    } finally { moving = false }
                }
                true
            },
        ) {
            itemsIndexed(list, key = { _, g -> g.id }) { i, g ->
                val own = remember { FocusRequester() }
                androidx.compose.runtime.DisposableEffect(g.id) { tileFocus[g.id] = own; onDispose { if (tileFocus[g.id] === own) tileFocus.remove(g.id) } }
                GameTile(g, model.cover(g), names, com.joeysretro.joey64.launcher.library.LibraryArrange.title(g, style), greyed = g.id in greyed,
                    modifier = Modifier.initialFocus(if (landId != null) g.id == landId else firstFocus && i == 0).focusRequester(own)
                    .then(if (i == 0 && firstTile != null) Modifier.focusRequester(firstTile) else Modifier),
                    onFocus = { on -> if (on) { focusedGame = g; focusedIndex = i; keep(g) } else if (focusedGame?.id == g.id) { focusedGame = null; focusedIndex = -1 } },
                    onOptions = { keep(g); popup = g.id to GamePopup.Options }) { keep(g); model.playAsked(g) { context.startActivity(it) } }
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
 * is cropped), or without one a plain tile the size and shape of a cover. While a cover loads the
 * tile is plain, so a name doesn't flash up first. Settings › Library › Names (approved 2026-11-08):
 * Only without box art puts [name] on a plain tile, Under the cover puts it under every tile, Off never.
 */
@Composable
private fun GameTile(game: LibraryGame, cover: File?, names: com.joeysretro.joey64.launcher.library.NamesMode, name: String,
                     greyed: Boolean = false, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit,
                     onOptions: () -> Unit, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    LaunchedEffect(focused) { onFocus(focused) }
    val picture by rememberCover(cover)
    val shape = RoundedCornerShape(4.dp)
    // Focus brings only the cover into view, and the name under it is taller than the row gap: the bottom row's
    // names were cut off by the hint row (Joey 2026-11-10). The whole tile comes into view instead.
    val whole = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) { androidx.compose.runtime.withFrameNanos { }; whole.bringIntoView() } }
    Column(modifier.fillMaxWidth().bringIntoViewRequester(whole)) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.45f)
            .coverSelection(focused)
            .background(c.chip, shape)
            .pressable(source, onLongClick = onOptions, onClick = onClick)
            // TalkBack (code audit 3, M33): the game's name, picture or not, and why it's greyed.
            .semantics { contentDescription = name; if (greyed) stateDescription = "Its ROMs folder can't be reached" },
        contentAlignment = Alignment.Center,
    ) {
        val p = picture
        when {
            // Greyed while its ROMs folder can't be reached (approved problem screens 2026-11-08, screen 3).
            p != null -> Image(p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape),
                alpha = if (greyed) 0.35f else 1f,
                colorFilter = if (greyed) androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }) else null)
            cover == null && names == com.joeysretro.joey64.launcher.library.NamesMode.NoArt ->
                Text(name, style = JoeyTheme.type.tile, color = if (greyed) c.textFaint else c.textSecondary, textAlign = TextAlign.Center,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp).clearAndSetSemantics { })
        }
    }
    if (names == com.joeysretro.joey64.launcher.library.NamesMode.Under) {
        // The focused game's name scrolls when it doesn't fit (Joey 2026-11-10: "titles should probably marquee");
        // the others end in "...".
        Text(name, style = JoeyTheme.type.note, color = if (focused) c.gold else if (greyed) c.textFaint else c.textSecondary, maxLines = 1,
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp).clearAndSetSemantics { }
                .then(if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE, animationMode = MarqueeAnimationMode.Immediately,
                    initialDelayMillis = 900, repeatDelayMillis = 1_500) else Modifier))
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
