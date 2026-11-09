package com.joeysretro.joey64.launcher.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.InfoChip
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PageHeader
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.TwoColumns
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.data.LibraryGame
import com.joeysretro.joey64.launcher.ingame.SlotDates
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.saves.SaveManager
import com.joeysretro.joey64.states.StateSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The game whose Saves or Screenshots page is open, with its save name (the files are named after it). */
data class SavesGame(val game: LibraryGame, val saveName: String)

/** What the Saves page shows, read off the main thread in one go. */
private class SavesData(
    val saves: List<SaveManager.GameSave>,
    val states: List<SaveManager.State>,
    val pictures: Map<Int, ImageBitmap>,
    val backups: List<SaveManager.Backup>,
)

private sealed interface SvPopup {
    data object Restore : SvPopup
    data object DeleteSave : SvPopup
    data class StateOptions(val state: SaveManager.State) : SvPopup
    data class DeleteState(val state: SaveManager.State) : SvPopup
}

/** "Mario Tennis" for "Mario Tennis (USA)". */
private fun shortName(t: String) = t.substringBefore(" (").ifBlank { t }

/**
 * Saves, one page per game, opened from the library (a game's options › Saves; not from the pause menu, Joey 2026-11-08).
 * Approved mockups 2026-11-08: build_savemgr.py, then build_savemgr2.py ("Approve as drawn"), which folds the in-game save
 * into its actions, as it's one file. Left: In-game save (Back up now, Restore a backup with the count, Delete save; grey
 * when there's nothing to act on), then the states that are there (Auto and the older quit saves, Recovery when it's this
 * game's, the slots) with the empty slots in one line. Right: the save's size, date and last backup, or the focused state's
 * picture, size and date. A on a state: Play from here, Delete (asks). States have no Rename (the drawing has none).
 */
