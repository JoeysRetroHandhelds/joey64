package com.joeysretro.joey64.launcher.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.FolderLook
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.ButtonRow
import com.joeysretro.joey64.launcher.components.FolderBox
import com.joeysretro.joey64.launcher.components.JoeyButton
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.Wordmark
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.storage.RomFolders
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.launch

/**
 * First run (Play-safe first run, approved by Joey 2026-11-05, screens 1 and 2):
 *  1. "Choose your ROMs folder": Android's folder picker, opened at the ROMs folder with games.
 *  2. "Found N N64 games" in that folder: Use this folder, or Choose another folder.
 * A folder with no games says so on the same screen ("No N64 games found") with only Choose
 * another folder. [onUse] saves the folder; the library takes over from there.
 *
 * Screens 3-4 (where saves go) and 5-7 (Import from RetroArch) come next; see the build plan.
 */
@Composable
fun FirstRunScreen(model: LibraryModel, onUse: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val landing = LocalFocusLanding.current
    var look by remember { mutableStateOf<FolderLook?>(null) }
    var looking by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { landing?.arm(); return@rememberLauncherForActivityResult }
        looking = true
        scope.launch {
            look = try { model.look(uri) } catch (e: Exception) { FolderLook(uri, 0, "", emptyList()) }
            looking = false
            landing?.arm()
        }
    }
    val choose = { picker.launch(RomFolders.initialPickerUri(context)) }

    BackHandler(enabled = look != null) { look = null; landing?.arm() }

    val found = look
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp).padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark(Modifier.padding(bottom = 26.dp))
        when {
            looking -> Heading("Looking for N64 games")
            found == null -> {
                Heading("Choose your ROMs folder")
                Body("Android's folder picker opens at your ROMs folder. Pick the folder with your N64 games, or the ROMs folder that holds an n64 folder.")
                ButtonRow(Modifier.padding(top = 22.dp)) {
                    JoeyButton("Choose ROMs folder", primary = true, modifier = Modifier.initialFocus(), onClick = choose)
                }
            }
            found.roms == 0 -> {
                Heading("No N64 games found")
                if (found.place.isNotEmpty()) FolderBox(found.place, found.folders, Modifier.padding(top = 16.dp))
                ButtonRow(Modifier.padding(top = 22.dp)) {
                    JoeyButton("Choose another folder", primary = true, modifier = Modifier.initialFocus(), onClick = choose)
                }
            }
            else -> {
                Heading(if (found.roms == 1) "Found 1 N64 game" else "Found ${found.roms} N64 games")
                FolderBox(found.place, found.folders, Modifier.padding(top = 16.dp))
                ButtonRow(Modifier.padding(top = 22.dp)) {
                    JoeyButton("Use this folder", primary = true, modifier = Modifier.initialFocus()) { onUse(found.tree) }
                    JoeyButton("Choose another folder", primary = false, onClick = choose)
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = JoeyTheme.type.heading, color = JoeyTheme.colors.text, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(text, style = JoeyTheme.type.body, color = JoeyTheme.colors.textDim, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 7.dp).widthIn(max = 560.dp))
}
