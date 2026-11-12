package com.joeysretro.joey64.launcher.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.achievements.AchFilter
import com.joeysretro.joey64.achievements.AchSort
import com.joeysretro.joey64.achievements.GameFilter
import com.joeysretro.joey64.achievements.GameSort
import com.joeysretro.joey64.achievements.RaAchievement
import com.joeysretro.joey64.achievements.RaBadges
import com.joeysretro.joey64.achievements.RaBridge
import com.joeysretro.joey64.achievements.RaGame
import com.joeysretro.joey64.achievements.RaLists
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.IconRow
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PageChip
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.RowTag
import com.joeysretro.joey64.launcher.components.ThinBar
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.rememberBadge
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.controls.ControlBus
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.ingame.SlotDates
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.joeysretro.joey64.launcher.components.ListPager
import com.joeysretro.joey64.launcher.components.pagedItem
import org.json.JSONObject
import java.io.File

/**
 * Achievements (plan, Phase 7; approved mockups ach_dp.html, ach2.html filters, ach3.html rows, Joey
 * 2026-11-04): the rail's page lists the games with achievements, a game's page lists its
 * achievements. Both share one header (picture, title, the line under it, the Show chip) and one
 * row ([IconRow]). Outside a game they read the progress cache ([com.joeysretro.joey64.achievements.RaCache]):
 * the game's set as it was the last time it ran. In a game the pause menu opens the game's page
 * as a panel ([InGameAchievements]) with rc_client's live list.
 */

/** A game whose achievements page is open: the library's game id and name. */
data class AchievementsGame(val gameId: Long, val title: String)

/** The rail's Achievements: the games list, and a game's page in its place (B goes back to the list). */
@Composable
fun AchievementsScreen(onHints: (List<Hint>) -> Unit, onClose: () -> Unit) {
    var open by remember { mutableStateOf<AchievementsGame?>(null) }
    var lastOpen by remember { mutableStateOf<Long?>(null) }
    // The games list's Show and Sort, kept here so they outlast a visit to a game's page (Thor 2026-11-12: Name was
    // back to Recently played after B from a game; the list is made again when it comes back).
    val filter = remember { mutableStateOf(GameFilter.All) }
    val sort = remember { mutableStateOf(GameSort.RecentlyPlayed) }
    val o = open
    if (o != null) androidx.compose.runtime.key(o) {
        GameAchievementsScreen(o, onHints) { lastOpen = o.gameId; open = null }
    } else GamesList(onHints, onClose, focusGame = lastOpen, filterState = filter, sortState = sort) { open = it }
}

/** A game's page in the launcher (from the games list, or a game's options › Achievements). */
@Composable
fun GameAchievementsScreen(game: AchievementsGame, onHints: (List<Hint>) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<GameData?>(null) }
    LaunchedEffect(game) {
        data = withContext(Dispatchers.IO) {
            val row = RaBridge.debugCache?.get(game.gameId)?.let { Triple(it.first, it.second, System.currentTimeMillis() - 3_600_000) }
                ?: LibraryDb.get(context).achievementsOf(game.gameId)
            if (row == null) GameData(null, emptyList(), 0L)
            else GameData(RaGame.parse(game.gameId, JSONObject(row.first), 0L),
                RaAchievement.forSetting(RaAchievement.parseList(row.second), com.joeysretro.joey64.achievements.RaSettings.current(context).unofficial), row.third)
        }
    }
    var showing by remember { mutableStateOf(false) }
    val tabs = remember { SetTabStep() }
    PageShell(onClose = { if (showing) showing = false else onClose() }, onTab = { tabs.step(it) }, onPage = { tabs.page(it) })
    GameAchievementsContent(com.joeysretro.joey64.launcher.components.gameName(game.title), data, live = false, onHints = onHints, showing = showing, onShow = { showing = it }, tabs = tabs, tabHint = "Set")
}

