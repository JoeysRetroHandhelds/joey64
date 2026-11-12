package com.joeysretro.joey64.launcher.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.compose.foundation.layout.size
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.R
import com.joeysretro.joey64.controls.GameBoyFiles
import com.joeysretro.joey64.controls.PlayerSetup
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupNote
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.storage.DataFolders
import com.joeysretro.joey64.launcher.storage.FolderNames
import com.joeysretro.joey64.launcher.storage.RomFolders
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transfer Pak setup (approved paks mockup 2026-11-08, scratchpad build_paks2.py / paks2_mock.png, "Approve as drawn",
 * screen 4): the Game Boy game and its save for one game. B is Done.
 */
@Composable
internal fun TransferSetupPopup(gameTitle: String, setup: PlayerSetup, row: String, onDismiss: () -> Unit,
                                onGame: () -> Unit, onSave: () -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Change"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle("Transfer Pak", "Pick the Game Boy game and its save for $gameTitle.")
            JoeyRow("Game Boy game", Modifier.initialFocus(row == "game"),
                value = (setup.gbGame?.let { GameBoyFiles.title(it.name) } ?: "None") + "  ›", onClick = onGame)
            JoeyRow("Game Boy save", Modifier.initialFocus(row == "save"),
                value = (setup.gbSave?.name ?: "New save") + "  ›", onClick = onSave)
            PopupNote("Joey64 looks in your ROMs folder's gb and gbc folders. It plays a copy of the save in its own folder, " +
                "so the original isn't changed. Takes effect when the game next starts.")
        }
    }
}

/**
 * Screen 5: the Game Boy games ([games]) or saves found ([GameBoyFiles.scan]: the ROM folders and the Game Boy folders),
 * None (New save for saves), Choose a file (Android's picker; only where the screen can open it, so not over a paused
 * game) and Game Boy folders ([onFolders]). [onPick] gets the pick and, for a game, the save that goes with it by default
 * (same name, .sav first); null is None.
 *
 * Approved Game Boy folder mockup 2026-11-08 (scratchpad build_gbfolder.py / gbfolder_mock.png, "Approve as drawn"):
 * with nothing found, the first row is Choose your Game Boy folder (the picker opens at the folder above the ROM folder)
 * or Choose your saves folder (it opens at RetroArch's saves folder); after a pick the lists fill and the folder row moves
 * to the end as Game Boy folders. A game's save list shows only the saves named like the game, with where each one is.
 */
@Composable
internal fun GameBoyListPopup(games: Boolean, setup: PlayerSetup, onDismiss: () -> Unit, onFolders: () -> Unit,
                              onDefaultSave: (GameBoyFiles.Found) -> Unit = {},
                              onPick: (GameBoyFiles.Found?, GameBoyFiles.Found?) -> Unit) {
    val context = LocalContext.current
    var scanned by remember { mutableStateOf(0) }
    var found by remember { mutableStateOf<GameBoyFiles.Scan?>(null) }
    var gbFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    var romFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(scanned) {
        found = null
        val s = SettingsStore(context).settings.first()
        gbFolders = s.gbFolders.sorted(); romFolders = s.romFolders.sorted()
        val f = scanGameBoy(context)
        found = f
        // A saves folder just picked, the game on New save: its same-name save is picked, as picking the game does.
        val game = setup.gbGame
        if (scanned > 0 && !games && game != null && setup.gbSave == null) GameBoyFiles.saveFor(game, f.saves)?.let(onDefaultSave)
    }
    val canPick = LocalActivityResultRegistryOwner.current != null
    val picker = if (canPick) rememberGbFolderPicker { scanned++ } else null
    val current = if (games) setup.gbGame else setup.gbSave
    val game = setup.gbGame
    val inFolders = found?.let { f ->
        if (games) f.games else if (game != null) f.saves.filter { GameBoyFiles.namedLike(it.name, GameBoyFiles.title(game.name)) } else f.saves
    }
    // A file picked elsewhere isn't in the folders: it still shows, ticked.
    val list = inFolders?.let { l -> if (current != null && l.none { it.uri == current.uri }) listOf(current) + l else l }
    // Nothing in the folders (a file picked elsewhere doesn't count): the folder row comes first.
    val none = inFolders?.isEmpty() == true
    fun pick(f: GameBoyFiles.Found?) {
        if (!games) { onPick(f, null); return }
        onPick(f, f?.let { GameBoyFiles.saveFor(it, found?.saves.orEmpty()) })
    }
    val subtitle = when {
        found == null -> null
        none -> if (games) "Joey64 can't see your Game Boy games yet." else "Joey64 can't see your Game Boy saves yet."
        !games && game != null -> "For ${GameBoyFiles.title(game.name)}."
        else -> "From " + fromLabel(context, found!!, list.orEmpty()) + "."
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        val scroll = rememberScrollState()
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(scroll)) {
            PopupTitle(if (games) "Game Boy game" else "Game Boy save", subtitle)
            // Focus landed on None while it looked: land again once the rows are in (the folder row, or the pick). None
            // keeps its focus without moving into view, under the rows that came in above it: the list goes down to it.
            val landing = LocalFocusLanding.current
            LaunchedEffect(found != null) { if (found != null) landing?.arm() }
            // Down to None once the rows are in: keyed on the list's height, so no frame wait.
            val bottom = scroll.maxValue
            LaunchedEffect(found != null, bottom) { if (found != null && current == null && !none && bottom > 0) scroll.scrollTo(bottom) }
            if (found == null) PopupNote("Looking…")
            else if (none && picker != null) JoeyRow(if (games) "Choose your Game Boy folder" else "Choose your saves folder", Modifier.initialFocus(true),
                detail = if (games) "Your gb or gbc folder, or the ROMs folder that holds them"
                    else "Where your Game Boy saves are, like RetroArch's saves folder") {
                picker.launch(if (games) gamesPickerStart(context, romFolders) else SAVES_START)
            }
            for (f in list.orEmpty()) {
                val on = f.uri == current?.uri
                JoeyRow(if (games) GameBoyFiles.title(f.name) else f.name, Modifier.initialFocus(on && !none),
                    detail = if (games) null else found?.from?.get(f.uri)?.let { pathLabel(context, it) },
                    trailing = if (on) { _ -> TickMark() } else null) { pick(f) }
            }
            if (!none) PopupDivider()
            JoeyRow(if (games) "None" else "New save", Modifier.initialFocus(current == null && !(none && picker != null)),
                detail = if (games) "No game in the Transfer Pak" else "A blank save for this game",
                trailing = if (current == null) { _ -> TickMark() } else null) { pick(null) }
            if (canPick) ChooseFileRow(games) { pick(it) }
            if (gbFolders.isNotEmpty()) JoeyRow("Game Boy folders",
                value = (if (gbFolders.size == 1) placeLabel(context, gbFolders[0]) else "${gbFolders.size} folders") + "  ›", onClick = onFolders)
        }
    }
}

