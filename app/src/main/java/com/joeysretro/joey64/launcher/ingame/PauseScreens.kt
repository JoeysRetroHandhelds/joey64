package com.joeysretro.joey64.launcher.ingame

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.coverSelection
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.pressable
import com.joeysretro.joey64.launcher.components.rememberFocusState
import com.joeysretro.joey64.launcher.theme.DarkColors
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/** What the game screen shows over the paused game (plan, Phase 3). */
sealed interface PauseScreen {
    /** The menu; [from] is the row focus goes back to (the one that opened the picker). */
    data class Menu(val from: MenuRow = MenuRow.Resume) : PauseScreen
    /** The slot picker, for Save state ([save]) or Load state, over [slots] (Auto first, then Slots 1-10). */
    data class Picker(val save: Boolean, val slots: List<SlotView>, val focus: Int) : PauseScreen
    /** Controls over the paused game (Phase 4, mockup 11), set to the game [saveName]. B goes back to the menu. */
    data class Controls(val saveName: String) : PauseScreen
    /** Cheats over the paused game (Phase 6) for [game]. B goes back to the menu; the game takes the changes on resume. */
    /** The game's achievements over the paused game (Phase 7), on the row with [focusId] when a tap opened it. B goes back to the menu. */
    data class Achievements(val focusId: Int? = null) : PauseScreen
    data class Cheats(val game: com.joeysretro.joey64.launcher.screens.CheatsGame, /** "Select + L3", or null with no hotkey for it. */ val gameSharkKeys: String? = null) : PauseScreen
}

enum class MenuRow { Resume, Save, Load, Controls, Cheats, Achievements }

/** One cell of the slot picker. [savedAt] null: nothing in the slot. [picture]: the PNG beside the state, if any. */
data class SlotView(val slot: Int, val name: String, val savedAt: Long?, val picture: ImageBitmap?)

/** What the pause menu and the slot picker do. */
interface PauseActions {
    fun resume()
    fun openSave()
    fun openLoad()
    fun openControls()
    /** Back from Controls to the menu; what changed there takes effect when the game resumes. */
    fun closeControls()
    fun openCheats()
    /** Back from Cheats to the menu; the cheats on now are what the game runs. */
    fun closeCheats()
    fun openAchievements()
    fun closeAchievements()
    /** Cheats' "Press GameShark button": back to the game with the button held for a second. */
    fun pressGameShark()
    fun reset()
    fun quit()
    /** The Quit row's name, from Controls > Hotkeys > Quit game ("Quit to library" or "Quit and exit Joey64"). */
    val quitLabel: String get() = "Quit to library"
    /** Back from the picker to the menu. */
    fun backToMenu(from: MenuRow)
    fun pick(save: Boolean, slot: Int)
}

/**
 * The pause menu on Back and the slot picker it opens, in one popup window whose contents change
 * (the game's popups' lesson: a window closed for the next one let the undimmed screen flash through).
 *  - **Menu** (approved mockup, 2026-11-05): the game's name, Resume, Save state with the slot it
 *    saves to, Load state, then Reset and Quit to library. Game settings, Controls, Cheats and
 *    Achievements join it as they're built. B and Back resume.
 *  - **Slot picker** (approved mockup): Auto then Slots 1-10, four to a row as 100 x 75 dp pictures,
 *    the selected slot's name and date at the right of the title. Saving can't go to Auto (dimmed:
 *    quitting writes it). A on an empty slot when loading does nothing. B goes back to the menu.
 */
