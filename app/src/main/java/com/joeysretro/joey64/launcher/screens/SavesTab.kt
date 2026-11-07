package com.joeysretro.joey64.launcher.screens

import android.net.Uri
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.PopupWarnNote
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.storage.DataFolder
import com.joeysretro.joey64.launcher.storage.DataFolders
import com.joeysretro.joey64.launcher.storage.DataMover
import com.joeysretro.joey64.launcher.storage.FolderNames
import com.joeysretro.joey64.launcher.storage.RomFolders
import com.joeysretro.joey64.launcher.storage.SrmSync
import com.joeysretro.joey64.saves.AppBackup
import com.joeysretro.joey64.saves.BackupChoice
import com.joeysretro.joey64.saves.BackupInfo
import com.joeysretro.joey64.saves.BackupPart
import com.joeysretro.joey64.saves.BackupWhen
import com.joeysretro.joey64.saves.ContinueOnPlay
import com.joeysretro.joey64.saves.SavesOptions
import com.joeysretro.joey64.saves.SavesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Saves (approved Saves mockup, 2026-11-07: scratchpad build_saves.py), the same for every
 * game. Left: Save on quit, Continue on Play, Quit saves to keep; Back up saves (save RAM copies in
 * `backups/`). Right: Back up Joey64 and Restore a backup (a .zip, [AppBackup]), Import from RetroArch
 * (the first-run pages again, [onImport]), Data folder ([DataFolders]); Advanced: Recovery state.
 * A row that's working shows how far it is as its value, then what came of it.
 */
@Composable
fun SavesTab(onHints: (List<Hint>) -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier, startRow: String = "quit") {
    val context = LocalContext.current
    val landing = LocalFocusLanding.current
    val scope = rememberCoroutineScope()
    val store = remember { SavesStore(context) }
    var o by remember { mutableStateOf(store.read()) }
    var focused by remember { mutableStateOf(startRow) }
    var popup by remember { mutableStateOf<String?>(null) }
    // A row's progress or outcome ("Saved · 120 files"), by row key.
    var status by remember { mutableStateOf<Pair<String, String>?>(null) }
    var backupParts by remember { mutableStateOf(BackupChoice.Everything.parts) }
    var restoring by remember { mutableStateOf<Pair<Uri, BackupInfo>?>(null) }
    var folderTree by remember { mutableStateOf(DataFolders.treeUri(context)) }
    val busy = status?.second?.endsWith("…") == true

    fun set(next: SavesOptions) { store.save(next); o = next }
    fun show(row: String, text: String) { status = row to text }

    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        landing?.arm()
        uri ?: return@rememberLauncherForActivityResult
        show("backup", "Backing up…")
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    SrmSync.pushAll(context)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        AppBackup.write(context, DataFolders.current(context).space, backupParts, out) { k -> if (k % 20 == 0) status = "backup" to "Backing up · $k files…" }
                    }
                } catch (e: Exception) { JLog.e(TAG, "backup failed: $e"); null }
            }
            show("backup", if (n == null) "Couldn't back up" else "Saved · $n files")
        }
    }
    val openZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        landing?.arm()
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val info = withContext(Dispatchers.IO) { try { context.contentResolver.openInputStream(uri)?.use { AppBackup.info(it) } } catch (e: Exception) { null } }
            if (info == null) show("restore", "Not a Joey64 backup") else { restoring = uri to info; popup = "restore" }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        landing?.arm()
        tree ?: return@rememberLauncherForActivityResult
        moveData(context, scope, tree.toString(), { show("folder", it) }) { folderTree = it }
    }

    LaunchedEffect(focused, busy) {
        onHints(if (busy && status?.first == focused) listOf(Hint("L/R", "Tab"), Hint("B", "Back"))
            else listOf(Hint("A", "Change"), Hint("L/R", "Tab"), Hint("B", "Back")))
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth().focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            @Composable
            // Focus lands back on the row that was in use (after Android's file and folder pickers too).
            fun row(key: String, label: String, value: String, open: () -> Unit) =
                JoeyRow(label, Modifier.initialFocus(key == focused), value = status?.takeIf { it.first == key }?.second ?: value,
                    sideways = true, onFocused = { if (it) focused = key }) { if (!busy) open() }
            Column(Modifier.weight(1f)) {
                SectionLabel("When you quit and play")
                row("quit", "Save on quit", if (o.saveOnQuit) "On" else "Off") { set(o.copy(saveOnQuit = !o.saveOnQuit)) }
                row("cont", "Continue on Play", o.continueOnPlay.label + "  ›") { popup = "cont" }
                row("keep", "Quit saves to keep", "${o.quitSaves}  ›") { popup = "keep" }
                SectionLabel("Save backups")
                row("bk", "Back up saves", o.backups.label + "  ›") { popup = "bk" }
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("Your data")
                row("backup", "Back up Joey64", "›") { popup = "backup" }
                row("restore", "Restore a backup", "›") { openZip.launch(arrayOf("application/zip", "application/octet-stream")) }
                row("import", "Import from RetroArch", "›") { onImport() }
                row("folder", "Data folder", folderLabel(context, folderTree) + "  ›") { popup = "folder" }
                SectionLabel("Advanced")
                row("rec", "Recovery state", recoveryLabel(o.recoveryMinutes) + "  ›") { popup = "rec" }
            }
        }
        Spacer(Modifier.height(6.dp))
        val (title, text) = describe(focused, o)
        DescriptionLine(title, text)
    }

    val close = { popup = null }
    when (popup) {
        "cont" -> ChoiceList("Continue on Play", ContinueOnPlay.entries.map { Triple(it.label, it.detail, it == o.continueOnPlay) }, close,
            sub = "When a game has a quit save, Play:") { set(o.copy(continueOnPlay = ContinueOnPlay.entries[it])); popup = null }
        "keep" -> ChoiceList("Quit saves to keep", SavesOptions.QUIT_SAVES.map { Triple("$it", if (it == 1) "Each quit replaces the last" else null, it == o.quitSaves) }, close,
            sub = "Each quit keeps the ones before, newest first. Continue loads the newest.") { set(o.copy(quitSaves = SavesOptions.QUIT_SAVES[it])); popup = null }
        "bk" -> BackupsPopup(o, close, { set(o.copy(backups = it)); popup = null }) { popup = "bkkeep" }
        "bkkeep" -> ChoiceList("Copies to keep", SavesOptions.BACKUPS_KEEP.map { Triple("$it", null, it == o.backupsKeep) }, { popup = "bk" },
            sub = "Per game, oldest removed first.") { set(o.copy(backupsKeep = SavesOptions.BACKUPS_KEEP[it])); popup = "bk" }
        "rec" -> ChoiceList("Recovery state", SavesOptions.RECOVERY_MINUTES.map { Triple(recoveryLabel(it), null, it == o.recoveryMinutes) }, close,
            sub = "Saved every few minutes while you play, and offered if Joey64 closes by itself.") { set(o.copy(recoveryMinutes = SavesOptions.RECOVERY_MINUTES[it])); popup = null }
        "backup" -> ChoiceList("Back up Joey64", BackupChoice.entries.map { Triple(it.label, it.detail, false) }, close,
            sub = "Makes \"${AppBackup.fileName()}\" in a folder you pick.") {
            backupParts = BackupChoice.entries[it].parts; popup = null
            createZip.launch(AppBackup.fileName())
        }
        "restore" -> restoring?.let { (uri, info) ->
            RestorePopup(info, { popup = null; restoring = null }) { parts ->
                popup = null; restoring = null
                show("restore", "Restoring…")
                scope.launch {
                    val n = withContext(Dispatchers.IO) {
                        try { context.contentResolver.openInputStream(uri)?.use { AppBackup.restore(context, DataFolders.current(context).space, parts, it) } }
                        catch (e: Exception) { JLog.e(TAG, "restore failed: $e"); null }
                    }
                    o = store.read()
                    show("restore", if (n == null) "Couldn't restore" else "Restored · $n files")
                }
            }
        }
        "folder" -> FolderPopup(folderTree == null, close) { pick ->
            popup = null
            if (pick) pickFolder.launch(null)
            else if (folderTree != null) moveData(context, scope, null, { show("folder", it) }) { folderTree = it }
        }
    }
}