/**
 * Game Boy folders (approved Game Boy folder mockup 2026-11-08, screen 5): the folders picked for Game Boy games and saves,
 * and Add a folder. A on one asks to remove it: only Joey64's permission goes, nothing in it is deleted.
 */
@Composable
internal fun GameBoyFoldersPopup(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(context) }
    var scanned by remember { mutableStateOf(0) }
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var places by remember { mutableStateOf<Map<String, GameBoyFiles.Place>>(emptyMap()) }
    var removing by remember { mutableStateOf<String?>(null) }
    var romFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(scanned) {
        val s = store.settings.first()
        folders = s.gbFolders.sorted(); romFolders = s.romFolders.sorted()
        places = scanGameBoy(context).places
    }
    val canPick = LocalActivityResultRegistryOwner.current != null
    val picker = if (canPick) rememberGbFolderPicker { scanned++ } else null
    removing?.let { tree ->
        val name = placeLabel(context, tree)
        JoeyDialog(onDismiss = { removing = null }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
            Column {
                PopupTitle("Remove $name?", "Joey64 stops looking there for Game Boy games and saves. Nothing in it is deleted.")
                JoeyRow("Keep it", Modifier.initialFocus(true)) { removing = null }
                JoeyRow("Remove") {
                    removing = null
                    scope.launch {
                        store.removeGbFolder(tree)
                        // A ROM folder or the data folder may be the same folder: its grant stays.
                        RomFolders.releaseIfUnused(context, tree)
                        JLog.i("Joey64GameBoy", "Game Boy folder removed")
                        scanned++
                    }
                }
            }
        }
        return
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle("Game Boy folders", "Where Joey64 looks for Game Boy games and saves.")
            folders?.forEachIndexed { i, tree ->
                JoeyRow(placeLabel(context, tree), Modifier.initialFocus(i == 0), detail = placeDetail(places[tree])) { removing = tree }
            }
            if (picker != null) JoeyRow("Add a folder", Modifier.initialFocus(folders?.isEmpty() == true)) { picker.launch(gamesPickerStart(context, romFolders)) }
        }
    }
}

/** RetroArch's saves folder on internal storage, where it keeps them by default: where Choose your saves folder opens. */
private val SAVES_START: Uri = DocumentsContract.buildDocumentUri(RomFolders.EXTERNAL_STORAGE, "primary:RetroArch/saves")

