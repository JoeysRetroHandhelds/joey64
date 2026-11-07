package com.joeysretro.joey64.launcher.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.storage.DataFolders
import com.joeysretro.joey64.launcher.storage.FolderNames
import com.joeysretro.joey64.launcher.storage.RomFolders
import kotlinx.coroutines.launch

/**
 * Settings › Library, the stop-gap tab of the plan's Decision 3 (OK'd 2026-11-05; built on Joey's
 * "Yes, build it" after the 2026-11-07 audit), until the real Library tab is designed. Left: the
 * ROM folders, each with its games (A removes it, after a question), then Add a folder. Right: the
 * Download box art and Download cheats switches. The data folder and Import from RetroArch are in
 * Settings › Saves.
 *
 * A removed folder's files leave the library; its games' play time, favourites, collections, saves
 * and states stay, and come back with the folder. The last folder can't be removed (the library
 * would go back to first run).
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
    // Counted again when a read ends: a folder added holds games already in the library, so the list may not change.
    val reading by library.reading.collectAsState()
    LaunchedEffect(folders, games, reading == null) { if (reading == null) counts = library.folderCounts() }
    var focused by remember { mutableStateOf(folders.firstOrNull() ?: "add") }
    var removing by remember { mutableStateOf<String?>(null) }
    // Add a folder's outcome ("Added · 82 games"), or "Looking…" while it reads.
    var addStatus by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(focused) {
        onHints(if (focused in folders) listOf(Hint("A", "Remove"), Hint("L/R", "Tab"), Hint("B", "Back"))
            else listOf(Hint("A", "Change"), Hint("L/R", "Tab"), Hint("B", "Back")))
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth().focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SectionLabel("ROM folders")
                for (tree in folders) {
                    val name = remember(tree) { folderName(context, tree) }
                    val n = counts[tree]
                    JoeyRow(name.first, Modifier.initialFocus(tree == focused), value = n?.let { if (it == 1) "1 game" else "$it games" },
                        detail = name.second, sideways = true, onFocused = { if (it) focused = tree }) { removing = tree }
                }
                JoeyRow("Add a folder", Modifier.initialFocus(focused == "add"), value = addStatus ?: "›",
                    sideways = true, onFocused = { if (it) focused = "add" }) { if (addStatus != "Looking…") picker.launch(RomFolders.initialPickerUri(context)) }
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("Downloads")
                JoeyRow("Download box art", Modifier.initialFocus(focused == "art"), value = if (s.downloadBoxArt) "On" else "Off",
                    sideways = true, onFocused = { if (it) focused = "art" }) { scope.launch { store.setDownloadBoxArt(!s.downloadBoxArt) } }
                JoeyRow("Download cheats", Modifier.initialFocus(focused == "cheats"), value = if (s.downloadCheats) "On" else "Off",
                    sideways = true, onFocused = { if (it) focused = "cheats" }) { scope.launch { store.setDownloadCheats(!s.downloadCheats) } }
            }
        }
        Spacer(Modifier.height(6.dp))
        val (title, text) = when (focused) {
            "add" -> "Add a folder" to "Another folder with N64 games: .z64, .v64, .n64, .zip and .7z. Other systems' ROMs are left out."
            "art" -> "Download box art" to "Covers from libretro's thumbnails, once per game. Off: no downloads; covers already here stay."
            "cheats" -> "Download cheats" to "Each game's cheat lists from joey64-cheats. Off: no downloads; lists already here stay."
            else -> "ROM folder" to "Where Joey64 finds your games. Removing it keeps their saves, states and play time."
        }
        DescriptionLine(title, text)
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
                        focused = "add"
                        scope.launch {
                            store.removeRomFolder(tree)
                            addStatus = null
                            // The focused row is gone: focus lands on Add a folder, not the rail.
                            landing?.arm()
                            library.removeFolder(tree)
                            // The data folder may be the same folder: its grant stays.
                            if (tree != DataFolders.treeUri(context)) try { RomFolders.release(context, tree.toUri()) } catch (e: Exception) {}
                            JLog.i(TAG, "ROM folder removed")
                        }
                    }
                }
            }
        }
    }
}

private const val TAG = "Joey64LibraryTab"

/** The folder's own name, and where it is ("SD Card › ROMs"). */
private fun folderName(context: android.content.Context, tree: String): Pair<String, String?> = try {
    val n = FolderNames.of(context, tree.toUri())
    val last = n.folders.lastOrNull()
    if (last == null) n.place to null else last to (listOf(n.place) + n.folders.dropLast(1)).joinToString(" › ")
} catch (e: Exception) { "ROM folder" to null }