private const val TAG = "Joey64SavesTab"

private fun recoveryLabel(min: Int) = if (min == 0) "Off" else "Every $min min"

private fun folderLabel(context: android.content.Context, tree: String?): String =
    if (tree == null) "Joey64's own" else try { FolderNames.of(context, tree.toUri()).folders.lastOrNull() ?: FolderNames.of(context, tree.toUri()).place } catch (e: Exception) { "Picked folder" }

/**
 * Moves the data to [tree] (null: back to Joey64's own storage) and switches to it. A file that
 * couldn't be moved stays where it was, and the row says how many.
 */
private fun moveData(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, tree: String?, show: (String) -> Unit, done: (String?) -> Unit) {
    show("Moving…")
    scope.launch {
        val r = withContext(Dispatchers.IO) {
            try {
                val target = if (tree == null) DataFolder.internal(context) else {
                    RomFolders.keep(context, tree.toUri(), write = true)
                    DataFolder.picked(context, tree.toUri())
                }
                val from = DataFolders.current(context)
                if (!from.canMoveTo(target)) return@withContext null
                SrmSync.pushAll(context)
                val res = DataMover.move(from.space, target.space, DataFolders.KINDS)
                    .also { com.joeysretro.joey64.launcher.storage.DataFolderRules.hideFromGallery(target.space, DataFolders.KINDS) }
                val old = DataFolders.treeUri(context)
                DataFolders.set(context, tree)
                SettingsStore(context).setDataFolder(tree)
                if (old != null && old != tree) try { RomFolders.release(context, old.toUri()) } catch (e: Exception) {}
                JLog.i(TAG, "data folder: moved ${res.moved}, already there ${res.alreadyThere}, kept ${res.conflicts.size}, failed ${res.failed.size}")
                res
            } catch (e: Exception) { JLog.e(TAG, "data folder: move failed: $e"); null }
        }
        if (r == null) { show("Couldn't move"); return@launch }
        done(tree)
        val left = r.conflicts.size + r.failed.size
        show(if (left == 0) "Moved · ${r.moved + r.alreadyThere} files" else "Moved · $left files left behind")
    }
}