/** The pause menu's Achievements: the same page as a panel over the paused game, rc_client's live list. */
@Composable
fun InGameAchievements(title: String, focusId: Int? = null, backLabel: String = "Back to pause menu", onClose: () -> Unit) {
    var hints by remember { mutableStateOf(emptyList<Hint>()) }
    var showing by remember { mutableStateOf(false) }
    val data = remember {
        val info = RaBridge.gameInfo().takeIf { it != "null" }
        val fake = if (info == null) RaBridge.debugCache?.values?.firstOrNull() else null
        val g = (info ?: fake?.first)?.let { RaGame.parse(0, JSONObject(it), 0L) }
        GameData(g, RaAchievement.parseList(if (fake != null) fake.second else RaBridge.achievementList()), System.currentTimeMillis())
    }
    val tabs = remember { SetTabStep() }
    JoeyDialog(onDismiss = onClose, hints = hints, width = 780.dp, onPadButton = { key ->
        when (key) {
            android.view.KeyEvent.KEYCODE_BUTTON_L1 -> tabs.step(-1)
            android.view.KeyEvent.KEYCODE_BUTTON_R1 -> tabs.step(1)
            android.view.KeyEvent.KEYCODE_BUTTON_L2 -> tabs.page(-1)
            android.view.KeyEvent.KEYCODE_BUTTON_R2 -> tabs.page(1)
        }
    }) {
        Box(Modifier.heightIn(max = 520.dp)) {
            GameAchievementsContent(title, data, live = true, onHints = { hints = it.map { h -> if (h.key == "B") Hint("B", backLabel) else h } },
                showing = showing, onShow = { showing = it }, focusId = focusId, inPanel = true, tabs = tabs, tabHint = "Tab")
        }
    }
}

private data class GameData(val game: RaGame?, val list: List<RaAchievement>, val keptAt: Long)

/** L1/R1 from the page or the panel, to the set tabs (approved mockup 2026-11-08, subsets_mock.png); L2/R2 a page of the list. */
private class SetTabStep {
    var onStep: ((Int) -> Unit)? = null
    var onPage: ((Int) -> Unit)? = null
    fun step(delta: Int) { onStep?.invoke(delta) }
    fun page(dir: Int) { onPage?.invoke(dir) }
}

/** B / Back and the launcher's buttons while a page is up (as Cheats does): the page takes them. */
@Composable
private fun PageShell(onClose: () -> Unit, onTab: ((Int) -> Unit)? = null, onPage: ((Int) -> Unit)? = null) {
    BackHandler(onBack = onClose)
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { ctl ->
            when (ctl) {
                Control.TabPrev -> onTab?.invoke(-1)
                Control.TabNext -> onTab?.invoke(1)
                // L2 / R2: a page of the list (Joey 2026-11-11: "l2/r2 should work ... to scroll").
                Control.PagePrev -> onPage?.invoke(-1)
                Control.PageNext -> onPage?.invoke(1)
                else -> {}
            }
            true
        }
        ControlBus.push(handler)
        onDispose { ControlBus.pop(handler) }
    }
    val landing = LocalFocusLanding.current
    LaunchedEffect(Unit) { landing?.arm() }
}

