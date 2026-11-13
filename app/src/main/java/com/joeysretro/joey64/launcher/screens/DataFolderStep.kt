package com.joeysretro.joey64.launcher.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.joeysretro.joey64.JLog
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
import com.joeysretro.joey64.launcher.storage.DataFolderMove
import com.joeysretro.joey64.launcher.storage.FolderName
import com.joeysretro.joey64.launcher.storage.FolderNames
import androidx.core.net.toUri

/**
 * First run, screens 3-4 (Play-safe first run, approved by Joey 2026-11-05: scratchpad build_first2.py;
 * built 2026-11-07), between the ROMs folder and Import from RetroArch:
 *  3. "Where should Joey64 keep saves, states and screenshots?": Choose folder (Android's picker) or
 *     Keep them in Joey64.
 *  4. "Saves, states and screenshots go here" with the folder: Continue, or Choose another folder.
 * A picked folder is used as Settings › Saves › Data folder does ([DataFolderMove]): anything Joey64
 * already has moves there. One change from the mockup: its "later in Settings › Library" line says
 * Settings › Saves, where Data folder is now. Added where the mockup had nothing: "Moving" while it
 * moves, and "Couldn't use that folder" when the move fails.
 * [debugOnly] (the SHOW broadcast's "data"): a pick shows screen 4 without moving anything.
 */
@Composable
fun DataFolderStep(debugOnly: Boolean = false, onDone: () -> Unit) {
    val context = LocalContext.current
    val landing = LocalFocusLanding.current
    // null: the question; else the folder in use (screen 4).
    var using by remember { mutableStateOf<FolderName?>(null) }
    var failed by remember { mutableStateOf(false) }
    // The move runs on its own (code audit 3, M5), so leaving this page doesn't cut it off; its outcome comes back here.
    val move by DataFolderMove.state.collectAsState()
    val moving = move is DataFolderMove.State.Moving
    LaunchedEffect(move) {
        val done = (move as? DataFolderMove.State.Done)?.let { DataFolderMove.take() } ?: return@LaunchedEffect
        val o = done.outcome
        if (o is DataFolderMove.Outcome.Failed) failed = true else {
            failed = false
            using = done.tree?.let { t -> try { FolderNames.of(context, t.toUri()) } catch (e: Exception) { null } } ?: FolderName("Picked folder", emptyList())
        }
        JLog.i("Joey64Setup", "first run: data folder " + when (o) {
            is DataFolderMove.Outcome.Failed -> "not used"
            is DataFolderMove.Outcome.OldUnreadable -> "in use, nothing moved (the old place can't be read)"
            is DataFolderMove.Outcome.Moved -> "in use, ${o.result.conflicts.size + o.result.failed.size} files left behind, ${o.result.keptAside} saves kept aside"
        })
        landing?.arm()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) { landing?.arm(); return@rememberLauncherForActivityResult }
        if (debugOnly) {
            using = try { FolderNames.of(context, uri) } catch (e: Exception) { FolderName("Picked folder", emptyList()) }
            failed = false; landing?.arm(); return@rememberLauncherForActivityResult
        }
        DataFolderMove.start(context, uri.toString())
    }
    val choose = { picker.launch(null) }
    // Coming from the ROMs step, the button A pressed is gone: land on this step's first button
    // (as ImportScreen does), or nothing is highlighted and the first key only brings it back.
    LaunchedEffect(Unit) { landing?.arm() }

    SetupPage {
        val u = using
        when {
            moving -> SetupHeading("Moving your saves, save states and screenshots")
            failed -> {
                SetupHeading("Couldn't use that folder")
                SetupBody("Joey64 couldn't write there. Pick another folder, or keep them in Joey64 for now.")
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Choose another folder", primary = true, modifier = Modifier.initialFocus(), onClick = choose)
                    JoeyButton("Keep them in Joey64", primary = false, onClick = onDone)
                }
            }
            u == null -> {
                SetupHeading("Where should Joey64 keep saves, save states and screenshots?")
                SetupBody("Pick a folder you can reach from a file manager or a sync app, like a Joey64 folder on internal storage or your SD card.")
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Choose folder", primary = true, modifier = Modifier.initialFocus(), onClick = choose)
                    JoeyButton("Keep them in Joey64", primary = false, onClick = onDone)
                }
                SetupSmall("You can change this later in Settings › Saves.")
            }
            else -> {
                SetupHeading("Saves, save states and screenshots go here")
                FolderBox(u.place, u.folders, Modifier.padding(top = 16.dp), subs = "saves · states · screenshots")
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Continue", primary = true, modifier = Modifier.initialFocus(), onClick = onDone)
                    JoeyButton("Choose another folder", primary = false, onClick = choose)
                }
            }
        }
    }
}
