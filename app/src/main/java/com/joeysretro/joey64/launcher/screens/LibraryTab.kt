package com.joeysretro.joey64.launcher.screens

import com.joeysretro.joey64.launcher.components.TwoColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.data.LibraryGame
import com.joeysretro.joey64.launcher.layout.LocalScreenLayout
import com.joeysretro.joey64.launcher.library.BetaMode
import com.joeysretro.joey64.launcher.library.CoverSize
import com.joeysretro.joey64.launcher.library.LibraryArrange
import com.joeysretro.joey64.launcher.library.LookFor
import com.joeysretro.joey64.launcher.library.NameStyle
import com.joeysretro.joey64.launcher.library.NamesMode
import com.joeysretro.joey64.launcher.library.RegionGroup
import com.joeysretro.joey64.launcher.library.SortBy
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.storage.BiosImport
import com.joeysretro.joey64.launcher.storage.DataFolders
import com.joeysretro.joey64.launcher.storage.FolderNames
import com.joeysretro.joey64.launcher.storage.RomFolders
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Library tab's popups (approved mockup 2026-11-08, screens 2-10). */
private enum class LibPopup { Sort, Regions, Betas, Hidden, Size, Names, Style, Folders, Look }

/**
 * Settings › Library (approved mockup 2026-11-08, scratchpad build_libtab.py / libtab_mock.png, "Approve as
 * drawn"); the same for every game, so no "for" chip. Left: Show (Sort by, Regions, Hidden games) and Covers
 * (Cover size, Names, Name style, greyed while Names is Off). Right: Games (ROM folders, a popup with each
 * folder and its games, A removes it after a question, and Add a folder; Look for new games; Look now), BIOS
 * and Downloads. Every row applies to the library at once.
 *
 * A removed folder's files leave the library; its games' play time, favourites, collections, saves and states
 * stay, and come back with the folder. The last folder can't be removed (the library would go back to first run).
 */