@Composable
private fun GamesList(onHints: (List<Hint>) -> Unit, onClose: () -> Unit, focusGame: Long?,
                      filterState: androidx.compose.runtime.MutableState<GameFilter>, sortState: androidx.compose.runtime.MutableState<GameSort>,
                      onOpen: (AchievementsGame) -> Unit) {
    val context = LocalContext.current
    val c = JoeyTheme.colors
    val account by RaBridge.account.collectAsState()
    var games by remember { mutableStateOf<List<RaGame>?>(null) }
    var filter by filterState
    var sort by sortState
    var showing by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    val pager = remember { ListPager() }
    val gamesState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf(0) }
    PageShell(onClose = { if (showing) showing = false else onClose() }, onPage = { dir -> scope.launch { pager.page(gamesState, dir, count) } })
    LaunchedEffect(Unit) {
        games = withContext(Dispatchers.IO) {
            val rows = RaBridge.debugCache?.map { (id, p) -> Triple(id, p.first, 0L) } ?: LibraryDb.get(context).achievementGames()
            rows.mapNotNull { (id, json, played) ->
                try { RaGame.parse(id, JSONObject(json), played) } catch (e: Exception) { null }
            }.filter { it.achievements > 0 }
        }
    }
    var onChip by remember { mutableStateOf(false) }
    var onSortChip by remember { mutableStateOf(false) }
    LaunchedEffect(onChip, onSortChip) { onHints(listOf(Hint("A", if (onSortChip) "Change" else if (onChip) "Show" else "Open"), Hint("B", "Back"))) }
    val all = games ?: return
    val shown = remember(all, filter, sort) { RaLists.games(all, filter, sort) }
    count = shown.size
    ToTopOnChange(gamesState, filter, sort)
    val now = System.currentTimeMillis()
    val chips = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(horizontal = JoeyTheme.spacing.margin).padding(top = 14.dp, bottom = 6.dp)) {
        val a = account
        // A place on the top bar: no title, and the filter chip is the first thing on the page (Joey 2026-11-11).
        Header(
            title = null,
            line = RaLists.pageSummary(a?.display ?: "", a?.softcore, filter, shown.size, all.size),
            picture = { Picture(a?.avatar, 34.dp) },
            chip = filter.label, chipOn = filter != GameFilter.All, onChip = { showing = true }, onChipFocused = { onChip = it },
            chipFirst = all.isNotEmpty() && shown.isEmpty(),
            sortChip = sort.label, onSort = { sorting = true }, onSortFocused = { onSortChip = it }, chipsEntry = chips,
        )
        if (all.isEmpty()) Empty("Play a game that has RetroAchievements, and it shows here with your progress.", holdFocus = true)
        else if (shown.isEmpty()) Empty("No games here")
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), state = gamesState) {
            itemsIndexed(shown, key = { _, g -> g.gameId }) { i, g ->
                val first = if (focusGame != null && shown.any { it.gameId == focusGame }) g.gameId == focusGame else g === shown.first()
                IconRow(g.title, RaLists.gameLine(g), Modifier.upTo(i == 0, chips).initialFocus(first).pagedItem(pager, i), bar = g.fraction,
                    rightText = if (g.mastered) gold("Mastered") else if (g.lastPlayed > 0) AnnotatedString(SlotDates.label(g.lastPlayed, now)) else null,
                    picture = { Picture(badgeFile(g.badge), 40.dp) }) { onOpen(AchievementsGame(g.gameId, g.title)) }
            }
        }
    }
    if (showing) AchPickPopup("Show", GameFilter.entries.map { it.label }, RaLists.counts(all).let { m -> GameFilter.entries.map { m[it] } },
        filter.ordinal, { filter = GameFilter.entries[it] }) { showing = false }
    if (sorting) AchPickPopup("Sort", GameSort.entries.map { it.label }, null, sort.ordinal, { sort = GameSort.entries[it] }) { sorting = false }
}

