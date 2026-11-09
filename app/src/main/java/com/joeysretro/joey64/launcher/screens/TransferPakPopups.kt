package com.joeysretro.joey64.launcher.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.R
import com.joeysretro.joey64.controls.GameBoyFiles
import com.joeysretro.joey64.controls.PlayerSetup
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupNote
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Transfer Pak setup (approved paks mockup 2026-11-08, scratchpad build_paks2.py / paks2_mock.png, "Approve as drawn",
 * screen 4): the Game Boy game and its save for one game. B is Done.
 */
@Composable
internal fun TransferSetupPopup(gameTitle: String, setup: PlayerSetup, row: String, onDismiss: () -> Unit,
                                onGame: () -> Unit, onSave: () -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Change"), Hint("B", "Done")), width = 470.dp) {
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
 * Screen 5: the Game Boy games ([games]) or saves found in the ROM folders' gb and gbc folders, None, and Choose a file
 * (Android's picker; only where the screen can open it, so not over a paused game). [onPick] gets the pick and, for a
 * game, the save that goes with it by default (same name, .sav first); null is None.
 */
@Composable
internal fun GameBoyListPopup(games: Boolean, setup: PlayerSetup, onDismiss: () -> Unit,
                              onPick: (GameBoyFiles.Found?, GameBoyFiles.Found?) -> Unit) {
    val context = LocalContext.current
    var found by remember { mutableStateOf<Pair<List<GameBoyFiles.Found>, List<GameBoyFiles.Found>>?>(null) }
    LaunchedEffect(Unit) {
        found = withContext(Dispatchers.IO) {
            val trees = SettingsStore(context).settings.first().romFolders.sorted().map { Uri.parse(it) }
            GameBoyFiles.scan(context, trees)
        }
    }
    val current = if (games) setup.gbGame else setup.gbSave
    val list = found?.let { (g, s) ->
        val l = if (games) g else s.sortedBy { sv ->
            // The saves that go with the picked game first.
            if (setup.gbGame != null && GameBoyFiles.title(sv.name).equals(GameBoyFiles.title(setup.gbGame.name), true)) 0 else 1
        }
        // A file picked elsewhere isn't in the folders: it still shows, ticked.
        if (current != null && l.none { it.uri == current.uri }) listOf(current) + l else l
    }
    fun pick(f: GameBoyFiles.Found?) {
        if (!games) { onPick(f, null); return }
        onPick(f, f?.let { GameBoyFiles.saveFor(it, found?.second.orEmpty()) })
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Back")), width = 470.dp) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            PopupTitle(if (games) "Game Boy game" else "Game Boy save", "From your ROMs folder's gb and gbc folders.")
            when {
                list == null -> PopupNote("Looking…")
                list.isEmpty() -> PopupNote(if (games) "No Game Boy games there." else "No Game Boy saves there.")
                else -> for (f in list) {
                    val on = f.uri == current?.uri
                    JoeyRow(if (games) GameBoyFiles.title(f.name) else f.name, Modifier.initialFocus(on),
                        trailing = if (on) { _ -> TickMark() } else null) { pick(f) }
                }
            }
            PopupDivider()
            JoeyRow("None", Modifier.initialFocus(current == null),
                detail = if (games) "No game in the Transfer Pak" else "A new save",
                trailing = if (current == null) { _ -> TickMark() } else null) { pick(null) }
            if (LocalActivityResultRegistryOwner.current != null) ChooseFileRow(games) { pick(it) }
        }
    }
}

@Composable
private fun TickMark() {
    Icon(painterResource(R.drawable.ic_tick), contentDescription = "Chosen", tint = JoeyTheme.colors.gold, modifier = Modifier.size(18.dp))
}

/** Choose a file: Android's picker; a file that isn't a Game Boy game (or save) is left out, with a log line. */
@Composable
private fun ChooseFileRow(games: Boolean, onPicked: (GameBoyFiles.Found) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = GameBoyFiles.nameOf(context, uri) ?: return@rememberLauncherForActivityResult
        if (if (games) !GameBoyFiles.isGame(name) else !GameBoyFiles.isSave(name)) {
            JLog.w("Joey64GameBoy", "picked $name: not a Game Boy ${if (games) "game" else "save"}"); return@rememberLauncherForActivityResult
        }
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (e: Exception) { JLog.w("Joey64GameBoy", "no lasting permission for $name: $e") }
        onPicked(GameBoyFiles.Found(uri.toString(), name))
    }
    JoeyRow("Choose a file") { picker.launch(arrayOf("*/*")) }
}