@Composable
fun LibraryTab(library: LibraryModel, onHints: (List<Hint>) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val landing = LocalFocusLanding.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(context) }
    val settings by store.settings.collectAsState(initial = null)
    val s = settings ?: return
    val folders = s.romFolders.sorted()
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val games by library.games.collectAsState()
    val hidden by library.hidden.collectAsState()
    // Counted again when a read ends: a folder added holds games already in the library, so the list may not change.
    val reading by library.reading.collectAsState()
    val looking by library.looking.collectAsState()
    val lookResult by library.lookResult.collectAsState()
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { library.clearLookResult() } }
    LaunchedEffect(folders, games, reading == null) { if (reading == null) counts = library.folderCounts() }
    var focused by remember { mutableStateOf("sort") }
    var popup by remember { mutableStateOf<LibPopup?>(null) }
    // Regions: the hint says Select on Betas and demos (a popup, not a tick); back from its choices, focus lands on it.
    var onBetas by remember { mutableStateOf(false) }
    var backFromBetas by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    // Add a folder's outcome ("Added · 82 games"), or "Looking…" while it reads.
    var addStatus by remember { mutableStateOf<String?>(null) }
    // 64DD BIOS (approved 2026-11-07): what bios/ holds, read again after a pick; the pick's outcome popup.
    var biosHave by remember { mutableStateOf<Map<String, BiosImport.Ipl?>?>(null) }
    var biosLooking by remember { mutableStateOf(false) }
    var biosFound by remember { mutableStateOf<List<BiosImport.Ipl>?>(null) }
    LaunchedEffect(Unit) { biosHave = withContext(Dispatchers.IO) { BiosImport.installed(context) } }
    val biosPicker = rememberBiosPicker { uri ->
        landing?.arm()
        uri ?: return@rememberBiosPicker
        biosLooking = true
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                try { BiosImport.importFolder(context, uri) } catch (e: Exception) { JLog.e(TAG, "64DD BIOS: $e"); emptyList() }
            }
            biosHave = withContext(Dispatchers.IO) { BiosImport.installed(context) }
            biosLooking = false
            biosFound = found
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        landing?.arm()
        uri ?: return@rememberLauncherForActivityResult
        if (uri.toString() in s.romFolders) { addStatus = "Already in the list"; return@rememberLauncherForActivityResult }
        addStatus = "Looking…"
        scope.launch {
            val look = try { library.look(uri) } catch (e: Exception) { JLog.e(TAG, "can't look in $uri: $e"); null }
            addStatus = when {
                look == null -> "Couldn't read it"
                look.roms == 0 -> "No N64 games in ${look.folders.lastOrNull() ?: look.place}"
                else -> {
                    RomFolders.keep(context, uri, write = false)
                    store.addRomFolder(uri.toString())
                    JLog.i(TAG, "ROM folder added: ${look.roms} games")
                    if (look.roms == 1) "Added · 1 game" else "Added · ${look.roms} games"
                }
            }
        }
    }

    fun set(block: suspend SettingsStore.() -> Unit) { scope.launch { store.block() } }
    @Composable
    fun row(key: String, label: String, value: String?, enabled: Boolean = true, onClick: () -> Unit) =
        JoeyRow(label, Modifier.initialFocus(focused == key), value = value, enabled = enabled, sideways = true,
            onFocused = { if (it) focused = key }, onClick = onClick)

    val gamesWord = { n: Int -> if (n == 1) "1 game" else "$n games" }
    // One list with the panel beside it (approved 2026-11-11).
    val panel = com.joeysretro.joey64.launcher.components.rememberListPanel()
    val base = LocalScreenLayout.current.boxArtColumns
    val (title, text) = describe(focused)
    fun <T> pick(all: List<T>, cur: T, label: (T) -> String, detail: (T) -> String?, on: (T) -> Unit) =
        com.joeysretro.joey64.launcher.components.PanelInfo(title, text,
            all.map { com.joeysretro.joey64.launcher.components.PanelChoice(label(it), detail(it), it == cur) }) { i -> on(all[i]) }
    val info = when (focused) {
        "size" -> pick(CoverSize.entries, s.coverSize, { it.label }, { "${LibraryArrange.columns(base, it)} across on this screen" }) { z -> set { setCoverSize(z) } }
        "names" -> pick(NamesMode.entries, s.names, { it.label }, { it.detail }) { n -> set { setNames(n) } }
        "style" -> if (s.names == NamesMode.Off) com.joeysretro.joey64.launcher.components.PanelInfo(title, text)
            else pick(NameStyle.entries, s.nameStyle, { it.label }, { if (it == NameStyle.Short) "Conker's Bad Fur Day" else "Conker's Bad Fur Day (Europe)" }) { n -> set { setNameStyle(n) } }
        "look" -> pick(LookFor.entries, s.lookFor, { it.label }, { it.detail }) { l -> set { setLookFor(l) } }
        // Rows that open a popup of their own: what's set inside.
        "sort" -> com.joeysretro.joey64.launcher.components.PanelInfo(title, text,
            lines = listOf("Sort by" to s.sortBy.label, "Favourites first" to (if (s.favouritesFirst) "On" else "Off")))
        "regions" -> com.joeysretro.joey64.launcher.components.PanelInfo(title, text,
            lines = listOf("Regions" to LibraryArrange.regionsLabel(s.regions), "One version per game" to (if (s.oneVersion) "On" else "Off"),
                "Betas and demos" to s.betas.label))
        "hidden" -> com.joeysretro.joey64.launcher.components.PanelInfo(title, text, lines = hidden.take(8).map { it.title to "Hidden" })
        else -> com.joeysretro.joey64.launcher.components.PanelInfo(title, text)
    }
    com.joeysretro.joey64.launcher.components.ListPanel(panel, info, listOf(Hint("A", "Change"), Hint("B", "Back")), onHints, modifier) {
        run {
            SectionLabel("Show")
            row("sort", "Sort by", s.sortBy.label + "  ›") { popup = LibPopup.Sort }
            row("regions", "Regions", LibraryArrange.regionsLabel(s.regions) + "  ›") { backFromBetas = false; popup = LibPopup.Regions }
            row("hidden", "Hidden games", (if (hidden.isEmpty()) "None" else gamesWord(hidden.size)) + "  ›") { popup = LibPopup.Hidden }
            SectionLabel("Covers")
            row("size", "Cover size", s.coverSize.label + "  ›") { if (!panel.enter()) popup = LibPopup.Size }
            row("names", "Names", s.names.label + "  ›") { if (!panel.enter()) popup = LibPopup.Names }
            row("style", "Name style", s.nameStyle.label + "  ›", enabled = s.names != NamesMode.Off) { if (!panel.enter()) popup = LibPopup.Style }
            SectionLabel("Games")
            row("folders", "ROM folders", (if (folders.size == 1) "1 folder" else "${folders.size} folders") + "  ›") { popup = LibPopup.Folders }
            row("look", "Look for new games", s.lookFor.label + "  ›") { if (!panel.enter()) popup = LibPopup.Look }
            val r = reading
            row("now", "Look now", when { r != null -> "Reading ${r.done} of ${r.total}"; looking -> "Looking…"; else -> lookResult }) {
                library.lookNow(folders.map { it.toUri() })
            }
            SectionLabel("BIOS")
            val biosLabel = biosHave?.let { BiosImport.label(it) }
            row("bios", "64DD BIOS", when { biosLooking -> "Looking…"; biosLabel != null -> "$biosLabel ✓"; biosHave == null -> null; else -> "Not set ›" }) {
                if (!biosLooking) biosPicker.launch(null)
            }
            SectionLabel("Downloads")
            row("art", "Download box art", if (s.downloadBoxArt) "On" else "Off") { set { setDownloadBoxArt(!s.downloadBoxArt) } }
            row("artq", "Box art quality", if (s.boxArtLow) "Low" else "High") { set { setBoxArtLow(!s.boxArtLow) } }
            row("cheats", "Download cheats", if (s.downloadCheats) "On" else "Off") { set { setDownloadCheats(!s.downloadCheats) } }
        }
    }

    val close = { popup = null }
    when (popup) {
        LibPopup.Sort -> JoeyDialog(onDismiss = close, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
            Column {
                PopupTitle("Sort by")
                for (by in SortBy.entries) JoeyRow(by.label, Modifier.initialFocus(by == s.sortBy), detail = by.detail,
                    trailing = if (by == s.sortBy) { _ -> PickerTick() } else null) { set { setSortBy(by) }; popup = null }
                PopupDivider()
                JoeyRow("Favourites first", detail = "Favourites at the top, then the rest", value = if (s.favouritesFirst) "On" else "Off",
                    sideways = true) { set { setFavouritesFirst(!s.favouritesFirst) } }
            }
        }
        LibPopup.Regions -> JoeyDialog(onDismiss = close, hints = listOf(Hint("A", if (onBetas) "Select" else "Tick"), Hint("B", "Close")), width = 470.dp) {
            Column {
                PopupTitle("Regions", "Games from the ticked regions show in the library.")
                RegionGroup.entries.forEachIndexed { i, r ->
                    val on = r in s.regions
                    JoeyRow(r.label, Modifier.initialFocus(i == 0 && !backFromBetas), detail = r.detail, leading = { _ -> TickBox(on) }) {
                        // The last tick stays: an empty library would look like lost games.
                        if (!on) set { setRegions(s.regions + r) } else if (s.regions.size > 1) set { setRegions(s.regions - r) }
                    }
                }
                PopupDivider()
                JoeyRow("One version per game", detail = "Several regions or revisions of a game: show one", value = if (s.oneVersion) "On" else "Off",
                    sideways = true) { set { setOneVersion(!s.oneVersion) } }
                // Approved mockup 1A (2026-11-11): what One version per game does with betas and demos; with it Off,
                // every copy has its own tile, so the row is grey.
                JoeyRow("Betas and demos", Modifier.initialFocus(backFromBetas),
                    detail = if (s.oneVersion) "Betas, protos, demos and kiosk copies" else "Needs One version per game",
                    value = s.betas.label + "  ›", enabled = s.oneVersion, sideways = true,
                    onFocused = { onBetas = it }) { popup = LibPopup.Betas }
            }
        }
        LibPopup.Betas -> ChoiceList("Betas and demos", BetaMode.entries.map { b -> Triple(b.label, b.detail, b == s.betas) },
            { backFromBetas = true; popup = LibPopup.Regions }) { i ->
            set { setBetas(BetaMode.entries[i]) }; backFromBetas = true; popup = LibPopup.Regions
        }
        LibPopup.Size -> ChoiceList("Cover size", CoverSize.entries.map { z ->
            Triple(z.label, "${LibraryArrange.columns(base, z)} across on this screen", z == s.coverSize) }, close) { i ->
            set { setCoverSize(CoverSize.entries[i]) }; popup = null
        }
        LibPopup.Names -> ChoiceList("Names", NamesMode.entries.map { n -> Triple(n.label, n.detail, n == s.names) }, close) { i ->
            set { setNames(NamesMode.entries[i]) }; popup = null
        }
        LibPopup.Style -> ChoiceList("Name style", listOf(
            Triple(NameStyle.Short.label, "Conker's Bad Fur Day", s.nameStyle == NameStyle.Short),
            Triple(NameStyle.Full.label, "Conker's Bad Fur Day (Europe)", s.nameStyle == NameStyle.Full)), close) { i ->
            set { setNameStyle(NameStyle.entries[i]) }; popup = null
        }
        LibPopup.Look -> ChoiceList("Look for new games", LookFor.entries.map { l -> Triple(l.label, l.detail, l == s.lookFor) }, close) { i ->
            set { setLookFor(LookFor.entries[i]) }; popup = null
        }
        LibPopup.Hidden -> HiddenPopup(hidden, close) { library.setHidden(it, false) }
        LibPopup.Folders -> if (removing == null) JoeyDialog(onDismiss = close, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 520.dp) {
            Column {
                PopupTitle("ROM folders", "Joey64 reads N64 games from these folders.")
                folders.forEachIndexed { i, tree ->
                    val name = remember(tree) { folderName(context, tree) }
                    JoeyRow(name.first, Modifier.initialFocus(i == 0), value = counts[tree]?.let(gamesWord), detail = name.second,
                        sideways = true) { removing = tree }
                }
                PopupDivider()
                JoeyRow("Add a folder", detail = "Pick a folder with N64 games", value = addStatus ?: "›", sideways = true) {
                    if (addStatus != "Looking…") picker.launch(RomFolders.initialPickerUri(context))
                }
            }
        }
        null -> {}
    }

    biosFound?.let { found ->
        JoeyDialog(onDismiss = { biosFound = null }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
            Column {
                if (found.isNotEmpty()) {
                    PopupTitle(foundBiosTitle(found))
                    JoeyRow("OK", Modifier.initialFocus(true)) { biosFound = null }
                } else {
                    PopupTitle(NO_BIOS)
                    JoeyRow("Choose again", Modifier.initialFocus(true)) { biosFound = null; biosPicker.launch(null) }
                    JoeyRow("Cancel") { biosFound = null }
                }
            }
        }
    }

    removing?.let { tree ->
        val name = remember(tree) { folderName(context, tree) }
        val only = folders.size <= 1
        JoeyDialog(onDismiss = { removing = null }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
            Column {
                if (only) {
                    PopupTitle(name.first, "Joey64 needs a ROM folder. Add another one before removing this one.")
                    JoeyRow("OK", Modifier.initialFocus(true)) { removing = null }
                } else {
                    var leaving by remember(tree) { mutableStateOf<Int?>(null) }
                    LaunchedEffect(tree) { leaving = library.gamesOnlyIn(tree) }
                    val n = leaving ?: counts[tree] ?: 0
                    PopupTitle("Remove ${name.first}?", if (leaving == 0) "Its games are in another folder too, so the library stays the same."
                        else (if (n == 1) "1 game leaves" else "$n games leave") +
                            " the library. Their saves, states, play time and collections stay, and come back if you add the folder again.")
                    JoeyRow("Keep it", Modifier.initialFocus(true)) { removing = null }
                    JoeyRow("Remove from the library") {
                        removing = null
                        scope.launch {
                            store.removeRomFolder(tree)
                            addStatus = null
                            library.removeFolder(tree)
                            // The data folder or a Game Boy folder may be the same folder: its grant stays.
                            RomFolders.releaseIfUnused(context, tree)
                            JLog.i(TAG, "ROM folder removed")
                        }
                    }
                }
            }
        }
    }
}