@Composable
private fun GameAchievementsContent(
    title: String, data: GameData?, live: Boolean, onHints: (List<Hint>) -> Unit,
    showing: Boolean, onShow: (Boolean) -> Unit, focusId: Int? = null, inPanel: Boolean = false,
    tabs: SetTabStep? = null, tabHint: String = "Set",
) {
    val c = JoeyTheme.colors
    var filter by remember { mutableStateOf(AchFilter.All) }
    var sort by remember { mutableStateOf(AchSort.RaOrder) }
    var onChip by remember { mutableStateOf(false) }
    var onSortChip by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    // The set tabs (approved mockup 2026-11-08): only for a game with more than one set. The filter
    // and sort stay when the tab changes; in game, the panel opens on the tab of [focusId]'s set.
    val sets = data?.game?.sets.orEmpty()
    val tabbed = sets.size > 1
    var setPicked by remember { mutableStateOf(-1) }
    val setIdx = if (setPicked in sets.indices) setPicked
        else focusId?.let { id -> data?.list?.firstOrNull { it.id == id } }?.let { a -> sets.indexOfFirst { it.id == a.set } }?.takeIf { it >= 0 } ?: 0
    val landing = LocalFocusLanding.current
    // The set tabs have focus (Joey 2026-11-09, D-pad tabs): a set opened there keeps focus on the row.
    var onTabs by remember { mutableStateOf(false) }
    // A new tab: focus lands on its first row, as when the page opens. Armed in the event that changes the tab
    // (JoeyOS docs/input-rewrite.md): it was armed from an effect a frame later, and for that frame the old rows were
    // gone and focus fell out of the page to the rail (Joey 2026-11-11: "l1/r1 on achievements page jumps to the rail").
    fun pickSet(i: Int) { setPicked = i; if (!onTabs) landing?.arm() }
    tabs?.onStep = { delta -> if (tabbed) pickSet((setIdx + delta + sets.size) % sets.size) }
    LaunchedEffect(onChip, onSortChip, tabbed, onTabs) {
        // L/R changes the set only in game: in the launcher L1/R1 move along the top bar (Joey 2026-11-11, option 1),
        // so the hint said "L/R Set" while R1 opened Search (Thor 2026-11-12); the sets are D-pad tabs there.
        onHints(listOfNotNull(if (onSortChip) Hint("A", "Change") else if (onChip) Hint("A", "Show") else if (onTabs) Hint("A", "Open") else null,
            if (tabbed && live) Hint("L/R", tabHint) else null, Hint("B", "Back")))
    }
    val d = data ?: return
    val g = d.game
    val set = sets.getOrNull(setIdx)?.takeIf { tabbed }
    val inSet = remember(d, set) { RaLists.inSet(d.list, set?.id) }
    val shown = remember(inSet, filter, sort) { RaLists.achievements(inSet, filter, sort, live) }
    val now = System.currentTimeMillis()
    val listState = rememberLazyListState()
    ToTopOnChange(listState, filter, sort)
    val pager = remember { ListPager() }
    val scope = rememberCoroutineScope()
    tabs?.onPage = { dir -> scope.launch { pager.page(listState, dir, shown.size) } }
    val chips = remember { FocusRequester() }
    val setTab = remember { FocusRequester() }
    Column((if (inPanel) Modifier.fillMaxWidth() else Modifier.fillMaxSize().padding(horizontal = JoeyTheme.spacing.margin).padding(top = 14.dp, bottom = 6.dp))) {
        Header(
            title = g?.title?.ifEmpty { null } ?: title,
            line = g?.let { if (set != null) RaLists.setSummary(RaLists.setLabel(setIdx, set), set, filter, RaLists.counted(shown)) else RaLists.gameSummary(it, filter, RaLists.counted(shown)) } ?: "",
            picture = { Picture(g?.let { badgeFile(it.badge) }, 34.dp) },
            chip = filter.label, chipOn = filter != AchFilter.All, onChip = { onShow(true) }, chipShown = g != null, onChipFocused = { onChip = it },
            chipFirst = g != null && shown.isEmpty(),
            sortChip = sort.label, onSort = { sorting = true }, onSortFocused = { onSortChip = it }, chipsEntry = chips,
        )
        if (g != null) ThinBar(set?.fraction ?: g.fraction, Modifier.fillMaxWidth().padding(top = 10.dp))
        if (tabbed) SettingsTabs(sets.mapIndexed { i, s -> RaLists.setLabel(i, s) }, setIdx, counts = sets.map { it.achievements },
            // In game, a dot on a set with an active challenge, so it isn't missed on another tab.
            dots = if (!live) emptySet() else sets.indices.filter { i -> d.list.any { it.set == sets[i].id && it.section == RaAchievement.ACTIVE_CHALLENGE } }.toSet(),
            onFocused = { onTabs = it }, entry = setTab, up = if (g != null) chips else null,
        ) { pickSet(it) }
        if (g == null) Empty(if (live) "This game has no RetroAchievements set, or it wasn't found." else "Play this game once, and its achievements show here.", holdFocus = true)
        else if (!live && d.list.any { !it.isUnlocked && it.progress.isNotEmpty() })
            Text("Progress from the last time you played, ${SlotDates.label(d.keptAt, now)}", style = JoeyTheme.type.small, color = c.textDim,
                modifier = Modifier.padding(start = JoeyTheme.spacing.rowPadH, top = 6.dp))
        if (g != null && shown.isEmpty()) Empty("No achievements here")
        LaunchedEffect(shown, focusId) {
            val i = focusId?.let { id -> shown.indexOfFirst { it.id == id } } ?: -1
            if (i > 0) listState.scrollToItem(maxOf(0, i - 2))
        }
        LazyColumn(Modifier.then(if (inPanel) Modifier.fillMaxWidth() else Modifier.weight(1f).fillMaxWidth()).padding(top = 6.dp), state = listState) {
            itemsIndexed(shown, key = { _, a -> a.id }) { i, a ->
                val first = if (focusId != null && shown.any { it.id == focusId }) a.id == focusId else a === shown.first()
                AchievementRow(a, now, live, Modifier.upTo(i == 0 && (tabbed || g != null), if (tabbed) setTab else chips)
                    .initialFocus(first).pagedItem(pager, i))
            }
        }
    }
    if (showing) AchPickPopup("Show", AchFilter.entries.map { it.label }, RaLists.achCounts(inSet).let { m -> AchFilter.entries.map { m[it] } },
        filter.ordinal, { filter = AchFilter.entries[it] }) { onShow(false) }
    if (sorting) AchPickPopup("Sort", AchSort.entries.map { it.label }, null, sort.ordinal, { sort = AchSort.entries[it] }) { sorting = false }
}