private fun describe(row: String, o: SavesOptions): Pair<String, String> = when (row) {
    "quit" -> "Save on quit" to if (o.saveOnQuit) "Quitting a game saves where you are, for Continue. Off: quitting saves nothing; the game's own saves still save."
        else "Off: quitting saves nothing. Your last quit save is kept and Continue still loads it."
    "cont" -> "Continue on Play" to "What Play does for a game you quit with a quit save: start from the beginning, ask, or go on from there."
    "keep" -> "Quit saves to keep" to "More than one: Load state has Auto 2, Auto 3 ... after the slots. Continue loads the newest."
    "bk" -> "Back up saves" to "Copies of each game's in-game save in backups/, next to saves/. The newest ${o.backupsKeep} per game are kept."
    "backup" -> "Back up Joey64" to "One .zip of your saves, states, screenshots and settings, in a folder you pick. ROMs and box art aren't in it."
    "restore" -> "Restore a backup" to "Brings back a Joey64 backup. Files with the same name are replaced; the rest stay."
    "import" -> "Import from RetroArch" to "Copies RetroArch's N64 saves and states into Joey64. RetroArch's own files aren't changed."
    "folder" -> "Data folder" to "Where saves, states, screenshots, cheats and save backups live."
    else -> "Recovery state" to "Saved every few minutes while you play, and offered if Joey64 closes by itself. Off: no crash question."
}

/** Back up saves (mockup screen 6): On start, Daily, Off, then Copies to keep. */
@Composable
private fun BackupsPopup(o: SavesOptions, onDismiss: () -> Unit, onPick: (BackupWhen) -> Unit, onKeep: () -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle("Back up saves", "Copies of each game's in-game save into backups/.")
            for (w in BackupWhen.entries) JoeyRow(w.label, Modifier.initialFocus(w == o.backups), detail = w.detail.ifEmpty { null },
                trailing = if (w == o.backups) { _ -> PickerTick() } else null) { onPick(w) }
            PopupDivider()
            JoeyRow("Copies to keep", value = "${o.backupsKeep}  ›", detail = "Per game, oldest removed first", onClick = onKeep)
        }
    }
}

/** Restore a backup (mockup screen 8): what's in it, then Everything in it / Saves only / Settings only (the parts it has). */
@Composable
private fun RestorePopup(info: BackupInfo, onDismiss: () -> Unit, onPick: (Set<BackupPart>) -> Unit) {
    val made = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.US).format(java.util.Date(info.madeAt))
    val has = buildList {
        fun n(k: Int, one: String) = if (k == 1) "1 $one" else "$k ${one}s"
        if (BackupPart.Saves in info.parts) { add(n(info.saves, "save")); add(n(info.states, "state")) }
        if (BackupPart.Screenshots in info.parts) add(n(info.screenshots, "screenshot"))
        if (BackupPart.Settings in info.parts) add("settings")
    }.joinToString(", ")
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle("Restore a backup", "Joey64 backup $made · $has")
            JoeyRow("Everything in it", Modifier.initialFocus()) { onPick(info.parts) }
            // A backup of one part has only "Everything in it".
            val mixed = BackupPart.Settings in info.parts && BackupPart.Saves in info.parts
            // Saves only: in-game saves and states (mockup screen 7), not screenshots.
            if (mixed) JoeyRow("Saves only") { onPick(setOf(BackupPart.Saves)) }
            if (mixed) JoeyRow("Settings only") { onPick(setOf(BackupPart.Settings)) }
            PopupWarnNote("Saves and settings with the same name are replaced by the backup's. The rest stay.")
        }
    }
}

/** Data folder (mockup screen 10): Joey64's own, or Pick a folder (Android's folder picker). [onPick] true = Pick a folder. */
@Composable
private fun FolderPopup(own: Boolean, onDismiss: () -> Unit, onPick: (Boolean) -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle("Data folder", "Saves, states, screenshots, cheats and save backups. Moving copies them all, then removes the old ones.")
            JoeyRow("Joey64's own", Modifier.initialFocus(own), detail = "Internal storage. Deleted if Joey64 is uninstalled",
                trailing = if (own) { _ -> PickerTick() } else null) { onPick(false) }
            JoeyRow("Pick a folder", Modifier.initialFocus(!own), value = "›", detail = "For example on the SD card. Kept if Joey64 is uninstalled",
                trailing = if (!own) { _ -> PickerTick() } else null) { onPick(true) }
        }
    }
}
