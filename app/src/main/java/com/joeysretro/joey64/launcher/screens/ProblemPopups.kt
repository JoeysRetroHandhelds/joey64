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
 * Netplay's library popups. The room is lost (approved netplay mockup 2026-11-10, screen 4, "OK only": "The netplay game
 * ended"); Couldn't reach the room and Your copy doesn't match the host's (Play together v2, approved 2026-11-06), the
 * joiner's side of a join that failed; Couldn't join the room (the room is full or the place is taken: approved mockup B
 * 2026-11-10, scratchpad build_netplay4.py, Try again starts the same join again). The host's name and copy aren't sent by
 * the room yet, so they read "the host".
 * Details (mismatch): this player's file and MD5.
 */
@Composable
internal fun NetEndedPopup(model: LibraryModel) {
    val n = model.netEnded.collectAsStateWithLifecycle().value ?: return
    var details by remember(n) { mutableStateOf(false) }
    val c = JoeyTheme.colors
    val context = LocalContext.current
    val turned = n.need == com.joeysretro.joey64.launcher.GameStartFailed.Need.NetTurnedDown
    JoeyDialog(
        onDismiss = { model.netEndedSeen() },
        hints = when {
            details -> listOf(Hint("A", "OK"), Hint("B", "Back"))
            turned -> listOf(Hint("A", "Select"), Hint("B", "Close"))
            else -> listOf(Hint("A", if (n.need == com.joeysretro.joey64.launcher.GameStartFailed.Need.NetMismatch) "Select" else "OK"))
        },
        interceptBack = { if (details) { details = false; true } else false },
        width = 470.dp,
    ) {
        key(details) { Column {
            LandAgain()
            fun bold(s: String) = SpanStyle(fontWeight = FontWeight.Bold, color = c.text)
            when {
                details -> {
                    PopupTitle(n.game.title, "Your copy")
                    InfoRow("File", n.file, labelColor = c.textDim, valueColor = c.text)
                    InfoRow("MD5", n.game.md5, labelColor = c.textDim, valueColor = c.text, valueStyle = JoeyTheme.type.code)
                    JoeyOption("OK", Modifier.initialFocus()) { model.netEndedSeen() }
                }
                n.need == com.joeysretro.joey64.launcher.GameStartFailed.Need.NetLost -> {
                    PopupTitle("The netplay game ended")
                    PromptText(AnnotatedStringOf("Joey64 lost the connection to the room. The room may have closed, or the Wi-Fi dropped."))
                    JoeyOption("OK", Modifier.initialFocus()) { model.netEndedSeen() }
                }
                turned -> {
                    PopupTitle("Couldn't join the room")
                    PromptText(AnnotatedStringOf("The room has no place for you: all its players are taken. If someone left, the host can free their place on the room screen, then join again."))
                    JoeyOption("Try again", Modifier.initialFocus()) { model.netTryAgain { context.startActivity(it) } }
                    JoeyOption("OK") { model.netEndedSeen() }
                }
                n.need == com.joeysretro.joey64.launcher.GameStartFailed.Need.NetUnreachable -> {
                    PopupTitle("Couldn't reach the room")
                    PromptText(AnnotatedStringOf("The code is right, but the host's router isn't letting players from outside in. On the same Wi-Fi as the host, it works."))
                    JoeyOption("OK", Modifier.initialFocus()) { model.netEndedSeen() }
                }
                else -> {
                    PopupTitle("Your copy doesn't match the host's")
                    PromptText(buildAnnotatedString {
                        append("Everyone needs the same copy of ")
                        pushStyle(bold("")); append(com.joeysretro.joey64.launcher.library.LibraryArrange.shortTitle(n.game.title)); pop()
                        append(" or the games fall out of step. Yours is ")
                        pushStyle(bold("")); append(n.file); pop()
                        append(".")
                    })
                    JoeyOption("OK", Modifier.initialFocus()) { model.netEndedSeen() }
                    JoeyOption("Details") { details = true }
                }
            }
        } }
    }
}

private fun AnnotatedStringOf(s: String) = androidx.compose.ui.text.AnnotatedString(s)

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
