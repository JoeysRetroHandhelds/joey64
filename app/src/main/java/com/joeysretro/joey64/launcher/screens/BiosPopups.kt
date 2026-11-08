package com.joeysretro.joey64.launcher.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** The 64DD BIOS picker: Android's file picker, any type (.n64, .z64, .v64, .bin, .zip, .7z), one file or several. */
@Composable
internal fun rememberBiosPicker(onPicked: (List<Uri>) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onPicked(uris) }

internal val BIOS_TYPES = arrayOf("*/*")

/** "No 64DD BIOS in that file" (or "those files", for several picked). */
internal fun noBiosTitle(picked: Int) = if (picked > 1) "No 64DD BIOS in those files" else "No 64DD BIOS in that file"

/** "Found 64DD BIOS: Japan v1.2, USA (prototype)". */
internal fun foundBiosTitle(found: List<BiosImport.Ipl>) = "Found 64DD BIOS: " + found.joinToString(", ") { it.label }

/**
 * Play on a 64DD disk with no 64DD BIOS (approved 2026-11-07): "This game needs the 64DD BIOS" with Choose file and
 * Cancel; after a good pick the game starts ([LibraryModel.biosPicked]), after a pick without one "No 64DD BIOS in
 * that file" with Choose again and Cancel. Shown by the library and Search, where Play is.
 */
@Composable
internal fun NeedsBiosPopup(model: LibraryModel, start: (Intent) -> Unit) {
    val ask = model.needsBios.collectAsStateWithLifecycle().value ?: return
    val landing = LocalFocusLanding.current
    var picked by remember { mutableIntStateOf(0) }
    val picker = rememberBiosPicker { uris ->
        landing?.arm()
        picked = uris.size
        model.biosPicked(uris, start)
    }
    JoeyDialog(onDismiss = { model.biosCancelled() }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 460.dp) {
        // Built again when the text changes, so focus starts on its first option.
        key(ask.missed) { Column {
            if (ask.missed) {
                PopupTitle(noBiosTitle(picked))
                JoeyOption("Choose again", modifier = Modifier.initialFocus()) { picker.launch(BIOS_TYPES) }
            } else {
                PopupTitle("This game needs the 64DD BIOS", "64DD disks start from the 64DD's own BIOS. Pick your Japan v1.2 file and Joey64 will keep a copy.")
                JoeyOption("Choose file", modifier = Modifier.initialFocus()) { picker.launch(BIOS_TYPES) }
            }
            JoeyOption("Cancel") { model.biosCancelled() }
        } }
    }
}
