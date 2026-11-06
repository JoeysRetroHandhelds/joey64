package com.joeysretro.joey64.launcher.screens

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.ImportModel
import com.joeysretro.joey64.launcher.ImportStep
import com.joeysretro.joey64.launcher.components.ButtonRow
import com.joeysretro.joey64.launcher.components.FolderBox
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyButton
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.ProgressLine
import com.joeysretro.joey64.launcher.components.SetupBody
import com.joeysretro.joey64.launcher.components.SetupButtonsTop
import com.joeysretro.joey64.launcher.components.SetupHeading
import com.joeysretro.joey64.launcher.components.SetupPage
import com.joeysretro.joey64.launcher.components.SetupSmall
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.rowTextColor
import com.joeysretro.joey64.launcher.library.ImportText
import com.joeysretro.joey64.launcher.library.RaClash
import com.joeysretro.joey64.launcher.library.RaFile
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import java.time.ZoneId

/**
 * Import from RetroArch (first-run screens 5-7 and the clash popups, approved 2026-11-05):
 *  5. "Copy your saves from RetroArch?": Choose RetroArch folder (the picker opens at RetroArch's
 *     folder) or Skip.
 *  6. "Found N saves and N states" in that folder: Copy to Joey64, or Choose another folder.
 *  7. "Copied N saves and N states", Continue.
 * A file Joey64 already has under the same name, with different contents, is asked about one at a
 * time before anything is copied (Keep Joey64's or Use RetroArch's, with dates, and "Do the same
 * for the other N"); Back in that popup cancels the copy, nothing written. [onDone]: Skip or Continue.
 *
 * Not shown yet: the first screen's "You can do this later in Settings › Library." (no Settings yet).
 */
@Composable
fun ImportScreen(model: ImportModel, dataFolder: String?, onDone: () -> Unit) {
    val step by model.step.collectAsStateWithLifecycle()
    val landing = LocalFocusLanding.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) landing?.arm() else model.look(uri, dataFolder)
    }
    val choose = { picker.launch(model.pickerUri()) }
    BackHandler(enabled = step is ImportStep.Found) { model.back() }

    // The clash being asked about, and the answers so far; null while no popup is up.
    var asking by remember(step) { mutableStateOf<Asking?>(null) }
    // A saves or states folder picked: "Use this folder" goes on to what it holds.
    var useAnyway by remember(step) { mutableStateOf(false) }
    // Focus lands on each new screen's first button, also the Found screen after the warning.
    LaunchedEffect(step, useAnyway) { landing?.arm() }

    SetupPage {
        when (val s = step) {
            ImportStep.Offer -> {
                SetupHeading("Copy your saves from RetroArch?")
                SetupBody("Joey64 can copy your N64 saves and save states from RetroArch, so your games carry on where you left off. RetroArch's own files aren't changed.")
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Choose RetroArch folder", primary = true, modifier = Modifier.initialFocus(), onClick = choose)
                    JoeyButton("Skip", primary = false, onClick = onDone)
                }
            }
            ImportStep.Looking -> SetupHeading("Looking for saves and states")
            is ImportStep.Found -> if (s.onlyKind != null && !useAnyway) {
                // Picked RetroArch's saves or states folder, likely by mistake: the RetroArch folder has both.
                val mine = if (s.onlyKind == DataKind.Saves) "saves" else "states"
                val other = if (s.onlyKind == DataKind.Saves) "states" else "saves"
                SetupHeading(if (s.folders.lastOrNull()?.lowercase() == mine) "This is RetroArch's $mine folder" else "This folder is inside RetroArch's $mine folder")
                SetupBody("Choose the RetroArch folder instead, the one that holds both saves and states, so your $other are copied too.")
                FolderBox(s.place, s.folders, Modifier.padding(top = 16.dp))
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Choose RetroArch folder", primary = true, modifier = Modifier.initialFocus(), onClick = choose)
                    JoeyButton("Use this folder", primary = false) { useAnyway = true }
                }
            } else if (s.total == 0) {
                SetupHeading("No N64 saves or states found")
                FolderBox(s.place, s.folders, Modifier.padding(top = 16.dp), subs = s.subs)
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Choose another folder", primary = true, modifier = Modifier.initialFocus(), onClick = choose)
                    JoeyButton("Skip", primary = false, onClick = onDone)
                }
            } else {
                SetupHeading("Found " + ImportText.counts(s.found.saves.size, s.found.states.size))
                FolderBox(s.place, s.folders, Modifier.padding(top = 16.dp), subs = s.subs)
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Copy to Joey64", primary = true, modifier = Modifier.initialFocus()) {
                        if (s.plan.clashes.isEmpty()) model.copy(s, emptySet(), dataFolder) else asking = Asking(0, emptySet())
                    }
                    JoeyButton("Choose another folder", primary = false, onClick = choose)
                }
                SetupSmall("Copies only. RetroArch keeps its own files.")
            }
            is ImportStep.Copying -> {
                SetupHeading("Copying " + ImportText.counts(s.saves, s.states))
                ProgressLine(s.progress, Modifier.padding(top = 20.dp))
            }
            is ImportStep.Done -> {
                val total = s.saves + s.states
                SetupHeading(if (total == 0) "Nothing copied" else "Copied " + ImportText.counts(s.saves, s.states))
                SetupBody(when {
                    s.failed > 0 -> "${if (s.failed == 1) "1 file" else "${s.failed} files"} couldn't be copied. RetroArch still has them."
                    total == 0 -> "Joey64 kept its own saves and states."
                    else -> "Your games continue from them in Joey64."
                })
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Continue", primary = true, modifier = Modifier.initialFocus(), onClick = onDone)
                }
            }
        }
    }

    val found = step as? ImportStep.Found
    val a = asking
    if (found != null && a != null) {
        val clashes = found.plan.clashes
        // A new popup for each clash, so focus starts on its first option.
        key(a.index) {
            ClashPopup(clashes[a.index], a.index, clashes.size, onCancel = { asking = null }) { useTheirs, forRest ->
                val picked = clashes.drop(a.index).let { if (forRest) it else it.take(1) }.map { it.file }
                val answers = if (useTheirs) a.useTheirs + picked else a.useTheirs
                val next = a.index + picked.size
                if (next >= clashes.size) {
                    asking = null
                    model.copy(found, answers, dataFolder)
                } else asking = Asking(next, answers)
            }
        }
    }
}

