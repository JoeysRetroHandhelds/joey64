package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.InfoRow
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.PromptText
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.library.DetailsText
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * Screens 1-2 of the approved problem screens (scratchpad build_problems2.py / problems2_mock.png, "Approve as drawn"
 * 2026-11-08): a game the library started that didn't start. "<game> won't start" with OK and Details; the text says
 * the file looks damaged only when it has a No-Intro name but not that copy's MD5 ([LibraryModel.StartFailed.damaged]),
 * else "Joey64 couldn't start it". Details: File, Folder, Size, MD5, What happened, then OK; B goes back.
 */
@Composable
internal fun StartFailedPopup(model: LibraryModel) {
    val f = model.startFailed.collectAsStateWithLifecycle().value ?: return
    var details by remember(f) { mutableStateOf(false) }
    val c = JoeyTheme.colors
    JoeyDialog(
        onDismiss = { model.startFailedSeen() },
        hints = if (details) listOf(Hint("A", "OK"), Hint("B", "Back")) else listOf(Hint("A", "Select"), Hint("B", "Close")),
        interceptBack = { if (details) { details = false; true } else false },
        width = 470.dp,
    ) {
        // Built again on each step, so focus starts on its first option.
        key(details) { Column {
            LandAgain()
            if (!details) {
                val where = (listOf(f.file) + listOfNotNull(f.folders.joinToString(" › ").ifEmpty { null })).joinToString(" · ")
                PopupTitle("${f.game.title} won't start",
                    (if (f.damaged) "The file looks damaged: it doesn't match any known copy of the game. Copying it again usually fixes this."
                    else "Joey64 couldn't start it.") + "\n" + where)
                JoeyOption("OK", Modifier.initialFocus()) { model.startFailedSeen() }
                JoeyOption("Details") { details = true }
            } else {
                PopupTitle(f.game.title, "Why it didn't start")
                @Composable
                fun Row(label: String, value: String, code: Boolean = false) =
                    InfoRow(label, value, labelColor = c.textDim, valueColor = c.text,
                        valueStyle = if (code) JoeyTheme.type.code else JoeyTheme.type.row)
                Row("File", f.file)
                val folder = (listOfNotNull(f.place) + f.folders).joinToString(" › ")
                if (folder.isNotEmpty()) Row("Folder", folder)
                DetailsText.size(f.size).takeIf { it.isNotEmpty() }?.let { Row("Size", it) }
                Row("MD5", f.game.md5, code = true)
                Row("What happened", f.why.text)
                JoeyOption("OK", Modifier.initialFocus()) { model.startFailedSeen() }
            }
        } }
    }
}

/**
 * Screen 3: a ROM folder that can't be reached (the SD card is out, or Android took the permission away). Its games are
 * greyed; this asks once when the library opens, and again on A on a greyed game. Try again reads the folder; Choose the
 * folder again opens Android's folder picker; Not now (and B) closes it.
 */
@Composable
internal fun LostFolderPopup(model: LibraryModel) {
    val ask = model.lostFolder.collectAsStateWithLifecycle().value ?: return
    val context = LocalContext.current
    val store = remember { com.joeysretro.joey64.launcher.settings.SettingsStore(context) }
    val folders = store.settings.collectAsStateWithLifecycle(initialValue = null).value?.romFolders.orEmpty().map { it.toUri() }
    val picker = rememberBiosPicker { uri -> uri?.let { model.lostChosen(it, folders) } }
    JoeyDialog(onDismiss = { model.lostNotNow() }, hints = listOf(Hint("A", "Select"), Hint("B", "Not now")), width = 470.dp) {
        key(ask) { Column {
            LandAgain()
            PopupTitle("Can't reach your ROMs folder")
            PromptText(buildAnnotatedString {
                append("Android stopped letting Joey64 open ")
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = JoeyTheme.colors.text)); append(ask.path); pop()
                append(". If the SD card was taken out, put it back. Games from that folder are greyed out until then.")
            })
            JoeyOption("Try again", Modifier.initialFocus()) { model.lostTryAgain(folders) }
            JoeyOption("Choose the folder again") { picker.launch(null) }
            JoeyOption("Not now") { model.lostNotNow() }
        } }
    }
}
