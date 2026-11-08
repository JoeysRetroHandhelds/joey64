package com.joeysretro.joey64.launcher.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.storage.BiosImport

/** The 64DD BIOS picker: Android's folder picker (Joey 2026-11-07: pick a folder, Joey64 searches it, BiosImport.importFolder). */
@Composable
internal fun rememberBiosPicker(onPicked: (Uri?) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> onPicked(uri) }

internal const val NO_BIOS = "No 64DD BIOS in that folder"

/** "Found 64DD BIOS: Japan v1.2, USA (prototype)". */
internal fun foundBiosTitle(found: List<BiosImport.Ipl>) = "Found 64DD BIOS: " + found.joinToString(", ") { it.label }

/**
 * Play on a 64DD disk with no 64DD BIOS (approved 2026-11-07; a folder pick since Joey's "let user pick a folder and
 * we search that folder"): "This game needs the 64DD BIOS" with Choose folder and Cancel; after a good pick the game
 * starts ([LibraryModel.biosPicked]), after a pick without one "No 64DD BIOS in that folder" with Choose again and
 * Cancel. Shown by the library and Search, where Play is.
 */
@Composable
internal fun NeedsBiosPopup(model: LibraryModel, start: (Intent) -> Unit) {
    val ask = model.needsBios.collectAsStateWithLifecycle().value ?: return
    val landing = LocalFocusLanding.current
    val picker = rememberBiosPicker { uri ->
        landing?.arm()
        uri?.let { model.biosPicked(it, start) }
    }
    JoeyDialog(onDismiss = { model.biosCancelled() }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 460.dp) {
        // Built again when the text changes, so focus starts on its first option.
        key(ask.missed) { Column {
            if (ask.missed) {
                PopupTitle(NO_BIOS)
                JoeyOption("Choose again", modifier = Modifier.initialFocus()) { picker.launch(null) }
            } else {
                PopupTitle("This game needs the 64DD BIOS", "64DD disks start from the 64DD's own BIOS. Pick the folder with your Japan v1.2 file and Joey64 will keep a copy.")
                JoeyOption("Choose folder", modifier = Modifier.initialFocus()) { picker.launch(null) }
            }
            JoeyOption("Cancel") { model.biosCancelled() }
        } }
    }
}
