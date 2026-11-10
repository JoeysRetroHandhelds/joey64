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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    /** [toGame]: opened by the Achievements hotkey, so B goes back to the game. */
    data class Achievements(val focusId: Int? = null, val toGame: Boolean = false) : PauseScreen
    data class Cheats(val game: com.joeysretro.joey64.launcher.screens.CheatsGame, /** "Select + L3", or null with no hotkey for it. */ val gameSharkKeys: String? = null) : PauseScreen
    /** Game settings over the paused game (approved mockup 2026-11-07, screen 9) for [saveName]. B goes back to the menu. */
    data class GameSettings(val saveName: String) : PauseScreen
    /** Resuming after a row read only at start changed (screen 10): Restart now or Later. [subtitle] names the rows. */
    data class Restart(val subtitle: String) : PauseScreen
    /** RetroAchievements' sign-in, from a tap on the Sign-in expired pop-up; closes back to the game. */
    data object SignIn : PauseScreen
    /**
     * Controller disconnected (approved problem screens 2026-11-08, screen 4): [name]'s controller, [player]'s (0-3), went
     * and the game is paused until it comes back. [builtIn]: this device has built-in controls to play on instead.
     */
    data class Disconnected(val player: Int, val name: String, val builtIn: Boolean, /** No built-in controls: Use touch controls (player 1). */ val touch: Boolean = false) : PauseScreen
    /** Not enough space to save (screen 5): [text] says what wasn't saved and how much room there is. OK goes back to the game. */
    data class NoSpace(val text: String) : PauseScreen
    /**
     * Netplay (approved mockup 2026-11-10, screen 1): [player]'s pause menu has the game stopped for everyone. No buttons;
     * it goes by itself when they're back. B opens this player's own menu.
     */
    data class NetPaused(val player: Int, /** From the room's list, null before it arrives. */ val name: String? = null) : PauseScreen
    /** Netplay screen 5: the games no longer match. Keep playing, or Leave the room ([host]: End for everyone). */
    data class NetOutOfStep(val host: Boolean) : PauseScreen
    /** Netplay, the host: [player] left (player-left popup, approved 2026-11-05). The game is paused for everyone while it's up. */
    data class NetLeft(val player: Int, val name: String? = null) : PauseScreen
}

/** What the Controller disconnected popup's rows do. */
enum class DisconnectChoice { BuiltIn, Touch, WithoutThem, Quit }

enum class MenuRow { Resume, Save, Load, GameSettings, Controls, Cheats, Achievements }

/** One cell of the slot picker. [savedAt] null: nothing in the slot. [picture]: the PNG beside the state, if any. */
data class SlotView(val slot: Int, val name: String, val savedAt: Long?, val picture: ImageBitmap?)