@Composable
fun SavesScreen(g: SavesGame, onHints: (List<Hint>) -> Unit, onPlay: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    val manager = remember(g.saveName) { SaveManager(context.applicationContext, g.saveName) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var popup by remember { mutableStateOf<SvPopup?>(null) }
    var focused by remember { mutableStateOf("bu") }
    val landing = LocalFocusLanding.current
    // Back from a game played from here: its states and save changed.
    LifecycleResumeEffect(Unit) { version++; onPauseOrDispose { } }
    val data by produceState<SavesData?>(null, version) {
        value = withContext(Dispatchers.IO) {
            val states = manager.states()
            SavesData(manager.gameSaves(), states,
                states.mapNotNull { st -> st.picture?.let { p -> BitmapFactory.decodeByteArray(p, 0, p.size)?.asImageBitmap()?.let { st.slot to it } } }.toMap(),
                manager.backups())
        }
    }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { onHints(listOf(Hint("A", "Select"), Hint("B", "Back"))) }
    // After each read (a delete or restore changes the rows): the focused row may be gone, so land on the page again, not the rail.
    LaunchedEffect(data) { if (data != null) landing?.arm() }
    fun changed() { popup = null; version++ }
    fun work(job: () -> Unit) { scope.launch { withContext(Dispatchers.IO) { job() }; changed() } }

    val now = System.currentTimeMillis()
    val d = data
    val name = shortName(g.game.title)
    Column(Modifier.fillMaxSize().padding(horizontal = s.margin).padding(top = 14.dp, bottom = 8.dp)) {
        PageHeader("Saves") { InfoChip(g.game.title, R.drawable.ic_device_floppy) }
        Spacer(Modifier.height(12.dp))
        if (d == null) return@Column
        val hasSave = d.saves.isNotEmpty()
        val hasBackups = d.backups.isNotEmpty()
        // A deleted state's row went: focus goes back to the first row.
        val keys = listOf("bu", "rs", "del") + d.states.map { "s:${it.slot}" }
        val at = if (focused in keys) focused else keys.first()
        TwoColumns(Modifier.weight(1f), left = {
            SectionLabel("In-game save")
            JoeyRow("Back up now", Modifier.initialFocus(at == "bu"), enabled = hasSave,
                onFocused = { if (it) focused = "bu" }) { if (hasSave) work { manager.backUpNow() } }
            JoeyRow("Restore a backup", Modifier.initialFocus(at == "rs"), value = if (hasBackups) "${d.backups.size}  ›" else null, enabled = hasBackups,
                onFocused = { if (it) focused = "rs" }) { if (hasBackups) popup = SvPopup.Restore }
            JoeyRow("Delete save", Modifier.initialFocus(at == "del"), enabled = hasSave,
                onFocused = { if (it) focused = "del" }) { if (hasSave) popup = SvPopup.DeleteSave }
            SectionLabel("States")
            for (st in d.states) {
                JoeyRow(SaveManager.stateLabel(st.slot), Modifier.initialFocus(at == "s:${st.slot}"), value = SlotDates.withTime(st.savedAt, now),
                    onFocused = { if (it) focused = "s:${st.slot}" }) { popup = SvPopup.StateOptions(st) }
            }
            SaveManager.emptySlots(d.states.map { it.slot }.toSet())?.let {
                Text(it, style = JoeyTheme.type.row, color = c.textFaint, modifier = Modifier.padding(horizontal = s.rowPadH, vertical = 10.dp))
            }
        }) {
            SectionLabel(" ")
            val state = d.states.firstOrNull { "s:${it.slot}" == at }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(10.dp)) {
                val picture = state?.let { d.pictures[it.slot] }
                Box(Modifier.fillMaxWidth(0.78f).aspectRatio(4f / 3f).clip(RoundedCornerShape(6.dp)).background(c.chip), contentAlignment = Alignment.Center) {
                    if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(painterResource(if (state != null) R.drawable.ic_camera else R.drawable.ic_device_floppy), contentDescription = null,
                        tint = c.textFaint, modifier = Modifier.size(54.dp))
                }
                Spacer(Modifier.height(8.dp))
                if (state != null) {
                    Text(SaveManager.stateLabel(state.slot), style = JoeyTheme.type.rowBold, color = c.text)
                    Text("${SaveManager.sizeLabel(state.size)} · ${SlotDates.withTime(state.savedAt, now)}", style = JoeyTheme.type.note, color = c.textSecondary)
                } else {
                    // The save the three rows act on (a 64DD disk game's disk save on a line of its own).
                    Text("In-game save", style = JoeyTheme.type.rowBold, color = c.text)
                    if (!hasSave) Text("None yet: $name hasn't saved", style = JoeyTheme.type.note, color = c.textSecondary)
                    for (sv in d.saves) {
                        Text((if (d.saves.size > 1) "${sv.label}: " else "") + "${SaveManager.sizeLabel(sv.size)} · ${SlotDates.withTime(sv.modified, now)}",
                            style = JoeyTheme.type.note, color = c.textSecondary)
                    }
                    d.backups.firstOrNull()?.let {
                        Text("Last backup: ${SlotDates.withTime(it.at, now)}", style = JoeyTheme.type.note, color = c.textSecondary)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val n = d.backups.size
        val (title, text) = when (at) {
            "bu" -> "Back up now" to if (hasSave) "A copy of $name's save in the backups folder." else "$name has no save to back up yet."
            "rs" -> "Restore a backup" to when {
                !hasBackups -> "$name has no backups yet."
                !hasSave -> "$name has no save now. A puts one of its $n backups back."
                else -> "Puts one of its $n backups back. Today's save is backed up first."
            }
            "del" -> "Delete save" to if (hasSave) "Deletes $name's save. A backup is kept first. States aren't touched." else "$name has no save to delete."
            else -> {
                val slot = at.removePrefix("s:").toInt()
                SaveManager.stateLabel(slot) to when {
                    slot == SaveManager.RECOVERY -> "Saved while playing, in case Joey64 closed. A plays from here or deletes it."
                    StateSlots.isAuto(slot) -> "Saved when the game was quit. A plays from here or deletes it."
                    else -> "A state: the game exactly as it was. A plays from here or deletes it."
                }
            }
        }
        DescriptionLine(title, text)
    }

    val hints = listOf(Hint("A", "Select"), Hint("B", "Close"))
    when (val p = popup) {
        null -> {}
        SvPopup.Restore -> {
            val list = d?.backups.orEmpty()
            val both = list.map { it.of }.distinct().size > 1
            JoeyDialog(onDismiss = { popup = null }, hints = listOf(Hint("A", "Restore"), Hint("B", "Back")), width = 470.dp) {
                Column {
                    PopupTitle("Restore a backup", "Today's save is backed up first.")
                    list.forEachIndexed { i, b ->
                        JoeyRow(SlotDates.withTime(b.at, now), if (i == 0) Modifier.initialFocus() else Modifier, value = SaveManager.sizeLabel(b.size),
                            detail = if (both) SaveManager.GameSave(b.of, 0, 0).label else null) { work { manager.restore(b) } }
                    }
                }
            }
        }
        SvPopup.DeleteSave -> JoeyDialog(onDismiss = { popup = null }, hints = hints, width = 470.dp) {
            Column {
                PopupTitle("Delete $name's save?",
                    "Your in-game progress goes. States aren't touched. A backup is kept first, so Restore a backup can bring it back.")
                JoeyOption("Keep it", Modifier.initialFocus()) { popup = null }
                JoeyOption("Delete") { work { d?.saves.orEmpty().forEach { manager.delete(it) } } }
            }
        }
        is SvPopup.StateOptions -> JoeyDialog(onDismiss = { popup = null }, hints = hints, width = 330.dp) {
            Column {
                PopupTitle(SaveManager.stateLabel(p.state.slot))
                JoeyRow("Play from here", Modifier.initialFocus(), detail = "Starts the game from this state") {
                    popup = null
                    onPlay(when (p.state.slot) {
                        SaveManager.RECOVERY -> com.joeysretro.joey64.MainActivity.STATE_RECOVERY
                        else -> com.joeysretro.joey64.MainActivity.STATE_SLOT + p.state.slot
                    })
                }
                PopupDivider()
                JoeyOption("Delete") { popup = SvPopup.DeleteState(p.state) }
            }
        }
        is SvPopup.DeleteState -> JoeyDialog(onDismiss = { popup = null }, hints = hints, width = 470.dp) {
            Column {
                PopupTitle("Delete ${SaveManager.stateLabel(p.state.slot)}?", "The state and its picture go. This can't be undone.")
                JoeyOption("Keep it", Modifier.initialFocus()) { popup = null }
                JoeyOption("Delete") { work { manager.deleteState(p.state.slot) } }
            }
        }
    }
}
