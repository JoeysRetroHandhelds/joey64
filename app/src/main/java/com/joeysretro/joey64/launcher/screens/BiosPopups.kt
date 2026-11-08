package com.joeysretro.joey64.launcher.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.library.GameLauncher
import com.joeysretro.joey64.launcher.storage.BiosImport

/** The 64DD BIOS picker (and the missing cartridge's): Android's folder picker (Joey 2026-11-07: pick a folder, Joey64 searches it, BiosImport.importFolder). */
@Composable
internal fun rememberBiosPicker(onPicked: (Uri?) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> onPicked(uri) }

internal const val NO_BIOS = "No 64DD BIOS in that folder"

/**
 * Lands focus again each time the popup's contents are built, so the rebuilt "No ... in that folder" text has Choose
 * again focused (2026-11-07 on the Thor: nothing focused until a D-pad press). The popup's landing had already landed
 * on Choose folder, which the rebuild removes; and the picker leaves the window in touch mode (a tap on the system's
 * Allow), where Compose refuses to focus an option, so it goes back to key mode first, as [JoeyDialog] does on open.
 * The picker's result no longer arms the screen's landing either: that moved focus to the game behind the popup.
 */
@Composable
private fun LandAgain() {
    val landing = LocalFocusLanding.current
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        inputMode.requestInputMode(InputMode.Keyboard)
        landing?.arm()
    }
}

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
    val picker = rememberBiosPicker { uri ->
        uri?.let { model.biosPicked(it, start) }
    }
    JoeyDialog(onDismiss = { model.biosCancelled() }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 460.dp) {
        // Built again when the text changes, so focus starts on its first option.
        key(ask.missed) { Column {
            LandAgain()
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

/**
 * Play on F-Zero X Expansion Kit or Dezaemon DD with no F-Zero X (Japan) / Dezaemon 3D (Japan) in the library (Joey
 * 2026-11-07, popup A, the BIOS popup's flow): "This disk needs F-Zero X (Japan)" with Choose folder and Cancel; the
 * picked folder is read into the library ([LibraryModel.cartPicked]) and the disk starts with the cartridge, or "No
 * F-Zero X (Japan) in that folder" with Choose again and Cancel. Shown by the library and Search, where Play is.
 */
@Composable
internal fun NeedsCartPopup(model: LibraryModel, start: (Intent) -> Unit) {
    val ask = model.needsCart.collectAsStateWithLifecycle().value ?: return
    val picker = rememberBiosPicker { uri ->
        uri?.let { model.cartPicked(it, start) }
    }
    val cart = GameLauncher.CART_NAMES[ask.code] ?: ask.code
    val disk = GameLauncher.DISK_NAMES[ask.code] ?: ask.game.title
    JoeyDialog(onDismiss = { model.cartCancelled() }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 460.dp) {
        // Built again when the text changes, so focus starts on its first option.
        key(ask.missed) { Column {
            LandAgain()
            if (ask.missed) {
                PopupTitle("No $cart in that folder")
                JoeyOption("Choose again", modifier = Modifier.initialFocus()) { picker.launch(null) }
            } else {
                PopupTitle("This disk needs $cart", "$disk plays from the $cart cartridge. Pick the folder with it and Joey64 will add it to your library.")
                JoeyOption("Choose folder", modifier = Modifier.initialFocus()) { picker.launch(null) }
            }
            JoeyOption("Cancel") { model.cartCancelled() }
        } }
    }
}