/** Hidden games (screen 8): each with when it was hidden; A shows it again. None: the popup says so. */
@Composable
private fun HiddenPopup(hidden: List<LibraryGame>, onDismiss: () -> Unit, onShow: (LibraryGame) -> Unit) {
    val day = remember { java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK) }
    val zone = remember { java.time.ZoneId.systemDefault() }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", if (hidden.isEmpty()) "OK" else "Show again"), Hint("B", "Close")), width = 520.dp) {
        Column {
            if (hidden.isEmpty()) {
                PopupTitle("Hidden games", "None hidden. A game's options (Start in the library) have Hide game.")
                JoeyRow("OK", Modifier.initialFocus(true), onClick = onDismiss)
            } else {
                PopupTitle("Hidden games", "Not in the library, Search or the filters. Their saves are kept.")
                hidden.forEachIndexed { i, g ->
                    val at = g.hiddenAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).format(day) }
                    JoeyRow(g.title, Modifier.initialFocus(i == 0), detail = at?.let { "Hidden $it" }, value = "Show again", sideways = true) { onShow(g) }
                }
            }
        }
    }
}

/** The Regions popup's tick box: gold and ticked when on. */
@Composable
internal fun TickBox(on: Boolean) {
    val c = JoeyTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.padding(end = 10.dp).size(18.dp).then(if (on) Modifier.background(c.gold, shape) else Modifier.border(2.dp, c.textDim, shape)),
        contentAlignment = Alignment.Center) {
        if (on) Icon(painterResource(R.drawable.ic_tick), contentDescription = "Ticked", tint = c.page, modifier = Modifier.size(14.dp))
    }
}

