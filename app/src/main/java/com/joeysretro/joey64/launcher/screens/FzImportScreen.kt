package com.joeysretro.joey64.launcher.screens

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.FzImportModel
import com.joeysretro.joey64.launcher.FzStep
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
import com.joeysretro.joey64.launcher.library.FzClash
import com.joeysretro.joey64.launcher.library.FzGame
import com.joeysretro.joey64.launcher.library.FzItem
import com.joeysretro.joey64.launcher.library.FzPlan
import com.joeysretro.joey64.launcher.library.ImportText
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import java.time.ZoneId

/**
 * Import from M64Plus FZ (approved mockup 2026-11-13, scratchpad build_fzimport.py / fzimport_all.png, "Approve as
 * drawn"): 2. where FZ keeps its data and Choose FZ folder; 3. Found, by game; 4. the clash popup with M64Plus FZ's;
 * 5. Done; 6. Nothing found. The mockup's Choose exported file isn't here: FZ's Export Game Data writes a folder
 * (its CopyToSdService copies GameData into the folder picked), so Choose FZ folder covers it.
 *
 * [firstRun]: Done says Next (on to RetroAchievements) and offers [onRetroArch] (Also from RetroArch); [onBack]: B or
 * Back on page 2. [onDone]: Next, or Done from Settings.
 */
@Composable
fun FzImportScreen(model: FzImportModel, dataFolder: String?, firstRun: Boolean, onBack: () -> Unit, onRetroArch: (() -> Unit)? = null,
                   debugOnly: Boolean = false, onDone: () -> Unit) {
    val step by model.step.collectAsStateWithLifecycle()
    val landing = LocalFocusLanding.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) landing?.arm() else model.look(uri, dataFolder)
    }
    val choose = { if (debugOnly) onDone() else picker.launch(model.pickerUri()) }
    BackHandler(enabled = step is FzStep.Offer || step is FzStep.Found) { if (step is FzStep.Offer) onBack() else model.back() }

    var asking by remember(step) { mutableStateOf<FzAsking?>(null) }
    LaunchedEffect(step) { landing?.arm() }

    SetupPage {
        when (val s = step) {
            FzStep.Offer -> {
                SetupHeading("Copy your saves from M64Plus FZ")
                SetupBody(WHERE)
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Choose FZ folder", primary = true, modifier = Modifier.initialFocus(), onClick = choose)
                    JoeyButton("Back", primary = false, onClick = onBack)
                }
                SetupSmall(COPIES)
            }
            FzStep.Looking -> SetupHeading("Looking for saves and save states")
            is FzStep.Found -> if (s.plan.all.isEmpty()) {
                SetupHeading(if (s.plan.notInLibrary.isEmpty()) "No M64Plus FZ saves in that folder" else "None of these games are in your library")
                SetupBody(if (s.plan.notInLibrary.isEmpty())
                    "Pick the folder that has FZ's game folders in it, named like \"Banjo-Kazooie (U) B29599651A13F681C9923D69354BF4A3\"."
                    else "M64Plus FZ has saves for " + names(s.plan.notInLibrary) + ", and no game in your library is the same copy.")
                FolderBox(s.place, s.folders, Modifier.padding(top = 16.dp))
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Choose FZ folder", primary = true, modifier = Modifier.initialFocus(), onClick = choose)
                    JoeyButton("Back", primary = false) { model.back() }
                }
            } else {
                SetupHeading("Found " + ImportText.counts(s.plan.saves, s.plan.states))
                SetupBody("For ${games(s.plan)}, in M64Plus FZ's folder:")
                FolderBox(s.place, s.folders, Modifier.padding(top = 16.dp))
                GameLines(s.plan)
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton("Copy to Joey64", primary = true, modifier = Modifier.initialFocus()) {
                        if (s.plan.clashes.isEmpty()) model.copy(s, emptySet(), dataFolder) else asking = FzAsking(0, emptySet())
                    }
                    JoeyButton("Choose another folder", primary = false, onClick = choose)
                }
                SetupSmall(COPIES)
            }
            is FzStep.Copying -> {
                SetupHeading("Copying " + ImportText.counts(s.saves, s.states))
                ProgressLine(s.progress, Modifier.padding(top = 20.dp))
            }
            is FzStep.Done -> {
                val total = s.saves + s.states
                SetupHeading(if (total == 0) "Nothing copied" else "Copied from M64Plus FZ")
                SetupBody(buildString {
                    append(when {
                        total == 0 -> "Joey64 kept its own saves and save states."
                        else -> ImportText.counts(s.saves, s.states) + (if (total == 1) " is" else " are") + " in Joey64 now."
                    })
                    if (total > 0 && s.kept > 0) append(" ${s.kept} kept Joey64's own.")
                    if (s.failed > 0) append(" ${if (s.failed == 1) "1 file" else "${s.failed} files"} couldn't be copied. M64Plus FZ still has them.")
                })
                ButtonRow(Modifier.padding(top = SetupButtonsTop)) {
                    JoeyButton(if (firstRun) "Next" else "Done", primary = true, modifier = Modifier.initialFocus(), onClick = onDone)
                    if (onRetroArch != null) JoeyButton("Also from RetroArch", primary = false, onClick = onRetroArch)
                }
                if (firstRun) SetupSmall(LATER)
            }
        }
    }

    val found = step as? FzStep.Found
    val a = asking
    if (found != null && a != null) {
        val clashes = found.plan.clashes
        key(a.index) {
            FzClashPopup(clashes[a.index], a.index, clashes.size, onCancel = { asking = null }) { useFz, forRest ->
                val picked = clashes.drop(a.index).let { if (forRest) it else it.take(1) }.map { it.item }
                val answers = if (useFz) a.useFz + picked else a.useFz
                val next = a.index + picked.size
                if (next >= clashes.size) {
                    asking = null
                    model.copy(found, answers, dataFolder)
                } else asking = FzAsking(next, answers)
            }
        }
    }
}

