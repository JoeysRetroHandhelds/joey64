package com.joeysretro.joey64.launcher.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.FolderLook
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.AddLine
import com.joeysretro.joey64.launcher.components.ButtonRow
import com.joeysretro.joey64.launcher.components.FolderBox
import com.joeysretro.joey64.launcher.components.JoeyButton
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.SetupBody
import com.joeysretro.joey64.launcher.components.SetupButtonsTop
import com.joeysretro.joey64.launcher.components.SetupHeading
import com.joeysretro.joey64.launcher.components.SetupPage
import com.joeysretro.joey64.launcher.components.SetupSmall
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.storage.RomFolders
import kotlinx.coroutines.launch

/**
 * First run (Play-safe first run, approved by Joey 2026-11-05, screens 1 and 2):
 *  1. "Choose your ROMs folder": Android's folder picker, opened at the N64 folder in ROMs when
 *     there is one, else at ROMs.
 *  2. "Found N N64 games" in that folder. The folders are a list (Joey, 2026-11-06): each with its
 *     games and a Remove button, then "Add another folder", which picks one more. Use this folder
 *     (Use these folders) saves them all.
 * A folder with no games isn't added; the screen says so ("No N64 games found" with Choose
 * another folder when it was the first, a line under the list otherwise). Back takes the last
 * folder off the list. [onUse] saves the folders; Import from RetroArch follows ([ImportScreen]).
 *
 * Screens 3-4 (where saves go) wait for the core to open saves by descriptor.
 */
@Composable
fun FirstRunScreen(model: LibraryModel, onUse: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val landing = LocalFocusLanding.current
    val picked = remember { mutableStateListOf<FolderLook>() }
    // The last folder picked, when it had no N64 games (it isn't added).
    var empty by remember { mutableStateOf<FolderLook?>(null) }
    var looking by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { landing?.arm(); return@rememberLauncherForActivityResult }
        looking = true
        scope.launch {
            val look = try { model.look(uri) } catch (e: Exception) { FolderLook(uri, 0, "", emptyList()) }
            when {
                look.roms == 0 -> empty = look
                picked.none { it.tree == uri } -> { picked += look; empty = null }
                else -> empty = null   // the same folder again: already on the list
            }
            looking = false
            landing?.arm()
        }
    }
    val choose = { picker.launch(RomFolders.initialPickerUri(context)) }

    BackHandler(enabled = picked.isNotEmpty() || empty != null) {
        if (empty != null) empty = null else picked.removeAt(picked.lastIndex)
        landing?.arm()
    }

    SetupPage {
        when {
            looking -> SetupHeading("Looking for N64 games")
            picked.isEmpty() && empty == null -> {
                SetupHeading("Choose your ROMs folder")
                SetupBody("Android's folder picker opens at your N64 games folder when it finds one, else at your ROMs folder. Pick the folder with your N64 games, or the ROMs folder that holds an n64 folder.")
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Choose ROMs folder", primary = true, modifier = Modifier.initialFocus(), onClick = choose)
                }
            }
            picked.isEmpty() -> {
                val e = empty!!
                SetupHeading("No N64 games found")
                if (e.place.isNotEmpty()) FolderBox(e.place, e.folders, Modifier.padding(top = 16.dp))
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Choose another folder", primary = true, modifier = Modifier.initialFocus(), onClick = choose)
                }
            }
            else -> {
                SetupHeading(if (picked.size == 1) "Found " + games(picked[0].roms, n64 = true) else "Found N64 games in ${picked.size} folders")
                // One width for every line: the widest folder's.
                Column(
                    Modifier.padding(top = 16.dp).width(IntrinsicSize.Max),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (look in picked) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FolderBox(look.place, look.folders, Modifier.weight(1f), subs = games(look.roms, n64 = false))
                            JoeyButton("Remove", primary = false) {
                                picked.remove(look)
                                landing?.arm()
                            }
                        }
                    }
                    AddLine("Add another folder", Modifier.fillMaxWidth(), onClick = choose)
                }
                empty?.let { SetupSmall("No N64 games in " + (it.folders.lastOrNull() ?: it.place) + ", so it wasn't added.") }
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton(if (picked.size == 1) "Use this folder" else "Use these folders", primary = true, modifier = Modifier.initialFocus()) {
                        onUse(picked.map { it.tree })
                    }
                }
            }
        }
    }
}

/** "81 N64 games" / "1 N64 game", or without "N64": "81 games". */
private fun games(n: Int, n64: Boolean): String {
    val word = if (n == 1) "game" else "games"
    return if (n64) "$n N64 $word" else "$n $word"
}