/** The description line under the tab for the focused row. */
private fun describe(key: String): Pair<String, String> = when (key) {
    "sort" -> "Sort by" to "The order of the library's covers."
    "regions" -> "Regions" to "Which regions' games the library shows, from each game's header. One version per game shows one copy of a game you have in several regions or revisions; the game's options › Version picks which one, and Search still has them all."
    "hidden" -> "Hidden games" to "Games hidden from their options. They're not in the library, Search or the filters, and their saves are kept."
    "size" -> "Cover size" to "How big the library's covers are: Small fits more across, Large fewer."
    "names" -> "Names" to "Whether the library shows game names: under every cover, only on games without box art, or not at all."
    "style" -> "Name style" to "Short leaves out the tags in brackets. Full keeps them, which tells apart two versions of a game."
    "folders" -> "ROM folders" to "Where Joey64 finds your games. Removing one keeps their saves, states and play time."
    "look" -> "Look for new games" to "When Joey64 reads the ROM folders for new, changed and removed games. A folder you add is always read."
    "now" -> "Look now" to "Reads the ROM folders for new, changed and removed games."
    "art" -> "Download box art" to "Covers from ScreenScraper, once per game. Off: no downloads; covers already here stay."
    "artq" -> "Box art quality" to "High: covers 640 pixels wide. Low: 320, a quarter of the space and quicker to download. Covers already here stay as they are."
    "bios" -> "64DD BIOS" to "Needed for 64DD disks. Pick the folder with your BIOS files; Joey64 keeps a copy of each one it knows."
    else -> "Download cheats" to "Each game's cheat lists from joey64-data. Off: no downloads; lists already here stay."
}

private const val TAG = "Joey64LibraryTab"

/** The folder's own name, and where it is ("SD Card › ROMs"). */
private fun folderName(context: android.content.Context, tree: String): Pair<String, String?> = try {
    val n = FolderNames.of(context, tree.toUri())
    val last = n.folders.lastOrNull()
    if (last == null) n.place to null else last to (listOf(n.place) + n.folders.dropLast(1)).joinToString(" › ")
} catch (e: Exception) { "ROM folder" to null }