@Composable
fun PausePopup(screen: PauseScreen, title: String, saveSlotName: String, now: Long, actions: PauseActions) {
    if (screen is PauseScreen.Controls) {
        com.joeysretro.joey64.launcher.screens.InGameControls(screen.saveName, title) { actions.closeControls() }
        return
    }
    if (screen is PauseScreen.Cheats) {
        com.joeysretro.joey64.launcher.screens.InGameCheats(screen.game,
            com.joeysretro.joey64.launcher.screens.GameSharkRow(screen.gameSharkKeys) { actions.pressGameShark() }) { actions.closeCheats() }
        return
    }
    if (screen is PauseScreen.Achievements) {
        com.joeysretro.joey64.launcher.screens.InGameAchievements(title, screen.focusId) { actions.closeAchievements() }
        return
    }
    val picker = screen as? PauseScreen.Picker
    val hints = if (picker == null) listOf(Hint("A", "Select"), Hint("B", "Resume"))
        else listOf(Hint("A", if (picker.save) "Save" else "Load"), Hint("D-pad", "Move"), Hint("B", "Back"))
    JoeyDialog(
        onDismiss = { actions.resume() },
        hints = hints,
        interceptBack = { if (picker != null) { actions.backToMenu(if (picker.save) MenuRow.Save else MenuRow.Load); true } else false },
        width = if (picker == null) 300.dp else 454.dp,
    ) {
        val landing = LocalFocusLanding.current
        LaunchedEffect(screen) { landing?.arm() }
        when (screen) {
            is PauseScreen.Menu -> Column {
                PopupTitle(title)
                JoeyOption("Resume", Modifier.initialFocus(screen.from == MenuRow.Resume)) { actions.resume() }
                JoeyOption("Save state", Modifier.initialFocus(screen.from == MenuRow.Save), value = "$saveSlotName  ▸") { actions.openSave() }
                JoeyOption("Load state", Modifier.initialFocus(screen.from == MenuRow.Load), value = "›") { actions.openLoad() }
                PopupDivider()
                JoeyOption("Controls", Modifier.initialFocus(screen.from == MenuRow.Controls), value = "›") { actions.openControls() }
                JoeyOption("Cheats", Modifier.initialFocus(screen.from == MenuRow.Cheats), value = "›") { actions.openCheats() }
                // Only when RetroAchievements knows the game (signed in, and it has a set).
                val hasSet = androidx.compose.runtime.remember(screen) { com.joeysretro.joey64.achievements.RaBridge.gameInfo() != "null" ||
                    com.joeysretro.joey64.achievements.RaBridge.debugCache != null }
                if (hasSet) JoeyOption("Achievements", Modifier.initialFocus(screen.from == MenuRow.Achievements), value = "›") { actions.openAchievements() }
                PopupDivider()
                JoeyOption("Reset") { actions.reset() }
                JoeyOption(actions.quitLabel) { actions.quit() }
            }
            is PauseScreen.Picker -> SlotGrid(screen, now) { actions.pick(screen.save, it) }
            is PauseScreen.Controls, is PauseScreen.Cheats, is PauseScreen.Achievements -> {}
        }
    }
}

@Composable
private fun SlotGrid(picker: PauseScreen.Picker, now: Long, onPick: (Int) -> Unit) {
    val c = JoeyTheme.colors
    var focused by remember(picker) { mutableIntStateOf(picker.focus) }
    Column(Modifier.padding(horizontal = 2.dp)) {
        val sel = picker.slots.firstOrNull { it.slot == focused }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            PopupTitle(if (picker.save) "Save state" else "Load state")
            Spacer(Modifier.weight(1f))
            if (sel != null) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Bold)) { append(sel.name) }
                        append(" · " + (sel.savedAt?.let { SlotDates.label(it, now) } ?: "Empty"))
                    },
                    style = JoeyTheme.type.small, color = c.textDim,
                    modifier = Modifier.padding(end = 10.dp, bottom = 8.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)) {
            for (row in picker.slots.chunked(4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (s in row) {
                        val off = picker.save && s.slot == 0
                        SlotCell(s, off, now, initial = s.slot == picker.focus, onFocused = { focused = s.slot }) {
                            if (picker.save || s.savedAt != null) onPick(s.slot)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotCell(s: SlotView, off: Boolean, now: Long, initial: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(100.dp, 75.dp)
            .alpha(if (off) 0.35f else 1f)
            .coverSelection(focused, 6.dp)
            .clip(shape)
            .background(c.chip)
            .then(if (initial && !off) Modifier.initialFocus() else Modifier)
            .pressable(source, enabled = !off, onClick = onClick),
    ) {
        if (s.picture != null) {
            Image(s.picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // The name over the picture, on a dark fade (light text in both themes: it sits on the game's picture).
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, DarkColors.surface.copy(alpha = 0.92f))))
                    .padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
            ) {
                Text(s.name, style = JoeyTheme.type.thumbLabel, color = if (focused) c.accent else DarkColors.text)
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.name, style = JoeyTheme.type.thumbLabel, color = if (focused && c.isDark) c.accent else c.text)
                Spacer(Modifier.size(2.dp))
                // A state with no picture beside it (one RetroArch saved without): its date in place of "Empty".
                Text(s.savedAt?.let { SlotDates.label(it, now) } ?: "Empty", style = JoeyTheme.type.label, color = c.textDim)
            }
        }
    }
}