/** Where Choose your Game Boy folder opens: the folder above the first ROM folder (ROMs, beside n64), else the ROM picker's start. */
private fun gamesPickerStart(context: Context, romFolders: List<String>): Uri {
    val docId = romFolders.firstOrNull()?.let { try { DocumentsContract.getTreeDocumentId(it.toUri()) } catch (e: Exception) { null } }
    if (docId != null && docId.contains('/')) return DocumentsContract.buildDocumentUri(RomFolders.EXTERNAL_STORAGE, docId.substringBeforeLast('/'))
    return RomFolders.initialPickerUri(context)
}

/** Android's folder picker for a Game Boy folder: read only, the grant kept, the folder added to the list. */
@Composable
private fun rememberGbFolderPicker(onAdded: () -> Unit): androidx.activity.compose.ManagedActivityResultLauncher<Uri?, Uri?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val landing = LocalFocusLanding.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        landing?.arm()
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try { RomFolders.keep(context, uri, write = false) } catch (e: Exception) { JLog.w("Joey64GameBoy", "no lasting permission for a Game Boy folder: $e") }
            SettingsStore(context).addGbFolder(uri.toString())
            JLog.i("Joey64GameBoy", "Game Boy folder added")
            onAdded()
        }
    }
}

/** Scans the ROM folders and the Game Boy folders, off the main thread. */
private suspend fun scanGameBoy(context: Context): GameBoyFiles.Scan {
    val s = SettingsStore(context).settings.first()
    return withContext(Dispatchers.IO) {
        GameBoyFiles.scan(context, s.romFolders.sorted().map { it.toUri() }, s.gbFolders.sorted().map { it.toUri() })
    }
}

/** "SD card › ROMs": a picked folder, with its storage. */
private fun placeLabel(context: Context, tree: String): String = try {
    FolderNames.of(context, tree.toUri()).let { (listOf(it.place) + it.folders).joinToString(" › ") }
} catch (e: Exception) { "Picked folder" }

/** "RetroArch › saves › Gambatte": where a found file is, without its storage. */
private fun pathLabel(context: Context, from: GameBoyFiles.From): String = try {
    val f = FolderNames.of(context, from.tree.toUri())
    (f.folders + listOfNotNull(from.sub.ifEmpty { null })).ifEmpty { listOf(f.place) }.joinToString(" › ")
} catch (e: Exception) { "Picked folder" }

/** "SD card › ROMs › gb and gbc": the folders [shown] came from. */
private fun fromLabel(context: Context, scan: GameBoyFiles.Scan, shown: List<GameBoyFiles.Found>): String =
    shown.mapNotNull { scan.from[it.uri] }.groupBy { it.tree }.entries.joinToString("; ") { (tree, froms) ->
        val subs = froms.map { it.sub }.filter { it.isNotEmpty() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        placeLabel(context, tree) + if (subs.isEmpty()) "" else " › " + andList(subs)
    }.ifEmpty { "your folders" }

/** "Games: 94, in its gb and gbc folders" / "Saves: in its Gambatte and mGBA folders". */
private fun placeDetail(p: GameBoyFiles.Place?): String = when {
    p == null -> "Looking…"
    !p.readable -> "Can't be read now"
    p.games > 0 -> "Games: ${p.games}" + if (p.gameFolders.isEmpty()) "" else ", in its ${andList(p.gameFolders)} folders"
    p.saves > 0 -> if (p.saveFolders.isEmpty()) "Saves: ${p.saves}" else "Saves: in its ${andList(p.saveFolders)} folders"
    else -> "No Game Boy games or saves found"
}

/** "a", "a and b", "a, b and c". */
private fun andList(items: List<String>): String =
    if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()

@Composable
private fun TickMark() {
    Icon(painterResource(R.drawable.ic_tick), contentDescription = "Chosen", tint = JoeyTheme.colors.gold, modifier = Modifier.size(18.dp))
}

/**
 * Choose a file: Android's picker; a file that isn't a Game Boy game (or save) is left out, and the row says so under
 * it (code audit 3, p2 L11: the popup looked as if nothing happened).
 */
@Composable
private fun ChooseFileRow(games: Boolean, onPicked: (GameBoyFiles.Found) -> Unit) {
    val context = LocalContext.current
    var wrong by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = GameBoyFiles.nameOf(context, uri) ?: return@rememberLauncherForActivityResult
        if (if (games) !GameBoyFiles.isGame(name) else !GameBoyFiles.isSave(name)) {
            JLog.w("Joey64GameBoy", "picked $name: not a Game Boy ${if (games) "game" else "save"}")
            wrong = name
            return@rememberLauncherForActivityResult
        }
        wrong = null
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (e: Exception) { JLog.w("Joey64GameBoy", "no lasting permission for $name: $e") }
        onPicked(GameBoyFiles.Found(uri.toString(), name))
    }
    JoeyRow("Choose a file", detail = wrong?.let { "$it isn't a Game Boy ${if (games) "game" else "save"}" }, detailWarn = true) { picker.launch(arrayOf("*/*")) }
}