/** One achievement (approved ach3.html): badge or padlock, name and Missable tag, description, progress bar; the right side says when, or how far, and how rare. */
@Composable
private fun AchievementRow(a: RaAchievement, now: Long, live: Boolean, modifier: Modifier) {
    val c = JoeyTheme.colors
    val tracked = !a.isUnlocked && a.progress.isNotEmpty()
    val right: AnnotatedString? = when {
        a.isUnlocked -> buildAnnotatedString {
            val hard = a.unlocked and 2 != 0
            withStyle(SpanStyle(color = if (hard) c.gold else c.textSecondary, fontWeight = FontWeight.Bold)) { append(RaLists.mode(a)) }
            append(" · " + RaLists.unlockDate(a, now))
        }
        tracked -> AnnotatedString(RaLists.progress(a))
        else -> null
    }
    val challenge = live && a.section == RaAchievement.ACTIVE_CHALLENGE
    IconRow(a.title, a.description, modifier, bar = if (tracked) a.percent / 100f else null, rightText = right, rightNote = RaLists.rarity(a),
        dim = !a.isUnlocked,
        // Locked: RetroAchievements' grey badge (Joey 2026-11-10, "greyed badges yes"); the padlock tile only when it has none.
        picture = { when {
            a.isUnlocked -> Picture(badgeFile(a.badge), 40.dp)
            a.badgeLocked.isNotEmpty() -> Picture(badgeFile(a.badgeLocked), 40.dp)
            else -> LockTile()
        } },
        tag = if (challenge || a.missable) ({
            if (challenge) RowTag("Challenge active", c.tagChallenge, c.tagChallengeBg)
            if (a.missable) RowTag("Missable", c.tagMissable, c.tagMissableBg, R.drawable.ic_alert_triangle)
        }) else null)
}