private const val WHERE = "Pick the folder M64Plus FZ keeps its game data in: the one set in its Settings › Data › External game data " +
    "location. If FZ keeps it inside the app, export it first (Tools › Import/Export Data › Export Game Data) and pick the folder you exported to."
private const val COPIES = "Copies only. M64Plus FZ keeps its own files."
private const val LATER = "You can do this later in Settings › Saves."

private fun names(games: List<FzGame>) = games.map { it.folder.substringBeforeLast(' ') }.let { n ->
    if (n.size <= 3) n.joinToString(", ") else n.take(3).joinToString(", ") + " and ${n.size - 3} more"
}

private fun games(plan: FzPlan): String {
    val n = plan.all.map { it.game.md5 }.distinct().size
    return if (n == 1) "1 of your games" else "$n of your games"
}

/** One line a game: its name in the library (the save name) and what it brings; then the games left out. */
@Composable
private fun GameLines(plan: FzPlan) {
    val byGame = plan.all.groupBy { it.saveName }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    Column(Modifier.padding(top = 10.dp).widthIn(max = 470.dp).fillMaxWidth()) {
        val shown = byGame.entries.take(MAX_LINES)
        for ((game, items) in shown) {
            val saves = items.count { it.kind == DataKind.Saves }
            val states = items.count { it.kind == DataKind.States }
            val what = listOfNotNull(if (saves > 0) "save" else null, if (states == 1) "1 save state" else if (states > 1) "$states save states" else null).joinToString(", ")
            Text("$game · $what", style = JoeyTheme.type.small, color = JoeyTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (byGame.size > MAX_LINES) Text("and ${byGame.size - MAX_LINES} more games", style = JoeyTheme.type.small, color = JoeyTheme.colors.textDim)
        if (plan.notInLibrary.isNotEmpty()) Text("Not in your library, left out: " + names(plan.notInLibrary), style = JoeyTheme.type.small,
            color = JoeyTheme.colors.textDim, modifier = Modifier.padding(top = 2.dp))
    }
}

private const val MAX_LINES = 6

private data class FzAsking(val index: Int, val useFz: Set<FzItem>)

/** "Conker's Bad Fur Day (Europe) already has a save in Joey64": keep which, one clash at a time; focus on the newer. */
@Composable
private fun FzClashPopup(clash: FzClash, index: Int, total: Int, onCancel: () -> Unit, onAnswer: (useFz: Boolean, forRest: Boolean) -> Unit) {
    val context = LocalContext.current
    val kind = clash.item.kind
    val others = total - index - 1
    var forRest by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val h24 = DateFormat.is24HourFormat(context)
    val fzNewer = clash.item.modified > clash.ours.modified
    val oursNewer = clash.ours.modified > clash.item.modified
    val noun = if (kind == DataKind.States) "save state" else "save"
    JoeyDialog(onDismiss = onCancel, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 440.dp) {
        Column {
            PopupTitle(ImportText.clashTitle(kind, clash.item.name), ImportText.clashQuestion(kind, index + 1, total))
            JoeyOption(ImportText.keepOurs(kind), modifier = Modifier.initialFocus(!fzNewer),
                value = ImportText.date(clash.ours.modified, now, zone, h24, oursNewer)) { onAnswer(false, forRest) }
            JoeyOption("Use M64Plus FZ's $noun", modifier = Modifier.initialFocus(fzNewer),
                value = ImportText.date(clash.item.modified, now, zone, h24, fzNewer)) { onAnswer(true, forRest) }
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
