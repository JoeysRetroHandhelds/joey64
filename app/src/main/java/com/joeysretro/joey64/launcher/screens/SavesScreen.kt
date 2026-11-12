package com.joeysretro.joey64.launcher.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import com.joeysretro.joey64.JLog
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
    // Back up now's pop-up (approved mockup 2026-11-08, build_backupmsg.py): (made a new one, the time it names); 3 s like Profiles loaded.
    var backedUp by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    LaunchedEffect(backedUp) { if (backedUp != null) { kotlinx.coroutines.delay(3_000L); backedUp = null } }
    // An action that didn't work (code audit 3, M6 and p2 L7): the same pop-up in the error colour, with what stayed as it was.
    var problem by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(problem) { if (problem != null) { kotlinx.coroutines.delay(3_000L); problem = null } }
    // One action at a time (p2 L6: a second A while a restore or delete ran started another on the same save).
    var working by remember { mutableStateOf(false) }
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
    val name = shortName(g.game.title)
    /** [job] off the main thread; false or an exception (a picked folder's permission half gone) shows [failed] with [stays]. */
    fun work(failed: String, stays: String, job: () -> Boolean) {
        if (working) return
        working = true
        popup = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { try { job() } catch (e: Exception) { JLog.e("Joey64SavesPage", "$failed: $e"); false } }
            working = false
            if (!ok) problem = failed to "$name · $stays"
            changed()
        }
    }

    val now = System.currentTimeMillis()
    val d = data
    Box(Modifier.fillMaxSize()) {
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
                onFocused = { if (it) focused = "bu" }) {
                if (hasSave && !working) {
                    working = true
                    scope.launch {
                        val names = withContext(Dispatchers.IO) { try { manager.backUpNow() } catch (e: Exception) { JLog.e("Joey64SavesPage", "back up now: $e"); null } }
                        working = false
                        if (names == null) { problem = "Couldn't back up" to "$name · its save is as it was"; changed(); return@launch }
                        val made = names.isNotEmpty()
                        // Nothing new: the save matches its newest backup, whose time the pop-up names.
                        val at = if (made) System.currentTimeMillis() else withContext(Dispatchers.IO) { try { manager.backups().firstOrNull()?.at } catch (e: Exception) { null } } ?: System.currentTimeMillis()
                        backedUp = made to at
                        changed()
                    }
                }
            }
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
    backedUp?.let { (made, at) ->
        com.joeysretro.joey64.launcher.components.JoeyToast(R.drawable.ic_circle_check, "Saves", if (made) "Backed up" else "Already backed up",
            if (made) "$name · ${SlotDates.withTime(at, now)}" else "Same as the backup from ${SlotDates.withTime(at, now)}",
            Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp), iconTint = if (made) null else c.textDim)
    }
    problem?.let { (what, line) ->
        com.joeysretro.joey64.launcher.components.JoeyToast(R.drawable.ic_alert, "Saves", what, line,
            Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp), iconTint = c.error)
    }
    }

    val hints = listOf(Hint("A", "Select"), Hint("B", "Close"))
    when (val p = popup) {
        null -> {}
        SvPopup.Restore -> {
            val list = d?.backups.orEmpty()
            val both = list.map { it.of }.distinct().size > 1
            JoeyDialog(onDismiss = { popup = null }, hints = listOf(Hint("A", "Restore"), Hint("B", "Close")), width = 470.dp) {
                Column {
                    PopupTitle("Restore a backup", "Today's save is backed up first.")
                    list.forEachIndexed { i, b ->
                        JoeyRow(SlotDates.withTime(b.at, now), if (i == 0) Modifier.initialFocus() else Modifier, value = SaveManager.sizeLabel(b.size),
                            detail = if (both) SaveManager.GameSave(b.of, 0, 0).label else null) { work("Couldn't restore", "its save is as it was") { manager.restore(b) } }
                    }
                }
            }
        }
        SvPopup.DeleteSave -> JoeyDialog(onDismiss = { popup = null }, hints = hints, width = 470.dp) {
            Column {
                PopupTitle("Delete $name's save?",
                    "Your in-game progress goes. States aren't touched. A backup is kept first, so Restore a backup can bring it back.")
                JoeyOption("Keep it", Modifier.initialFocus()) { popup = null }
                JoeyOption("Delete") { work("Couldn't delete the save", "it's still there") { d?.saves.orEmpty().map { manager.delete(it) }.all { it } } }
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
                JoeyOption("Delete") { work("Couldn't delete the state", "it's still there") { manager.deleteState(p.state.slot) } }
            }
        }
    }
}