/** The shared header (approved template): picture, the title in the page type, the line under it, the Show chip at the right. */
@Composable
private fun Header(title: String?, line: String, picture: @Composable () -> Unit, chip: String, chipOn: Boolean, onChip: () -> Unit, chipShown: Boolean = true,
                   onChipFocused: (Boolean) -> Unit = {}, chipFirst: Boolean = false,
                   sortChip: String? = null, onSort: () -> Unit = {}, onSortFocused: (Boolean) -> Unit = {},
                   chipsEntry: FocusRequester? = null) {
    val c = JoeyTheme.colors
    // [chipsEntry] focuses the chips (the one last used, else the first): Up from the list's first row (or the set tabs)
    // points here. Compose's own search went past them to the top bar: the rows are full width, so it weighs the chips
    // at the side as far off, though they're the nearest thing above (Google's TV Navigation page: the D-pad goes to
    // the nearest element that way; Restore focus page: focusRestorer with a fallback). Thor 2026-11-12.
    val firstChip = remember { FocusRequester() }
    val group = (if (chipsEntry != null) Modifier.focusRequester(chipsEntry) else Modifier).focusRestorer(firstChip).focusGroup()
    // Sort, its own chip by the filter (approved as drawn 2026-11-12, sortchip_mock.png).
    @Composable fun sortChipView(m: Modifier = Modifier) { if (sortChip != null && chipShown) PageChip(sortChip, R.drawable.ic_sort, m, onFocused = onSortFocused, onClick = onSort) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // [title] null (the Achievements place, under the top bar): the chips first, at the start of the page.
        if (title == null && chipShown) Row(group, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageChip(chip, R.drawable.ic_filter, modifier = Modifier.focusRequester(firstChip).then(if (chipFirst) Modifier.initialFocus() else Modifier),
                on = chipOn, onFocused = onChipFocused, onClick = onChip)
            sortChipView()
        }
        // The Achievements place: the name and points at the right, the picture just before them (Joey 2026-11-12:
        // "right align the retroachievements username and points").
        if (title == null) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        picture()
        Column(if (title == null) Modifier else Modifier.weight(1f), horizontalAlignment = if (title == null) Alignment.End else Alignment.Start) {
            if (title != null) Text(title, style = JoeyTheme.type.pageTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, style = JoeyTheme.type.small, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // [chipFirst]: the list is empty, so focus lands on the chip (else it falls to the rail).
        if (title != null && chipShown) Row(group, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sortChipView(Modifier.focusRequester(firstChip))
            PageChip(chip, R.drawable.ic_filter, modifier = if (chipFirst) Modifier.initialFocus() else Modifier,
                on = chipOn, onFocused = onChipFocused, onClick = onChip)
        }
    }
}

/**
 * A new filter or sort starts the list from its top: it kept its scroll, so after Name the A to C games were above the
 * screen (Thor 2026-11-12; the Library's grid does the same, LibraryModel.gridSort). Not on the first pass, so a list
 * opened on a game (back from its page) keeps its place.
 */
@Composable
private fun ToTopOnChange(state: androidx.compose.foundation.lazy.LazyListState, vararg keys: Any?) {
    val seen = remember { mutableStateOf<List<Any?>?>(null) }
    LaunchedEffect(*keys) {
        val k = keys.toList()
        if (seen.value != null && seen.value != k) state.scrollToItem(0)
        seen.value = k
    }
}

/** Up from this row goes to [to] when [on] (the list's first row: see [Header]'s chipsEntry). */
private fun Modifier.upTo(on: Boolean, to: FocusRequester): Modifier = if (on) focusProperties { up = to } else this

/** A badge or game icon, kept in files/ra/badges (fetched now if it isn't); a plain tile until it's here. */
@Composable
private fun Picture(file: File?, size: Dp) {
    val c = JoeyTheme.colors
    val bmp = rememberBadge(file)
    val shape = RoundedCornerShape(6.dp)
    if (bmp != null) Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(shape))
    else Box(Modifier.size(size).background(c.chip, shape))
}

@Composable
private fun LockTile() {
    val c = JoeyTheme.colors
    Box(Modifier.size(40.dp).background(c.chip, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_lock), contentDescription = "Locked", tint = c.textDim, modifier = Modifier.size(16.dp))
    }
}

/** The kept badge for [url], fetching it when it isn't kept yet (the row shows it once it lands). */
@Composable
private fun badgeFile(url: String): File? {
    val context = LocalContext.current
    var f by remember(url) { mutableStateOf(if (url.isEmpty()) null else RaBadges.kept(context, url)) }
    if (f == null && url.isNotEmpty()) LaunchedEffect(url) {
        f = withContext(badgeWaits) { RaBadges.await(context, url, 30_000) }
    }
    return f
}

/**
 * Badge waits get four threads of their own: each row whose badge isn't kept waits up to 30 s, and a long list scrolled
 * offline held one IO thread per row, so the page's other reads waited behind them (code audit 3, p2 L13).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val badgeWaits = Dispatchers.IO.limitedParallelism(4)

@Composable
private fun gold(text: String) = buildAnnotatedString {
    withStyle(SpanStyle(color = JoeyTheme.colors.gold, fontWeight = FontWeight.Bold)) { append(text) }
}

@Composable
private fun Empty(text: String, holdFocus: Boolean = false) {
    // [holdFocus]: the page has nothing else to focus, so the words hold it (else it falls to the rail, with the rail's hints).
    Text(text, style = JoeyTheme.type.note, color = JoeyTheme.colors.textDim,
        modifier = (if (holdFocus) Modifier.initialFocus(true).focusable() else Modifier)
            .padding(start = JoeyTheme.spacing.rowPadH, top = 16.dp, bottom = 8.dp))
}

/**
 * Show (the filters, with their counts) or Sort, each one list with the one in use ticked: the Filter and Sort
 * columns of ach2.html, split into two chips (approved as drawn 2026-11-12, sortchip_mock.png). A picks and closes.
 */
@Composable
private fun AchPickPopup(title: String, labels: List<String>, counts: List<Int?>?, picked: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 380.dp) {
        Column {
            PopupTitle(title)
            labels.forEachIndexed { i, label ->
                JoeyRow(label, Modifier.initialFocus(i == picked),
                    trailing = { f -> TickCount(i == picked, counts?.getOrNull(i), f) }) { onPick(i); onDismiss() }
            }
        }
    }
}