/** What the pause menu and the slot picker do. */
interface PauseActions {
    fun resume()
    fun openSave()
    fun openLoad()
    fun openGameSettings()
    /** Back from Game settings to the menu; what can change in game does now, the rest on Restart now or the next start. */
    fun closeGameSettings()
    /** The in-game Picture panel changed Shader, Sides or Overlay: the paused picture is drawn again with it. */
    fun pictureChanged() {}
    /** The menu's Game settings value: "3 changed" or "Same as All games". */
    val gameSettingsLabel: String get() = ""
    fun restartNow()
    fun restartLater()
    fun openControls()
    /** Back from Controls to the menu; what changed there takes effect when the game resumes. */
    fun closeControls()
    fun openCheats()
    /** Back from Cheats to the menu; the cheats on now are what the game runs. */
    fun closeCheats()
    fun openAchievements()
    fun closeAchievements()
    /** The sign-in popup closed (signed in, or B): back to the game. */
    fun closeSignIn()
    /** A row of the Controller disconnected popup. */
    fun disconnectChoice(choice: DisconnectChoice) {}
    /** Not enough space to save: OK or B, back to the game. */
    fun closeNoSpace() {}
    /** In a netplay room: the menu greys Save state, Load state, Cheats and Reset, and Quit is Leave the room. */
    val inRoom: Boolean get() = false
    /** In a netplay room: the menu's line under its title (screen 2), "Players 1 and 3 are waiting for you". */
    val netLine: String? get() = null
    /** Netplay screen 1's B: this player's own pause menu. */
    fun netMenu() {}
    /** Netplay: Keep playing (out of step) or Carry on without them (player left). */
    fun netCarryOn() {}
    /** Netplay: Leave the room, or the host's End for everyone. */
    fun netLeave() {}
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
 *  - **Slot picker** (approved mockup): Auto then Slots 1-10 (then Auto 2 ..., Settings › Saves), four to a row as 100 x 75 dp pictures,
 *    the selected slot's name and date at the right of the title. Saving can't go to Auto (dimmed:
 *    quitting writes it). A on an empty slot when loading does nothing. B goes back to the menu.
 */
@Composable
fun PausePopup(screen: PauseScreen, title: String, saveSlotName: String, now: Long, actions: PauseActions) {
    if (screen is PauseScreen.GameSettings) {
        com.joeysretro.joey64.launcher.screens.InGameGameSettings(com.joeysretro.joey64.launcher.gamesettings.GameRef(screen.saveName, title), onPictureChanged = { actions.pictureChanged() }) { actions.closeGameSettings() }
        return
    }
    if (screen is PauseScreen.Controls) {
        com.joeysretro.joey64.launcher.screens.InGameControls(screen.saveName, title) { actions.closeControls() }
        return
    }
    if (screen is PauseScreen.Cheats) {
        com.joeysretro.joey64.launcher.screens.InGameCheats(screen.game,
            com.joeysretro.joey64.launcher.screens.GameSharkRow(screen.gameSharkKeys) { actions.pressGameShark() }) { actions.closeCheats() }
        return
    }
    if (screen is PauseScreen.SignIn) {
        com.joeysretro.joey64.launcher.screens.InGameSignIn { actions.closeSignIn() }
        return
    }
    if (screen is PauseScreen.Achievements) {
        com.joeysretro.joey64.launcher.screens.InGameAchievements(title, screen.focusId,
            backLabel = if (screen.toGame) "Back to the game" else "Back to pause menu") { actions.closeAchievements() }
        return
    }
    if (screen is PauseScreen.Disconnected) { DisconnectedPopup(screen, actions); return }
    if (screen is PauseScreen.NetPaused || screen is PauseScreen.NetOutOfStep || screen is PauseScreen.NetLeft) { NetplayPopup(screen, actions); return }
    if (screen is PauseScreen.NoSpace) {
        JoeyDialog(onDismiss = { actions.closeNoSpace() }, hints = listOf(Hint("A", "OK"), Hint("B", "Back to game")), width = 470.dp) {
            Column {
                PopupTitle(com.joeysretro.joey64.saves.NoSpace.TITLE, screen.text)
                JoeyOption("OK", Modifier.initialFocus()) { actions.closeNoSpace() }
            }
        }
        return
    }
    val picker = screen as? PauseScreen.Picker
    val restart = screen as? PauseScreen.Restart
    val hints = when {
        restart != null -> listOf(Hint("A", "Select"), Hint("B", "Later"))
        picker == null -> listOf(Hint("A", "Select"), Hint("B", "Resume"))
        else -> listOf(Hint("A", if (picker.save) "Save" else "Load"), Hint("D-pad", "Move"), Hint("B", "Back"))
    }
    JoeyDialog(
        onDismiss = { if (restart != null) actions.restartLater() else actions.resume() },
        hints = hints,
        interceptBack = { if (picker != null) { actions.backToMenu(if (picker.save) MenuRow.Save else MenuRow.Load); true } else false },
        // Five slot pictures to a row once older quit saves make it longer than three rows of four.
        width = when { restart != null -> 470.dp; picker == null -> 300.dp; picker.slots.size > 12 -> 564.dp; else -> 454.dp },
    ) {
        val landing = LocalFocusLanding.current
        LaunchedEffect(screen) { landing?.arm() }
        when (screen) {
            is PauseScreen.Menu -> Column {
                PopupTitle(title)
                // Netplay screen 2: only the line is new; the menu is today's.
                val netLine = androidx.compose.runtime.remember(screen) { actions.netLine }
                if (netLine != null) Text(netLine, style = JoeyTheme.type.note, color = JoeyTheme.colors.gold,
                    modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 6.dp))
                val room = androidx.compose.runtime.remember(screen) { actions.inRoom }
                // The rows scroll under the title: on a short screen (the Thor at 1080p) the card ran out of
                // room and squeezed the last row, Quit, to a thin line with no text. Focus brings a row into view.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                JoeyOption("Resume", Modifier.initialFocus(screen.from == MenuRow.Resume)) { actions.resume() }
                // Netplay screen 6: greyed in a room, with the pop-up's words.
                if (room) {
                    NetBlockedRow("Save state")
                    NetBlockedRow("Load state")
                } else {
                JoeyOption("Save state", Modifier.initialFocus(screen.from == MenuRow.Save), value = "$saveSlotName  ▸") { actions.openSave() }
                JoeyOption("Load state", Modifier.initialFocus(screen.from == MenuRow.Load), value = "›") { actions.openLoad() }
                }
                PopupDivider()
                JoeyOption("Game settings", Modifier.initialFocus(screen.from == MenuRow.GameSettings),
                    value = androidx.compose.runtime.remember(screen) { actions.gameSettingsLabel } + "  ›") { actions.openGameSettings() }
                JoeyOption("Controls", Modifier.initialFocus(screen.from == MenuRow.Controls), value = "›") { actions.openControls() }
                if (room) NetBlockedRow("Cheats")
                else JoeyOption("Cheats", Modifier.initialFocus(screen.from == MenuRow.Cheats), value = "›") { actions.openCheats() }
                // Only when RetroAchievements knows the game (signed in, and it has a set).
                val hasSet = androidx.compose.runtime.remember(screen) { com.joeysretro.joey64.achievements.RaBridge.gameInfo() != "null" ||
                    com.joeysretro.joey64.achievements.RaBridge.debugCache != null }
                if (hasSet) JoeyOption("Achievements", Modifier.initialFocus(screen.from == MenuRow.Achievements), value = "›") { actions.openAchievements() }
                PopupDivider()
                if (room) NetBlockedRow("Reset") else JoeyOption("Reset") { actions.reset() }
                if (room) JoeyOption(com.joeysretro.joey64.netplay.NetplayWords.LEAVE) { actions.netLeave() }
                else JoeyOption(actions.quitLabel) { actions.quit() }
                }
            }
            is PauseScreen.Picker -> SlotGrid(screen, now) { actions.pick(screen.save, it) }
            is PauseScreen.Restart -> Column {
                PopupTitle("Restart to use the new settings?", screen.subtitle)
                com.joeysretro.joey64.launcher.components.JoeyRow("Restart now", Modifier.initialFocus(),
                    detail = "Saves to the auto slot, then starts again from there") { actions.restartNow() }
                com.joeysretro.joey64.launcher.components.JoeyRow("Later", detail = "Keep playing as it is") { actions.restartLater() }
            }
            is PauseScreen.Controls, is PauseScreen.Cheats, is PauseScreen.Achievements, is PauseScreen.GameSettings, PauseScreen.SignIn,
            is PauseScreen.Disconnected, is PauseScreen.NoSpace, is PauseScreen.NetPaused, is PauseScreen.NetOutOfStep, is PauseScreen.NetLeft -> {}
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
            // Older quit saves (Settings › Saves) make it longer than three rows of four: five to a row then.
            for (row in picker.slots.chunked(if (picker.slots.size > 12) 5 else 4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (s in row) {
                        val off = picker.save && com.joeysretro.joey64.states.StateSlots.isAuto(s.slot)
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

/**
 * Controller disconnected (approved problem screens, scratchpad build_problems2.py, "Approve as drawn" 2026-11-08): the
 * game is paused and carries on by itself when the controller comes back (MainActivity closes this then). Use built-in
 * controls when the device has them, else Use touch controls for player 1 (the touch controls show for now), Carry on without them for
 * players 2-4, Quit to library. B does nothing: the game waits.
 */
@Composable
private fun DisconnectedPopup(screen: PauseScreen.Disconnected, actions: PauseActions) {
    JoeyDialog(onDismiss = {}, hints = listOf(Hint("A", "Select")), width = 470.dp) {
        Column {
            val text = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = JoeyTheme.colors.text))
                append(screen.name); pop()
                append(" (Player ${screen.player + 1}) disconnected, so the game is paused. It carries on by itself when the controller comes back.")
            }
            PopupTitle("Controller disconnected")
            com.joeysretro.joey64.launcher.components.PromptText(text)
            var first = true
            fun firstFocus(): Modifier = if (first) { first = false; Modifier.initialFocus() } else Modifier
            if (screen.builtIn) JoeyOption("Use built-in controls", firstFocus()) { actions.disconnectChoice(DisconnectChoice.BuiltIn) }
            else if (screen.touch) JoeyOption("Use touch controls", firstFocus()) { actions.disconnectChoice(DisconnectChoice.Touch) }
            if (screen.player > 0) JoeyOption("Carry on without them", firstFocus()) { actions.disconnectChoice(DisconnectChoice.WithoutThem) }
            JoeyOption(actions.quitLabel, firstFocus()) { actions.disconnectChoice(DisconnectChoice.Quit) }
        }
    }
}

/** A pause menu row that does nothing in a netplay room (approved netplay mockup 2026-11-10, screen 6): greyed, with the pop-up's words. */
@Composable
private fun NetBlockedRow(label: String) =
    com.joeysretro.joey64.launcher.components.JoeyRow(label, enabled = false, detail = com.joeysretro.joey64.netplay.NetplayWords.BLOCKED) {}

/**
 * The in-game netplay popups (approved mockup 2026-11-10, scratchpad build_netplay3.py, "Approve as drawn"):
 *  - **Player N paused the game** (screen 1), on every other screen while that player's menu is open: no rows, B opens this
 *    player's own menu; MainActivity takes it away when they're back.
 *  - **The games are out of step** (screen 5), over the paused game: Keep playing, Leave the room (the host: End for everyone).
 *  - **Player N left the game** (the host; the player-left popup approved 2026-11-05): Carry on without them, Leave the room.
 *    Its Wait row waits for rejoin (after the first testing build, Joey 2026-11-10).
 */
@Composable
private fun NetplayPopup(screen: PauseScreen, actions: PauseActions) {
    val w = com.joeysretro.joey64.netplay.NetplayWords
    when (screen) {
        is PauseScreen.NetPaused -> JoeyDialog(onDismiss = { actions.netMenu() }, hints = listOf(Hint("B", "Menu")), width = 470.dp) {
            Column {
                PopupTitle(w.pausedTitle(screen.player))
                com.joeysretro.joey64.launcher.components.PromptText(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = JoeyTheme.colors.text)) { append(screen.name ?: "They") }
                    append(w.PAUSED_TEXT)
                })
                // Something has to hold focus so B reaches the dialog; nothing to press.
                Box(Modifier.size(1.dp).initialFocus().pressable(rememberFocusState().first, onClick = {}))
            }
        }
        is PauseScreen.NetOutOfStep -> JoeyDialog(onDismiss = {}, hints = listOf(Hint("A", "Select")), width = 470.dp) {
            Column {
                PopupTitle(w.OUT_OF_STEP_TITLE)
                com.joeysretro.joey64.launcher.components.PromptText(androidx.compose.ui.text.AnnotatedString(w.OUT_OF_STEP_TEXT))
                JoeyOption("Keep playing", Modifier.initialFocus()) { actions.netCarryOn() }
                JoeyOption(if (screen.host) w.END_FOR_EVERYONE else w.LEAVE) { actions.netLeave() }
            }
        }
        is PauseScreen.NetLeft -> JoeyDialog(onDismiss = {}, hints = listOf(Hint("A", "Select")), width = 470.dp) {
            Column {
                PopupTitle(w.leftTitle(screen.player, screen.name))
                com.joeysretro.joey64.launcher.components.PromptText(androidx.compose.ui.text.AnnotatedString(w.leftText(screen.player)))
                JoeyOption("Carry on without them", Modifier.initialFocus()) { actions.netCarryOn() }
                JoeyOption(w.LEAVE) { actions.netLeave() }
            }
        }
        else -> {}
    }
}