private data class Asking(val index: Int, val useTheirs: Set<RaFile>)

/** "Super Mario 64 (USA) already has a save in Joey64": keep which, one clash at a time. */
@Composable
private fun ClashPopup(clash: RaClash, index: Int, total: Int, onCancel: () -> Unit, onAnswer: (useTheirs: Boolean, forRest: Boolean) -> Unit) {
    val context = LocalContext.current
    val kind = clash.file.kind
    val others = total - index - 1
    var forRest by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val h24 = DateFormat.is24HourFormat(context)
    val theirsNewer = clash.file.modified > clash.ours.modified
    val oursNewer = clash.ours.modified > clash.file.modified
    JoeyDialog(onDismiss = onCancel, hints = listOf(Hint("A", "Select"), Hint("B", "Back")), width = 440.dp) {
        Column {
            PopupTitle(ImportText.clashTitle(kind, clash.file.name), ImportText.clashQuestion(kind, index + 1, total))
            JoeyOption(ImportText.keepOurs(kind), modifier = Modifier.initialFocus(),
                value = ImportText.date(clash.ours.modified, now, zone, h24, oursNewer)) { onAnswer(false, forRest) }
            JoeyOption(ImportText.useTheirs(kind),
                value = ImportText.date(clash.file.modified, now, zone, h24, theirsNewer)) { onAnswer(true, forRest) }
            if (others > 0) {
                PopupDivider()
                JoeyRow(ImportText.doTheSame(others), leading = { focused ->
                    Icon(painterResource(if (forRest) R.drawable.ic_check_on else R.drawable.ic_check_off), contentDescription = null,
                        tint = rowTextColor(JoeyTheme.colors, focused, JoeyTheme.colors.text), modifier = Modifier.size(18.dp))
                }) { forRest = !forRest }
            }
        }
    }
}
