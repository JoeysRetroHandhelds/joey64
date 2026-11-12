package com.joeysretro.joey64.launcher.screens

import com.joeysretro.joey64.launcher.components.TwoColumns
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.joeysretro.joey64.launcher.components.tabScroll
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.R
import com.joeysretro.joey64.controls.AutoMap
import com.joeysretro.joey64.controls.ControlsEditor
import com.joeysretro.joey64.controls.ControlsStore
import com.joeysretro.joey64.controls.DeviceChoice
import com.joeysretro.joey64.controls.FastForwardMode
import com.joeysretro.joey64.controls.Hotkeys
import com.joeysretro.joey64.controls.HotkeyAction
import com.joeysretro.joey64.controls.InputNames
import com.joeysretro.joey64.controls.N64Button
import com.joeysretro.joey64.controls.PadDevices
import com.joeysretro.joey64.controls.PadInput
import com.joeysretro.joey64.controls.Pak
import com.joeysretro.joey64.controls.GameBoyFiles
import com.joeysretro.joey64.controls.RumbleOn
import com.joeysretro.joey64.controls.PakDb
import com.joeysretro.joey64.controls.PlayerSetup
import com.joeysretro.joey64.controls.QuitTo
import com.joeysretro.joey64.controls.SlowMode
import com.joeysretro.joey64.controls.StickSource
import com.joeysretro.joey64.launcher.components.ButtonRow
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyButton
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PageChip
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupNote
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.PopupInfoNote
import com.joeysretro.joey64.launcher.components.PopupWarnNote
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.TabChip
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.controls.CaptureInput
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.controls.ControlBus
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.data.LibraryGame
import com.joeysretro.joey64.launcher.library.GameLauncher
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.states.GameStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controls (plan, Phase 4; approved mockups 1-11, Joey 2026-11-06). One page whose rows follow the
 * player picked with L / R: the Controller row first, then the N64 buttons, the D-pad and C button
 * groups (one row each, A opens the four directions: "Split" when they're not one thing), the stick,
 * its dead zone and the pak. The chips at the top: All games or one game, Hotkeys, Auto map, View
 * controller. Everything is saved as it changes (`files/controls/`, [ControlsStore]).
 *
 * Hosted by the launcher's rail ([ControlsScreen]) and, over the paused game, by the pause menu
 * ([InGameControls]), which opens it set to that game. The host passes the launcher's buttons in
 * through [ControlsSink] (X, Y, L / R) and asks it about B first.
 */
class ControlsSink {
    var onControl: (Control) -> Boolean = { false }
    /** B / Back: true when the page took it (closed a sub-page), false to close the page. */
    var onBack: () -> Boolean = { false }
}

/** Controls on the launcher's rail. */
@Composable
fun ControlsScreen(onHints: (List<Hint>) -> Unit, onClose: () -> Unit) {
    val sink = remember { ControlsSink() }
    BackHandler { if (!sink.onBack()) onClose() }
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { sink.onControl(it); true }
        ControlBus.push(handler)
        onDispose { ControlBus.pop(handler) }
    }
    val landing = LocalFocusLanding.current
    LaunchedEffect(Unit) { landing?.arm() }
    ControlsContent(game = null, gameTitle = null, inGame = false, sink = sink, onHints = onHints)
}

/** Controls over the paused game (mockup 11): the page without the rail, set to [saveName]. B goes back to the pause menu. */
@Composable
fun InGameControls(saveName: String, title: String, onClose: () -> Unit) {
    val sink = remember { ControlsSink() }
    var hints by remember { mutableStateOf(emptyList<Hint>()) }
    JoeyDialog(
        onDismiss = onClose,
        hints = hints,
        fullScreen = true,
        interceptBack = { sink.onBack() },
        onPadButton = { code -> com.joeysretro.joey64.launcher.controls.Controls.intentFor(code)?.let { sink.onControl(it) } },
    ) {
        ControlsContent(game = saveName, gameTitle = title, inGame = true, sink = sink, onHints = { hints = it })
    }
}

/** Controls set to one game, opened from Game settings at [player] (a changed "Player 2 · ..." row). B there goes back to Game settings. */
@Composable
fun GameControlsPage(saveName: String, title: String, player: Int, sink: ControlsSink, onHints: (List<Hint>) -> Unit) =
    ControlsContent(game = saveName, gameTitle = title, inGame = false, sink = sink, onHints = onHints, startPlayer = player)

private sealed interface Popup {
    data object Controller : Popup
    data object AutoConfirm : Popup
    data class OnePress(val layouts: List<AutoMap.Layout>) : Popup
    /** [back]: the popup to return to once a button is picked (Fast-forward's and Quit game's Button). */
    data class Remap(val target: Target, val back: Popup? = null) : Popup
    /** The Hotkeys page's Fast-forward and Quit game rows (approved popups, 2026-11-07); [row] has focus on return. */
    data class FastForward(val row: String = "button") : Popup
    data object FfMode : Popup
    /** Slow motion and Rewind (approved Slow motion and Rewind mockup, 2026-11-08), Fast-forward's shape. */
    data class SlowMotion(val row: String = "button") : Popup
    data object SlowModeChoice : Popup
    data class Rewind(val row: String = "button") : Popup
    data object RewindBack : Popup
    data object RewindSteps : Popup
    data class QuitGame(val row: String = "button") : Popup
    /** Any other hotkey row (approved hotkeys mockup 2026-11-07): Button and Needs the hotkey button. */
    data class Hotkey(val action: HotkeyAction, val row: String = "button") : Popup
    data object QuitTo : Popup
    data object Stick : Popup
    /** Stick feel (approved mockup 2026-11-07); [row] has focus on return from a row's choices. */
    data class StickFeel(val row: String = com.joeysretro.joey64.controls.StickFeel.RANGE) : Popup
    data class FeelChoice(val row: String) : Popup
    data object PakChoice : Popup
    /** Approved paks mockup 2026-11-08 (scratchpad build_paks2.py): Rumble on, the Transfer Pak setup and its lists. */
    data object RumbleOnChoice : Popup
    data class TransferSetup(val row: String = "game") : Popup
    data object GbGame : Popup
    data object GbSave : Popup
    /** Game Boy folders (approved Game Boy folder mockup 2026-11-08); B goes back to the list it came from. */
    data class GbFolders(val games: Boolean) : Popup
    data object Games : Popup
    data object PickGame : Popup
    data object Diagram : Popup
    /** Start: Reset tab for the player on screen (approved mockup B, 2026-11-07). */
    data object ResetPlayer : Popup
    /** Controls extras (approved mockup 2026-11-08): Tap to hold, Macros, Profiles. */
    data object Tap : Popup
    /** Turbo (approved mockup 2026-11-08, scratchpad build_turbo.py). */
    data object Turbo : Popup
    data object Macros : Popup
    data object Profiles : Popup
    /** Touch controls (approved mockup 2026-11-09): a row's choices (Show, Hide when not touched, Stick), the resets, All games ›. */
    data class TouchChoice(val row: String) : Popup
    data object TouchResetLayouts : Popup
    data object TouchResetPage : Popup
    data object TouchGames : Popup
    data object TouchPickGame : Popup
}

/** What a remap popup sets. */
private sealed interface Target {
    data class Button(val b: N64Button) : Target
    data object HotkeyButton : Target
    data class Hotkey(val action: HotkeyAction) : Target
}

private const val DPAD = "DPad"
private val DPAD_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
private const val CGROUP = "C"
private val DEAD_ZONES = listOf(0, 5, 10, 15, 20, 25, 30)
private val STRENGTHS = PlayerSetup.RUMBLE_STRENGTHS

@Composable
private fun ControlsContent(game: String?, gameTitle: String?, inGame: Boolean, sink: ControlsSink, onHints: (List<Hint>) -> Unit, startPlayer: Int = 0) {
    val context = LocalContext.current
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    val store = remember { ControlsStore(context) }
    LaunchedEffect(Unit) { if (!store.hasAll()) store.saveAll(PadDevices.firstSet(com.joeysretro.joey64.launcher.layout.Tv.isTv(context))) }
    var target by remember { mutableStateOf(game) }
    var targetTitle by remember { mutableStateOf(gameTitle) }
    val editor = remember(target) {
        ControlsEditor(store, target) { setup -> PadDevices.autoSetup(PadDevices.deviceFor(setup.device), setup.device, keep = null) }
    }
    var version by remember { mutableIntStateOf(0) }
    fun edit(block: () -> Unit) { block(); version++ }
    var player by remember { mutableIntStateOf(startPlayer) }
    var hotkeysOpen by remember { mutableStateOf(false) }
    // Controls › Touch controls (approved mockup 2026-11-09): a page of its own from Player 1's chip, and its layout editor.
    var touchOpen by remember { mutableStateOf(false) }
    var touchEdit by remember { mutableStateOf<Boolean?>(null) }
    val touchStore = remember { com.joeysretro.joey64.touch.TouchStore(context) }
    var touchVer by remember { mutableIntStateOf(0) }
    val touchNow = remember(target, touchVer) { touchStore.forGame(target) }
    val touchBase = remember(target, touchVer) { if (target == null) com.joeysretro.joey64.touch.TouchSettings() else touchStore.all() }
    fun setTouch(t: com.joeysretro.joey64.touch.TouchSettings) { touchStore.save(target, t); touchVer++ }
    var popup by remember { mutableStateOf<Popup?>(null) }
    var focused by remember { mutableStateOf("Controller") }
    var open by remember { mutableStateOf(setOf<String>()) }
    // Devices change while the page is open (a pad connects): read again whenever a popup closes.
    val pads = remember(popup, version) { PadDevices.pads() }

    @Suppress("UNUSED_EXPRESSION") version // edits recompose through it
    val shown = editor.shown
    val setup = shown.players[player]
    val dev = remember(setup.device, pads) { PadDevices.deviceFor(setup.device) }
    val face = remember(dev) { faceFor(dev) }
    val off = setup.device == DeviceChoice.None
    val changed = editor.changed(player)

    // Auto pak on a game says which pak it picks.
    var autoPak by remember(target) { mutableStateOf<Pak?>(null) }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        autoPak = withContext(Dispatchers.IO) {
            try {
                val md5 = LibraryDb.get(context).gameWithFile(t, GameStates::saveNameOf)?.md5
                PakDb.auto(PakDb.load(context), md5)
            } catch (e: Exception) { JLog.e(TAG, "Auto pak: can't look up $t: $e"); null }
        }
    }

    // ---- the launcher's buttons ----
    val current by rememberUpdatedState(Triple(focused, player, hotkeysOpen))
    val touchPage by rememberUpdatedState(touchOpen)
    sink.onControl = { control ->
        val (row, p, hk) = current
        if (touchPage) when (control) {
            Control.TabPrev, Control.TabNext, Control.Extra -> true
            Control.Search -> {
                when (row) {
                    "Tap", "Macros" -> if (target != null) edit { resetRow(editor, 0, row) }
                    "tResetLayouts", "tChipGame", "tChipEdit" -> {}
                    else -> setTouch(touchNow.rowFrom(row, touchBase))
                }
                true
            }
            Control.Options -> { popup = Popup.TouchResetPage; true }
            else -> false
        } else when (control) {
            Control.TabPrev -> { val n = ((if (hk) HOTKEYS_TAB else p) + 4) % 5; hotkeysOpen = n == HOTKEYS_TAB; if (n != HOTKEYS_TAB) player = n; open = emptySet(); true }
            Control.TabNext -> { val n = ((if (hk) HOTKEYS_TAB else p) + 1) % 5; hotkeysOpen = n == HOTKEYS_TAB; if (n != HOTKEYS_TAB) player = n; open = emptySet(); true }
            Control.Extra -> { edit { if (hk) clearHotkey(editor, row) else clearRow(editor, p, row) }; true }
            // Tap to hold and Macros: Y is Use All games on a game; on All games they have no default but none.
            Control.Search -> { if (!(row in EXTRA_ROWS && target == null)) edit { if (hk) resetHotkey(editor, row) else resetRow(editor, p, row) }; true }
            Control.Options -> { if (!hk && editor.shown.players[p].device != DeviceChoice.None) popup = Popup.ResetPlayer; true }
            else -> false
        }
    }
    sink.onBack = {
        if (touchOpen) { touchOpen = false; focused = "chipTouch"; true }
        else false
    }

    // ---- hints ----
    val backLabel = if (inGame) "Back to the pause menu" else "Back"
    val ctlHints = (when {
            focused == "chipTouch" -> listOf(Hint("A", "Open"), Hint("B", backLabel))
            focused == "tabs" -> listOf(Hint("A", "Open"), Hint("B", backLabel))
            hotkeysOpen -> listOf(Hint("A", if (focused == "hkFastForward" || focused == "hkQuit") "Open" else "Change"), Hint("X", "Clear"),
                Hint("Y", "Reset to default"), Hint("B", "Back"))
            focused.startsWith("chip") -> listOf(Hint("A", "Select"), Hint("B", backLabel))
            focused == "DeadZone" || (focused == "RumbleStrength" && !off) -> listOf(Hint("◀▶", "Change"), Hint("Y", if (target == null) "Reset to default" else "Use All games"),
                Hint("B", backLabel))
            focused == "Controller" -> listOf(Hint("A", "Change"), Hint("B", backLabel))
            // Pak and Rumble on: nothing for X to clear (approved paks mockup screen 1's hints).
            (focused == "Pak" || focused == "RumbleOn") && !off -> listOf(Hint("A", "Change"),
                Hint("Y", if (target == null) "Reset to default" else "Use All games"), Hint("B", backLabel))
            focused == "StickFeel" && !off -> listOf(Hint("A", "Open"), Hint("Y", if (target == null) "Reset to default" else "Use All games"),
                Hint("B", backLabel))
            off -> listOf(Hint("B", backLabel))
            focused in EXTRA_ROWS -> listOfNotNull(Hint("A", "Change"), if (target == null) null else Hint("Y", "Use All games"),
                Hint("B", backLabel))
            else -> listOf(Hint("A", "Change"), Hint("X", "Clear"), Hint("Y", if (target == null) "Reset to default" else "Use All games"),
                Hint("B", backLabel))
        }).let { if (hotkeysOpen || off) it else withResetHint(it) }

    val landing = LocalFocusLanding.current
    LaunchedEffect(touchOpen) { landing?.arm() }

    // "Mario Kart loaded" after a profile loads (mockup 9), for 3 seconds.
    var loaded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(loaded) { if (loaded != null) { kotlinx.coroutines.delay(3_000L); loaded = null } }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = s.margin).padding(top = 14.dp, bottom = 6.dp)) {
        if (touchOpen) {
            TouchControlsPage(touchNow, shown.players[0], targetTitle, if (target == null) emptySet() else touchNow.changedFrom(touchBase),
                editor.changed(0), focused, onFocused = { focused = it }, onGames = { popup = Popup.TouchGames },
                onEdit = { touchEdit = it },
                onPopup = { row -> popup = when (row) { "Tap" -> Popup.Tap; "Macros" -> Popup.Macros; "tResetLayouts" -> Popup.TouchResetLayouts
                    else -> Popup.TouchChoice(row) } },
                onSet = { setTouch(it) }, hints = touchPageHints(focused, target != null, backLabel), onHints = onHints, modifier = Modifier.weight(1f))
        } else {
            @Composable
            fun headerChips() {
                PageChip((targetTitle ?: "All games") + "  ›", R.drawable.ic_sliders, on = target != null,
                    onFocused = { if (it) focused = "chipGame" }) { popup = Popup.Games }
            }
            // No title under the top bar; the chips are the first thing on the page (approved top bar, 2026-11-11).
            com.joeysretro.joey64.launcher.components.PageHeader(null) { headerChips() }
            // Scrolls sideways on a narrow screen rather than wrapping "Player 4".
            // A focus stop too (Joey 2026-11-09): Up from the chips, ◀ ▶, A picks the player.
            // Hotkeys is the fifth tab, after Player 4 (Joey 2026-11-11): the same for every game and player.
            com.joeysretro.joey64.launcher.components.TabRow(5, if (hotkeysOpen) HOTKEYS_TAB else player, Modifier.padding(top = 6.dp).tabScroll(rememberScrollState()),
                onFocused = { if (it) focused = "tabs" }, onPick = { i ->
                    if (i == HOTKEYS_TAB) hotkeysOpen = true
                    else if (i != player || hotkeysOpen) { hotkeysOpen = false; player = i; open = emptySet() }
                }) { i, m ->
                TabChip(if (i == HOTKEYS_TAB) "Hotkeys" else "Player ${i + 1}", if (i == HOTKEYS_TAB) hotkeysOpen else (i == player && !hotkeysOpen), m)
            }
            if (hotkeysOpen) {
                HotkeysPage(editor, version, focused, ctlHints, onHints, onFocused = { focused = it }, onOpen = { popup = it }) { popup = Popup.Remap(it) }
            } else {
            Row(Modifier.padding(top = 6.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PageChip("Auto map", R.drawable.ic_wand, onFocused = { if (it) focused = "chipAuto" }) { if (!off) popup = Popup.AutoConfirm }
                PageChip("View controller", R.drawable.ic_eye, onFocused = { if (it) focused = "chipView" }) { popup = Popup.Diagram }
                PageChip("Profiles", R.drawable.ic_bookmark, onFocused = { if (it) focused = "chipProfiles" }) { if (!off) popup = Popup.Profiles }
                // Touch controls are Player 1's (approved mockup 2026-11-09, screen 7).
                if (player == 0 && remember { com.joeysretro.joey64.launcher.layout.Tv.hasTouch(context) }) PageChip("Touch controls", R.drawable.ic_hand_click, modifier = Modifier.initialFocus(focused == "chipTouch"),
                    onFocused = { if (it) focused = "chipTouch" }) { touchOpen = true; focused = com.joeysretro.joey64.touch.TouchSettings.ROW_SHOW }
            }
            // One list with the panel beside it (approved 2026-11-11): the focused row's help, and the choices of
            // Stick, Pak and Rumble on.
            val panel = com.joeysretro.joey64.launcher.components.rememberListPanel()
            val (ctlTitle, ctlHelp) = controlsRowHelp(focused, setup, face, off)
            val ctlInfo = when {
                off -> com.joeysretro.joey64.launcher.components.PanelInfo(ctlTitle, ctlHelp)
                focused == "Stick" -> StickSource.entries.let { all -> com.joeysretro.joey64.launcher.components.PanelInfo(ctlTitle, ctlHelp,
                    all.map { com.joeysretro.joey64.launcher.components.PanelChoice(it.label, it.detail, it == setup.stick) }) { i ->
                    edit { editor.set(player, setup.copy(stick = all[i])) } } }
                focused == "Pak" -> Pak.entries.let { all -> com.joeysretro.joey64.launcher.components.PanelInfo(ctlTitle, ctlHelp,
                    all.map { val oneGame = it == Pak.Transfer && target == null
                        com.joeysretro.joey64.launcher.components.PanelChoice(pakValue(it, autoPak),
                            if (oneGame) "For one game: pick it in the chip" else pakDetail(it), it == setup.pak, enabled = !oneGame) }) { i ->
                    // Transfer Pak opens its setup (Game Boy game and save), as the popup did.
                    if (all[i] == Pak.Transfer) popup = Popup.PakChoice else edit { editor.set(player, setup.copy(pak = all[i])) } } }
                focused == "RumbleOn" -> {
                    val motor = com.joeysretro.joey64.launcher.layout.Tv.hasVibrator(context)
                    val ons = if (motor) RumbleOn.entries else RumbleOn.entries.filter { it != RumbleOn.Device }
                    com.joeysretro.joey64.launcher.components.PanelInfo(ctlTitle, ctlHelp,
                        ons.map { com.joeysretro.joey64.launcher.components.PanelChoice(it.label, if (!motor && it == RumbleOn.Controller) "Its own motor" else it.detail,
                            it == setup.rumbleOn) }) { i -> edit { editor.set(player, setup.copy(rumbleOn = ons[i])) } }
                }
                else -> com.joeysretro.joey64.launcher.components.PanelInfo(ctlTitle, ctlHelp)
            }
            com.joeysretro.joey64.launcher.components.ListPanel(panel, ctlInfo, ctlHints, onHints, Modifier.weight(1f)) {
                SectionLabel("Controller")
                JoeyRow("Controller", Modifier.initialFocus(focused == "Controller"), value = deviceName(setup.device) + "  ›", sideways = true,
                    onFocused = { if (it) focused = "Controller" }) { popup = Popup.Controller }
                SectionLabel("Buttons")
                for (b in N64Button.BUTTONS) BindingRow(b, b.label, setup, face, off, b.name in changed, { focused = it }) { popup = Popup.Remap(Target.Button(b)) }
                SectionLabel("D-pad and C buttons")
                for ((key, label, dirs) in listOf(Triple(DPAD, "D-pad", N64Button.DPAD), Triple(CGROUP, "C buttons", N64Button.C))) {
                    val expanded = key in open
                    val value = InputNames.group(setup, dirs) ?: "Split"
                    JoeyRow(label, value = value + if (expanded) "  ∨" else "  ›", enabled = !off, sideways = true,
                        dot = dirs.any { it.name in changed },
                        onFocused = { if (it) focused = key }) { open = if (expanded) open - key else open + key }
                    if (expanded) for (b in dirs) BindingRow(b, arrow(b) + " " + b.label, setup, face, off, b.name in changed, { focused = it }, indent = true) {
                        popup = Popup.Remap(Target.Button(b))
                    }
                }
                SectionLabel("Analog stick")
                JoeyRow("Stick", value = setup.stick.label + "  ›", enabled = !off, sideways = true,
                    dot = "Stick" in changed,
                    onFocused = { if (it) focused = "Stick" }) { if (!panel.enter()) popup = Popup.Stick }
                JoeyRow("Dead zone", Modifier.onPreviewKeyEvent { e ->
                    val n = e.nativeKeyEvent
                    val step = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
                    if (step == 0 || off) return@onPreviewKeyEvent false
                    if (n.action == KeyEvent.ACTION_DOWN) {
                        val i = (DEAD_ZONES.indexOfFirst { it >= setup.deadZone }.takeIf { it >= 0 } ?: DEAD_ZONES.lastIndex) + step
                        edit { editor.set(player, setup.copy(deadZone = DEAD_ZONES[i.coerceIn(0, DEAD_ZONES.lastIndex)])) }
                    }
                    true
                }, value = "◀ ${setup.deadZone}% ▶", enabled = !off, sideways = true,
                    dot = "DeadZone" in changed,
                    onFocused = { if (it) focused = "DeadZone" })
                JoeyRow("Stick feel", value = stickFeelValue(setup.feel) + "  ›", enabled = !off, sideways = true,
                    dot = changed.any { it.startsWith("Feel.") },
                    onFocused = { if (it) focused = "StickFeel" }) { popup = Popup.StickFeel() }
                SectionLabel("Pak")
                JoeyRow("Pak", value = pakValue(setup.pak, autoPak, setup) + "  ›", enabled = !off, sideways = true,
                    dot = "Pak" in changed,
                    onFocused = { if (it) focused = "Pak" }) { if (!panel.enter()) popup = Popup.PakChoice }
                // Greyed when the pak can't be a Rumble Pak (approved paks mockup 2026-11-08, screens 1 and 4a).
                val rumble = !off && when (setup.pak) { Pak.Rumble -> true; Pak.Auto -> autoPak == null || autoPak == Pak.Rumble; else -> false }
                JoeyRow("Rumble on", value = setup.rumbleOn.label + "  ›", enabled = rumble, sideways = true,
                    dot = "RumbleOn" in changed,
                    onFocused = { if (it) focused = "RumbleOn" }) { if (rumble && !panel.enter()) popup = Popup.RumbleOnChoice }
                val strengthWorks = remember(setup.device, setup.rumbleOn, pads) { rumbleStrengthWorks(context, dev, setup) }
                JoeyRow("Rumble strength", Modifier.onPreviewKeyEvent { e ->
                    val n = e.nativeKeyEvent
                    val step = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
                    if (step == 0) return@onPreviewKeyEvent false
                    if (rumble && strengthWorks && n.action == KeyEvent.ACTION_DOWN) {
                        val i = (STRENGTHS.indexOfFirst { it >= setup.rumbleStrength }.takeIf { it >= 0 } ?: STRENGTHS.lastIndex) + step
                        edit { editor.set(player, setup.copy(rumbleStrength = STRENGTHS[i.coerceIn(0, STRENGTHS.lastIndex)])) }
                    }
                    true
                }, value = if (strengthWorks) "◀ ${setup.rumbleStrength}% ▶" else "Full only", enabled = rumble, sideways = true,
                    dot = "RumbleStrength" in changed,
                    onFocused = { if (it) focused = "RumbleStrength" })
                SectionLabel("Extras")
                JoeyRow("Tap to hold", value = com.joeysretro.joey64.controls.latchLabel(setup.latch) + "  ›", enabled = !off, sideways = true,
                    dot = "Tap" in changed, onFocused = { if (it) focused = "Tap" }) { popup = Popup.Tap }
                JoeyRow("Turbo", value = com.joeysretro.joey64.controls.turboLabel(setup.turbo) + "  ›", enabled = !off, sideways = true,
                    dot = "Turbo" in changed, onFocused = { if (it) focused = "Turbo" }) { popup = Popup.Turbo }
                JoeyRow("Macros", value = macrosValue(setup.macros.size) + "  ›", enabled = !off, sideways = true,
                    dot = "Macros" in changed, onFocused = { if (it) focused = "Macros" }) { popup = Popup.Macros }
            }
        }
        }
        }
    loaded?.let { name ->
        com.joeysretro.joey64.launcher.components.JoeyToast(R.drawable.ic_circle_check, "Profiles", "$name loaded",
            "Player ${player + 1}, ${targetTitle ?: "All games"}", Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp))
    }
    }

    // ---- popups ----
    val close = { popup = null }
    when (val p = popup) {
        null -> {}
        Popup.Controller -> ControllerPopup(player, shown.players.map { it.device }, pads, close) { choice, d ->
            edit { editor.setDevice(player, choice, PadDevices.autoSetup(d, choice, keep = setup)) }
            close()
        }
        Popup.AutoConfirm -> AutoMapConfirm(player, deviceName(setup.device), dev, close) {
            val layouts = dev?.let { PadDevices.layouts(it) }.orEmpty()
            if (layouts.size > 1) popup = Popup.OnePress(layouts)
            else { edit { editor.autoMap(player, PadDevices.autoSetup(dev, setup.device)) }; close() }
        }
        is Popup.OnePress -> OnePressPopup(deviceName(setup.device)) { code ->
            val layout = code?.let { AutoMap.pickByPress(p.layouts, it) }
            val mapped = if (layout != null) AutoMap.fromLayout(setup.device, layout, face ?: AutoMap.FaceCodes.POSITION)
                else AutoMap.byPosition(setup.device, PadDevices.face(dev))
            edit { editor.autoMap(player, mapped) }
            close()
        }
        is Popup.Remap -> RemapPopup(p.target, setup, editor, face) { input, keyCode ->
            if (input != null || keyCode != null) edit {
                when (val t = p.target) {
                    is Target.Button -> editor.set(player, setup.with(t.b, input))
                    Target.HotkeyButton -> keyCode?.let { editor.setHotkeys(editor.hotkeys.copy(button = it)) }
                    is Target.Hotkey -> keyCode?.let { editor.setHotkeys(editor.hotkeys.withKey(t.action, it)) }
                }
            }
            popup = p.back
        }
        is Popup.Hotkey -> HotkeyPopup(p.action, editor, version, p.row, close, player1 = editor.shown.players[0],
            onButton = { popup = Popup.Remap(Target.Hotkey(p.action), Popup.Hotkey(p.action, "button")) }) { h -> edit { editor.setHotkeys(h) } }
        is Popup.FastForward -> FastForwardPopup(editor, version, p.row, close, editor.shown.players[0],
            onButton = { popup = Popup.Remap(Target.Hotkey(HotkeyAction.FastForward), Popup.FastForward("button")) },
            onMode = { popup = Popup.FfMode }) { h -> edit { editor.setHotkeys(h) } }
        Popup.FfMode -> ChoicePopup("Mode", null,
            FastForwardMode.entries.map { Choice(it.label, it.detail, it == editor.hotkeys.ffMode, experimental = it.experimental) }, { popup = Popup.FastForward("mode") }) { i ->
            edit { editor.setHotkeys(editor.hotkeys.copy(ffMode = FastForwardMode.entries[i])) }; popup = Popup.FastForward("mode")
        }
        is Popup.SlowMotion -> SlowMotionPopup(editor, version, p.row, close, editor.shown.players[0],
            onButton = { popup = Popup.Remap(Target.Hotkey(HotkeyAction.SlowMotion), Popup.SlowMotion("button")) },
            onMode = { popup = Popup.SlowModeChoice }) { h -> edit { editor.setHotkeys(h) } }
        Popup.SlowModeChoice -> ChoicePopup("Mode", null,
            SlowMode.entries.map { Choice(it.label, it.detail, it == editor.hotkeys.slowMode) }, { popup = Popup.SlowMotion("mode") }) { i ->
            edit { editor.setHotkeys(editor.hotkeys.copy(slowMode = SlowMode.entries[i])) }; popup = Popup.SlowMotion("mode")
        }
        is Popup.Rewind -> RewindPopup(editor, version, p.row, close, editor.shown.players[0],
            onButton = { popup = Popup.Remap(Target.Hotkey(HotkeyAction.Rewind), Popup.Rewind("button")) },
            onBack = { popup = Popup.RewindBack }, onSteps = { popup = Popup.RewindSteps }) { h -> edit { editor.setHotkeys(h) } }
        Popup.RewindBack -> ChoicePopup("How far back", "Longer takes more memory.",
            Hotkeys.REWIND_SECONDS.map { s -> Choice(Hotkeys.rewindSecondsLabel(s), "About ${Hotkeys.rewindMb(s, editor.hotkeys.rewindEvery)} MB of memory",
                s == editor.hotkeys.rewindSeconds) }, { popup = Popup.Rewind("back") }) { i ->
            edit { editor.setHotkeys(editor.hotkeys.copy(rewindSeconds = Hotkeys.REWIND_SECONDS[i])) }; popup = Popup.Rewind("back")
        }
        Popup.RewindSteps -> ChoicePopup("Steps", "How often a moment is kept. More often goes back more smoothly and takes more memory.",
            Hotkeys.REWIND_STEPS.map { n -> Choice(Hotkeys.rewindEveryLabel(n), "About ${Hotkeys.rewindMb(editor.hotkeys.rewindSeconds, n)} MB for ${Hotkeys.rewindSecondsLabel(editor.hotkeys.rewindSeconds)}",
                n == editor.hotkeys.rewindEvery) }, { popup = Popup.Rewind("steps") }) { i ->
            edit { editor.setHotkeys(editor.hotkeys.copy(rewindEvery = Hotkeys.REWIND_STEPS[i])) }; popup = Popup.Rewind("steps")
        }
        is Popup.QuitGame -> QuitGamePopup(editor, version, p.row, close, editor.shown.players[0],
            onButton = { popup = Popup.Remap(Target.Hotkey(HotkeyAction.Quit), Popup.QuitGame("button")) },
            onQuitTo = { popup = Popup.QuitTo }) { h -> edit { editor.setHotkeys(h) } }
        Popup.QuitTo -> ChoicePopup("Quit to", null,
            QuitTo.entries.map { Choice(it.label, it.detail, it == editor.hotkeys.quitTo) }, { popup = Popup.QuitGame("to") }) { i ->
            edit { editor.setHotkeys(editor.hotkeys.copy(quitTo = QuitTo.entries[i])) }; popup = Popup.QuitGame("to")
        }
        Popup.Stick -> ChoicePopup("Player ${player + 1} stick", "What moves the N64 stick.",
            StickSource.entries.map { Choice(it.label, it.detail, it == setup.stick) }, close) { i ->
            edit { editor.set(player, setup.copy(stick = StickSource.entries[i])) }; close()
        }
        is Popup.StickFeel -> StickFeelPopup(player, setup, changed, if (target == null) null else editor.all.players[player].feel, p.row, close,
            onSet = { f -> edit { editor.set(player, setup.copy(feel = f)) } },
            onReset = { row -> edit { editor.resetRow(player, row) } },
            onChoice = { row -> popup = Popup.FeelChoice(row) })
        is Popup.FeelChoice -> {
            val choices = feelChoices(setup.feel, p.row)
            ChoicePopup(feelLabel(p.row), null, choices.map { it.first }, { popup = Popup.StickFeel(p.row) }) { i ->
                edit { editor.set(player, setup.copy(feel = choices[i].second)) }; popup = Popup.StickFeel(p.row)
            }
        }
        Popup.PakChoice -> ChoicePopup("Player ${player + 1} pak", "What's in the back of the controller.",
            Pak.entries.map {
                // Transfer Pak is set for one game: each game needs its own Game Boy game (screen 2).
                val oneGame = it == Pak.Transfer && target == null
                Choice(pakValue(it, autoPak), if (oneGame) "For one game: pick it in the chip" else pakDetail(it), it == setup.pak,
                    enabled = !oneGame)
            }, close) { i ->
            val pak = Pak.entries[i]
            edit { editor.set(player, setup.copy(pak = pak)) }
            // Transfer Pak opens its setup (screen 4), also when it was already the pak.
            popup = if (pak == Pak.Transfer) Popup.TransferSetup() else null
        }
        Popup.RumbleOnChoice -> {
            // A TV has no motor of its own: no This device (Android TV mockup 2026-11-09, screen 7).
            val motor = com.joeysretro.joey64.launcher.layout.Tv.hasVibrator(context)
            val ons = if (motor) RumbleOn.entries else RumbleOn.entries.filter { it != RumbleOn.Device }
            ChoicePopup("Player ${player + 1} rumble", "Where the Rumble Pak's shaking goes.",
                ons.map { Choice(it.label, if (!motor && it == RumbleOn.Controller) "Its own motor" else it.detail, it == setup.rumbleOn) }, close) { i ->
                edit { editor.set(player, setup.copy(rumbleOn = ons[i])) }; close()
            }
        }
        is Popup.TransferSetup -> TransferSetupPopup(targetTitle ?: target ?: "", setup, p.row, close,
            onGame = { popup = Popup.GbGame }, onSave = { popup = Popup.GbSave })
        Popup.GbGame -> GameBoyListPopup(games = true, setup = setup, onDismiss = { popup = Popup.TransferSetup("game") },
            onFolders = { popup = Popup.GbFolders(true) }) { game, save ->
            edit { editor.set(player, setup.copy(gbGame = game, gbSave = save)) }; popup = Popup.TransferSetup("game")
        }
        Popup.GbSave -> GameBoyListPopup(games = false, setup = setup, onDismiss = { popup = Popup.TransferSetup("save") },
            onFolders = { popup = Popup.GbFolders(false) }, onDefaultSave = { save -> edit { editor.set(player, setup.copy(gbSave = save)) } }) { save, _ ->
            edit { editor.set(player, setup.copy(gbSave = save)) }; popup = Popup.TransferSetup("save")
        }
        is Popup.GbFolders -> GameBoyFoldersPopup { popup = if (p.games) Popup.GbGame else Popup.GbSave }
        Popup.Games -> GamesForPopup("Controls for", "A game's own controls are used instead of All games when it starts.", target,
            gamesWithOwn = { editor.gamesWithOwn() }, changeCount = { editor.changeCount(it) }, what = "controls",
            onDismiss = close, onPick = { save, title -> target = save; targetTitle = title; close() },
            onPickGame = { popup = Popup.PickGame }, onRemoved = { save -> edit { editor.removeGame(save) } })
        Popup.PickGame -> PickGamePopup("Its controls start as a copy of All games.", { popup = Popup.Games }) { save, title -> target = save; targetTitle = title; close() }
        Popup.Diagram -> JoeyDialog(onDismiss = close, hints = listOf(Hint("B", "Close")), width = 760.dp) {
            ControllerDiagram(player, deviceName(setup.device), setup, face)
        }
        Popup.Tap -> TapToHoldPopup(player, setup.latch, { l -> edit { editor.set(player, setup.copy(latch = l)) } }, close)
        Popup.Turbo -> TurboPopup(player, setup.turbo, setup.turboSpeed, setup.latch,
            { t, s -> edit { editor.set(player, setup.copy(turbo = t, turboSpeed = s)) } }, close)
        Popup.Macros -> MacrosPopup(player, setup.macros, editor.hotkeys, { m -> edit { editor.set(player, setup.copy(macros = m)) } }, close)
        Popup.Profiles -> ProfilesPopup(player, deviceName(setup.device), targetTitle ?: target, editor, version,
            onChanged = { version++ }, onLoaded = { name -> loaded = name; close() }, onDismiss = close)
        is Popup.TouchChoice -> when (p.row) {
            com.joeysretro.joey64.touch.TouchSettings.ROW_SHOW -> ChoicePopup("Show touch controls", null,
                com.joeysretro.joey64.touch.TouchShow.entries.map { Choice(it.label, it.detail, it == touchNow.show) }, close) { i ->
                setTouch(touchNow.copy(show = com.joeysretro.joey64.touch.TouchShow.entries[i])); close() }
            com.joeysretro.joey64.touch.TouchSettings.ROW_HIDE -> ChoicePopup("Hide when not touched", "Touch the screen to bring them back.",
                com.joeysretro.joey64.touch.TouchSettings.HIDE_STEPS.map { Choice(com.joeysretro.joey64.touch.TouchSettings.hideLabel(it), null, it == touchNow.hideAfter) }, close) { i ->
                setTouch(touchNow.copy(hideAfter = com.joeysretro.joey64.touch.TouchSettings.HIDE_STEPS[i])); close() }
            else -> ChoicePopup("Stick", "How the on-screen stick follows your thumb.",
                com.joeysretro.joey64.touch.TouchStick.entries.map { Choice(it.label, it.detail, it == touchNow.stick) }, close) { i ->
                setTouch(touchNow.copy(stick = com.joeysretro.joey64.touch.TouchStick.entries[i])); close() }
        }
        Popup.TouchResetLayouts -> ResetTabPopup("Reset layouts?",
            "Landscape and portrait go back to " + (if (target == null) "the layouts Joey64 starts with." else "All games' layouts."),
            "Keep my layouts", "Reset layouts", close) {
            setTouch(touchNow.copy(landscape = touchBase.landscape, portrait = touchBase.portrait)); close()
        }
        Popup.TouchResetPage -> {
            val g = targetTitle ?: target
            if (target == null) ResetTabPopup("Reset touch controls?", "Every row on this page and both layouts go back to their defaults. " +
                "Tap to hold and Macros stay: they're Player 1's Controls rows.", "Keep my settings", "Reset to defaults", close) {
                setTouch(com.joeysretro.joey64.touch.TouchSettings()); JLog.i(TAG, "reset page: touch controls, All games"); close()
            } else if (touchNow.changedFrom(touchBase).isEmpty()) NothingToResetPopup("$g uses All games' touch controls", "It has none of its own.", close)
            else ResetTabPopup("Use All games for touch controls?", "$g's own touch controls go, and it uses All games' again.",
                "Keep its settings", "Use All games", close) {
                touchStore.removeGame(target!!); touchVer++; JLog.i(TAG, "reset page: touch controls, a game"); close()
            }
        }
        Popup.TouchGames -> GamesForPopup("Touch controls for", "A game's own touch controls are used instead of All games when it starts.", target,
            gamesWithOwn = { touchStore.gamesWithOwn() }, changeCount = { touchStore.changeCount(it) }, what = "touch controls",
            onDismiss = close, onPick = { save, title -> target = save; targetTitle = title; close() },
            onPickGame = { popup = Popup.TouchPickGame }, onRemoved = { save -> touchStore.removeGame(save); touchVer++ })
        Popup.TouchPickGame -> PickGamePopup("Its touch controls start as a copy of All games.", { popup = Popup.TouchGames }) { save, title ->
            target = save; targetTitle = title; close() }
        Popup.ResetPlayer -> {
            val who = "Player ${player + 1}"
            val g = targetTitle ?: target
            if (target == null) ResetTabPopup("Reset $who?", "$who's buttons, stick, dead zone, stick feel and pak go back to what Auto map gives " +
                "${deviceName(setup.device)}. The controller and the hotkeys stay.", "Keep my settings", "Reset to defaults", close) {
                edit { editor.resetPlayer(player) }; JLog.i(TAG, "reset tab: player ${player + 1}, All games"); close()
            } else if (changed.isEmpty()) NothingToResetPopup("$who uses All games' controls", "$g has none of its own for $who.", close)
            else ResetTabPopup("Use All games for $who?", "${changesGo(changed.size, "for $who in $g")}, and $who uses All games' controls again.",
                "Keep its settings", "Use All games", close) {
                edit { editor.resetPlayer(player) }; JLog.i(TAG, "reset tab: player ${player + 1}, a game"); close()
            }
        }
    }

    // The layout editor (approved mockup screens 3-6), over the paused game or, in the launcher, a screenshot.
    touchEdit?.let { portrait ->
        val p1 = editor.shown.players[0]
        TouchEditor(portrait, touchNow.layout(portrait), touchBase.layout(portrait), touchNow.opacity, p1.macros, inGame,
            macrosPopup = { onClose -> MacrosPopup(0, editor.shown.players[0].macros, editor.hotkeys,
                { m -> edit { editor.set(0, editor.shown.players[0].copy(macros = m)) } }, onClose) }) { list ->
            touchEdit = null
            // Back to the starting layout on All games: kept as "the approved one", so a later default change reaches it.
            val keep = if (target == null && list == com.joeysretro.joey64.touch.TouchSettings().layout(portrait)) null else list
            setTouch(touchNow.withLayout(portrait, keep))
            JLog.i(TAG, "touch controls: ${if (portrait) "portrait" else "landscape"} layout saved, ${list.count { !it.hidden }} controls shown")
        }
    }
}

private const val TAG = "Joey64Controls"
private val EXTRA_ROWS = setOf("Tap", "Turbo", "Macros")

/** The Macros row's value: "None", "1 macro", "2 macros". */
private fun macrosValue(n: Int) = when (n) { 0 -> "None"; 1 -> "1 macro"; else -> "$n macros" }

/** The player page's panel title and help for the focused row (approved list + panel, 2026-11-11). */
private fun controlsRowHelp(row: String, setup: PlayerSetup, face: AutoMap.FaceCodes?, off: Boolean): Pair<String, String> {
    if (off) return "No controller" to "This player has no controller. Pick one in Controller to set their buttons."
    N64Button.entries.firstOrNull { it.name == row }?.let { b ->
        return b.label to "What presses the N64's ${b.label}: ${InputNames.of(setup.buttons[b], face)}. A changes it, X clears it."
    }
    return when (row) {
        "Controller" -> "Controller" to "Which controller this player uses: the built-in controls, a pad, or None."
        DPAD -> "D-pad" to "The N64 D-pad's four directions. A shows them one by one."
        CGROUP -> "C buttons" to "The N64's four C buttons. A shows them one by one."
        "Stick" -> "Stick" to "What moves the N64 stick."
        "DeadZone" -> "Dead zone" to "How far the stick moves before the game sees it. Raise it if the game moves on its own."
        "StickFeel" -> "Stick feel" to "Range, response curve, octagon gate, invert and more. A opens them, with a live preview."
        "Pak" -> "Pak" to "What's in the back of the controller: Auto picks the Rumble Pak for games that use it, else the Controller Pak."
        "RumbleOn" -> "Rumble on" to "Where the Rumble Pak's shaking goes."
        "RumbleStrength" -> "Rumble strength" to "How hard it shakes. Some motors only have full strength."
        "Tap" -> "Tap to hold" to "Ticked buttons stay held after a tap until the next tap."
        "Turbo" -> "Turbo" to "Ticked buttons press again and again while held."
        "Macros" -> "Macros" to "One button plays a list of presses. Up to 8 a player."
        "tabs" -> "Players" to "Each player's controller and buttons. ◀ ▶ picks one."
        "chipGame" -> "All games or one game" to "Controls for every game, or for one game only."
        "chipAuto" -> "Auto map" to "Sets this player's buttons from the controller's known layout."
        "chipView" -> "View controller" to "A picture of the controller with what each button does."
        "chipProfiles" -> "Profiles" to "Save this player's buttons under a name, or load a saved one."
        "chipTouch" -> "Touch controls" to "Player 1's buttons on the screen."
        else -> "" to ""
    }
}

@Composable
private fun BindingRow(b: N64Button, label: String, setup: PlayerSetup, face: AutoMap.FaceCodes?, off: Boolean, changed: Boolean,
                      onFocused: (String) -> Unit, indent: Boolean = false, onClick: () -> Unit) {
    JoeyRow(label, Modifier.padding(start = if (indent) 16.dp else 0.dp), value = InputNames.of(setup.buttons[b], face),
        enabled = !off, sideways = true, dot = changed,
        onFocused = { if (it) onFocused(b.name) }, onClick = onClick)
}

private fun arrow(b: N64Button) = when (b) {
    N64Button.DUp, N64Button.CUp -> "↑"; N64Button.DDown, N64Button.CDown -> "↓"
    N64Button.DLeft, N64Button.CLeft -> "←"; else -> "→"
}

/** Face buttons are named with where they sit on gamepads; N64-style pads Joey64 knows show the plain names. */
private fun faceFor(dev: android.view.InputDevice?): AutoMap.FaceCodes? = when {
    dev == null -> AutoMap.FaceCodes.POSITION
    PadDevices.layouts(dev).isNotEmpty() -> null
    else -> PadDevices.face(dev)
}

private fun deviceName(d: DeviceChoice) = when (d) {
    DeviceChoice.BuiltIn -> "Built-in controls"
    DeviceChoice.None -> "None"
    is DeviceChoice.Pad -> d.name
}

private fun pakValue(p: Pak, auto: Pak?, setup: PlayerSetup? = null) = when {
    p == Pak.Auto && auto != null -> "Auto · ${auto.label}"
    // "Transfer Pak · Pokémon Gold (USA)" on the page (approved paks mockup screen 4a).
    p == Pak.Transfer && setup != null -> "Transfer Pak · " + (setup.gbGame?.let { GameBoyFiles.title(it.name) } ?: "No game")
    else -> p.label
}

/**
 * Whether Rumble strength can do anything for [setup]'s motor: Android sets the strength only on motors with amplitude
 * control; the others shake at full (the row says "Full only").
 */
private fun rumbleStrengthWorks(context: android.content.Context, dev: android.view.InputDevice?, setup: PlayerSetup): Boolean {
    val pad = if (setup.rumbleOn == RumbleOn.Device || dev == null || setup.device == DeviceChoice.BuiltIn) null
        else (if (android.os.Build.VERSION.SDK_INT >= 31) dev.vibratorManager.defaultVibrator else @Suppress("DEPRECATION") dev.vibrator)
            .takeIf { it.hasVibrator() }
    val sys = if (android.os.Build.VERSION.SDK_INT >= 31)
        (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
    val v = pad ?: sys.takeIf { it.hasVibrator() } ?: return true
    return v.hasAmplitudeControl()
}

private fun pakDetail(p: Pak) = when (p) {
    Pak.Auto -> "The Rumble Pak for games that use one, else the Controller Pak"
    Pak.Controller -> "For games that save to the pak"
    Pak.Rumble -> "Vibrates the controller, or this device"
    Pak.Transfer -> "Reads a Game Boy game, for the Pokémon Stadium games"
    Pak.None -> "Nothing in the back of the controller"
}

private fun clearRow(editor: ControlsEditor, p: Int, row: String) {
    val s = editor.shown.players[p]
    if (s.device == DeviceChoice.None) return
    when (row) {
        DPAD -> editor.set(p, N64Button.DPAD.fold(s) { a, b -> a.with(b, null) })
        CGROUP -> editor.set(p, N64Button.C.fold(s) { a, b -> a.with(b, null) })
        else -> N64Button.entries.firstOrNull { it.name == row }?.let { editor.set(p, s.with(it, null)) }
    }
}

private fun resetRow(editor: ControlsEditor, p: Int, row: String) {
    if (editor.shown.players[p].device == DeviceChoice.None) return
    when (row) {
        DPAD -> editor.resetGroup(p, N64Button.DPAD)
        CGROUP -> editor.resetGroup(p, N64Button.C)
        "Controller" -> {}
        else -> if (!row.startsWith("chip")) editor.resetRow(p, row)
    }
}

private fun clearHotkey(editor: ControlsEditor, row: String) {
    val a = HotkeyAction.entries.firstOrNull { "hk" + it.name == row } ?: return
    editor.setHotkeys(editor.hotkeys.withKey(a, null))
}

private fun resetHotkey(editor: ControlsEditor, row: String) {
    if (row == "hkButton") { editor.setHotkeys(editor.hotkeys.copy(button = KeyEvent.KEYCODE_BUTTON_SELECT)); return }
    val a = HotkeyAction.entries.firstOrNull { "hk" + it.name == row } ?: return
    val d = Hotkeys()
    // The default button and on-its-own; a hotkey that had that press goes to Not set.
    var h = editor.hotkeys.copy(alone = if (a.aloneByDefault) editor.hotkeys.alone + a else editor.hotkeys.alone - a).withKey(a, a.default)
    // Fast-forward and Quit game: the popup's other settings go back to theirs too.
    if (a == HotkeyAction.FastForward) h = h.copy(ffMode = d.ffMode, ffSpeed = d.ffSpeed)
    if (a == HotkeyAction.Quit) h = h.copy(quitTo = d.quitTo)
    editor.setHotkeys(h)
}

// ---- Hotkeys (mockups 6 and 7) ----

/** A button in a hotkey's name: the D-pad's directions short ("Select + Right"), the rest as the Controls page names them. */
fun hotkeyKey(code: Int) = when (code) {
    KeyEvent.KEYCODE_DPAD_UP -> "Up"; KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
    KeyEvent.KEYCODE_DPAD_LEFT -> "Left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
    else -> InputNames.key(code, null)
}

/** The Controls tab row's Hotkeys tab (after Player 1-4). */
private const val HOTKEYS_TAB = 4

@Composable
private fun HotkeysPage(editor: ControlsEditor, version: Int, focused: String, hints: List<Hint>, onHints: (List<Hint>) -> Unit,
                        onFocused: (String) -> Unit, onOpen: (Popup) -> Unit, onChange: (Target) -> Unit) {
    val c = JoeyTheme.colors
    @Suppress("UNUSED_EXPRESSION") version
    val h = editor.hotkeys
    // Fast-forward and Quit game open their own popups (their button and how they work); every other row opens HotkeyPopup.
    val popupRows = mapOf(HotkeyAction.FastForward to Popup.FastForward(), HotkeyAction.SlowMotion to Popup.SlowMotion(),
        HotkeyAction.Rewind to Popup.Rewind(), HotkeyAction.Quit to Popup.QuitGame())
    @Composable
    fun row(a: HotkeyAction) = JoeyRow(a.label, value = hotkeyValue(h, a) + if (a in popupRows) "  ›" else "", sideways = true,
        onFocused = { if (it) onFocused("hk" + a.name) }) {
        onOpen(popupRows[a] ?: Popup.Hotkey(a))
    }
    Column(Modifier.fillMaxSize()) {
    // One list with the panel beside it (approved 2026-11-11, Hotkeys included).
    val hkPanel = com.joeysretro.joey64.launcher.components.rememberListPanel()
    val hkInfo = if (focused == "hkButton") com.joeysretro.joey64.launcher.components.PanelInfo("Hotkey button",
            "Hold it with another button for a hotkey. Pressed alone it does nothing in the game while it's the hotkey button.",
            lines = listOf("Now" to hotkeyKey(h.button)))
        else HotkeyAction.entries.firstOrNull { "hk" + it.name == focused }?.let { a ->
            com.joeysretro.joey64.launcher.components.PanelInfo(a.label, hotkeyDescription(a) ?: "", lines = listOf("Button" to hotkeyValue(h, a)))
        } ?: com.joeysretro.joey64.launcher.components.PanelInfo("Hotkeys", "")
    com.joeysretro.joey64.launcher.components.ListPanel(hkPanel, hkInfo, hints, onHints, Modifier.weight(1f)) {
            SectionLabel("Hotkey button")
            JoeyRow("Hotkey button", Modifier.initialFocus(), value = hotkeyKey(h.button) + "  ›", sideways = true,
                onFocused = { if (it) onFocused("hkButton") }) { onChange(Target.HotkeyButton) }
            SectionLabel("States")
            for (a in listOf(HotkeyAction.Save, HotkeyAction.Load, HotkeyAction.NextSlot, HotkeyAction.PrevSlot, HotkeyAction.UndoLoad, HotkeyAction.UndoSave)) row(a)
            SectionLabel("Sound")
            for (a in listOf(HotkeyAction.VolumeUp, HotkeyAction.VolumeDown, HotkeyAction.Mute)) row(a)
            SectionLabel("Picture")
            for (a in listOf(HotkeyAction.UpscaleUp, HotkeyAction.UpscaleDown, HotkeyAction.NextShader, HotkeyAction.ShaderToggle)) row(a)
            SectionLabel("Game")
            for (a in listOf(HotkeyAction.PauseMenu, HotkeyAction.Pause, HotkeyAction.FrameAdvance, HotkeyAction.FastForward, HotkeyAction.SlowMotion, HotkeyAction.Rewind, HotkeyAction.Turbo, HotkeyAction.Screenshot, HotkeyAction.Stats,
                HotkeyAction.Achievements, HotkeyAction.GameShark, HotkeyAction.Reset, HotkeyAction.Quit)) row(a)
    }
    }
}

/** A hotkey's value on the page and in its popup: "Select + R1", just "Back" on its own, or Not set. */
internal fun hotkeyValue(h: Hotkeys, a: HotkeyAction): String {
    val k = h.keys[a] ?: return InputNames.NOT_SET
    return if (h.isAlone(a)) hotkeyKey(k) else hotkeyKey(h.button) + " + " + hotkeyKey(k)
}

/** The page's line for a focused hotkey (approved hotkeys mockup 2026-11-07 for Volume up and Upscale up). */
private fun hotkeyDescription(a: HotkeyAction): String? = when (a) {
    HotkeyAction.FastForward -> "Speeds the game up. Pick its button, Toggle, Hold or Adaptive, and how fast."
    HotkeyAction.Quit -> "Saves to the auto slot, then goes to the library or exits Joey64."
    HotkeyAction.SlowMotion -> "Slows the game down. Pick its button, Toggle or Hold, and how slow."
    HotkeyAction.Rewind -> "Hold to go back in time. Turn recording on, and pick how far back and how often a moment is kept."
    HotkeyAction.Stats -> "Steps through Off, Simple, Full and Custom. Settings › On screen sets the rest."
    HotkeyAction.VolumeUp -> "The game's sound 5% louder. Saved as Settings › Audio's Volume: the game's own if it has one, else All games'."
    HotkeyAction.VolumeDown -> "The game's sound 5% quieter. Saved as Settings › Audio's Volume: the game's own if it has one, else All games'."
    HotkeyAction.Mute -> "Turns the game's sound off, and on again. It's Settings › Audio's Mute, the same for every game."
    HotkeyAction.UpscaleUp -> "Draws the game one step sharper, up to 8x. For this play only, on parallel-RDP. Settings › Video keeps its own."
    HotkeyAction.UpscaleDown -> "Draws the game one step less sharp. For this play only, on parallel-RDP. Settings › Video keeps its own."
    HotkeyAction.Pause -> "Stops the game where it is, with no menu, until you press it again."
    HotkeyAction.FrameAdvance -> "Paused, each press runs the game one frame, without sound. Playing, it pauses."
    HotkeyAction.Achievements -> "Opens the pause menu's Achievements page. B goes back to the game."
    HotkeyAction.UndoLoad -> "Goes back to where you were just before the last load state, if it was the wrong slot."
    HotkeyAction.UndoSave -> "Puts back the slot the last save state wrote over, with its picture and date."
    HotkeyAction.Turbo -> "Turns turbo off for this play, and on again. Which buttons repeat is set in Controls › Turbo."
    HotkeyAction.NextShader -> "Steps through the shaders and your presets for this play. Settings › Video keeps its own."
    HotkeyAction.ShaderToggle -> "Turns the shader off for this play, and on again. Settings › Video keeps its own."
    else -> null
}

/** The popup's line under its title: what the hotkey does. */
private fun hotkeyPopupLine(a: HotkeyAction): String = when (a) {
    HotkeyAction.Save -> "Saves to the current slot."
    HotkeyAction.Load -> "Loads the current slot."
    HotkeyAction.NextSlot -> "The next save slot."
    HotkeyAction.PrevSlot -> "The previous save slot."
    HotkeyAction.VolumeUp -> "The game's sound 5% louder. Held, it keeps going."
    HotkeyAction.VolumeDown -> "The game's sound 5% quieter. Held, it keeps going."
    HotkeyAction.Mute -> "Turns the game's sound off, and on again."
    HotkeyAction.UpscaleUp -> "One step sharper, for this play only."
    HotkeyAction.UpscaleDown -> "One step less sharp, for this play only."
    HotkeyAction.PauseMenu -> "Opens the pause menu."
    HotkeyAction.Pause -> "Stops the game with no menu, until you press it again."
    HotkeyAction.FrameAdvance -> "Paused, one frame a press. Playing, it pauses."
    HotkeyAction.Screenshot -> "Saves a picture of the game."
    HotkeyAction.Stats -> "Steps through Off, Simple, Full and Custom."
    HotkeyAction.Achievements -> "Opens the Achievements page."
    HotkeyAction.GameShark -> "The GameShark's button, held for as long as you hold it."
    HotkeyAction.Reset -> "Starts the game over, as the console's Reset."
    HotkeyAction.UndoLoad -> "Back to just before the last load state."
    HotkeyAction.UndoSave -> "Puts back the slot the last save wrote over."
    HotkeyAction.Turbo -> "Turbo off for this play, and on again."
    HotkeyAction.NextShader -> "The next shader, for this play only."
    HotkeyAction.ShaderToggle -> "The shader off, and on again, for this play only."
    HotkeyAction.FastForward, HotkeyAction.SlowMotion, HotkeyAction.Rewind, HotkeyAction.Quit -> ""
}

/** "Needs the hotkey button" (approved hotkeys mockup 2026-11-07): A turns it On or Off. */
@Composable
private fun NeedsHotkeyRow(h: Hotkeys, a: HotkeyAction, start: Boolean, onRow: () -> Unit, onSet: (Hotkeys) -> Unit) {
    val alone = h.isAlone(a)
    JoeyRow("Needs the hotkey button", Modifier.initialFocus(start), value = if (alone) "Off" else "On",
        detail = if (alone) "Off: the button does it on its own" else "On: hold ${hotkeyKey(h.button)}, then press the button",
        onFocused = { if (it) onRow() }) { onSet(h.withAlone(a, !alone)) }
}

/** On its own on one of Player 1's N64 buttons: the game doesn't get that button while the hotkey has it. */
@Composable
private fun TakenFromPlayer1(h: Hotkeys, a: HotkeyAction, player1: PlayerSetup) {
    val k = h.keys[a] ?: return
    if (!h.isAlone(a)) return
    val b = N64Button.entries.firstOrNull { (player1.buttons[it] as? PadInput.Key)?.code == k }
    val name = when {
        b == null -> if (player1.stick.usesDPad && k in DPAD_KEYS) "the stick" else return
        b in N64Button.DPAD -> "D-pad ${b.label.lowercase()}"
        b in N64Button.C -> "C ${b.label.lowercase()}"
        else -> b.label
    }
    PopupWarnNote("${hotkeyKey(k)} is $name for Player 1. While ${a.label} has it on its own, the game doesn't get $name.")
}

/** Approved hotkeys mockup (2026-11-07): a hotkey's Button and whether it needs the hotkey button. */
@Composable
private fun HotkeyPopup(a: HotkeyAction, editor: ControlsEditor, version: Int, startRow: String, onDismiss: () -> Unit,
                        player1: PlayerSetup, onButton: () -> Unit, onSet: (Hotkeys) -> Unit) {
    @Suppress("UNUSED_EXPRESSION") version
    val h = editor.hotkeys
    var row by remember { mutableStateOf(startRow) }
    JoeyDialog(onDismiss = onDismiss, width = 420.dp, hints = listOf(Hint("A", "Select"), Hint("B", "Close"))) {
        Column {
            PopupTitle(a.label, hotkeyPopupLine(a))
            JoeyRow("Button", Modifier.initialFocus(startRow == "button"), value = (h.keys[a]?.let { hotkeyKey(it) } ?: InputNames.NOT_SET) + "  ›",
                onFocused = { if (it) row = "button" }, onClick = onButton)
            NeedsHotkeyRow(h, a, startRow == "alone", { row = "alone" }, onSet)
            TakenFromPlayer1(h, a, player1)
        }
    }
}

/** Approved fast-forward popup (2026-11-07): Button (set like the other hotkeys), Mode, Speed (Left / Right). */
@Composable
private fun FastForwardPopup(editor: ControlsEditor, version: Int, startRow: String, onDismiss: () -> Unit, player1: PlayerSetup,
                             onButton: () -> Unit, onMode: () -> Unit, onSet: (Hotkeys) -> Unit) {
    @Suppress("UNUSED_EXPRESSION") version
    val h = editor.hotkeys
    var row by remember { mutableStateOf(startRow) }
    val button = h.keys[HotkeyAction.FastForward]
    val buttonName = hotkeyValue(h, HotkeyAction.FastForward)
    JoeyDialog(onDismiss = onDismiss, width = 400.dp,
        hints = when (row) {
            "speed" -> listOf(Hint("◀▶", "Change"), Hint("B", "Close"))
            "button" -> listOf(Hint("A", "Change"), Hint("X", "Clear"), Hint("B", "Close"))
            else -> listOf(Hint("A", "Select"), Hint("B", "Close"))
        },
        onPadButton = { code -> if (code == KeyEvent.KEYCODE_BUTTON_X && row == "button") onSet(h.withKey(HotkeyAction.FastForward, null)) }) {
        Column {
            PopupTitle("Fast-forward")
            JoeyRow("Button", Modifier.initialFocus(startRow == "button"), value = buttonName, onFocused = { if (it) row = "button" }, onClick = onButton)
            NeedsHotkeyRow(h, HotkeyAction.FastForward, startRow == "alone", { row = "alone" }, onSet)
            JoeyRow("Mode", Modifier.initialFocus(startRow == "mode"), value = h.ffMode.label + "  ›", experimental = h.ffMode.experimental,
                onFocused = { if (it) row = "mode" }, onClick = onMode)
            JoeyRow("Speed", Modifier.initialFocus(startRow == "speed").onPreviewKeyEvent { e ->
                val n = e.nativeKeyEvent
                val step = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
                if (step == 0) return@onPreviewKeyEvent false
                if (n.action == KeyEvent.ACTION_DOWN) {
                    val i = (Hotkeys.FF_SPEEDS.indexOf(h.ffSpeed).takeIf { it >= 0 } ?: Hotkeys.FF_SPEEDS.lastIndex) + step
                    onSet(h.copy(ffSpeed = Hotkeys.FF_SPEEDS[i.coerceIn(0, Hotkeys.FF_SPEEDS.lastIndex)]))
                }
                true
            }, value = "◀ ${h.ffSpeed}% ▶", sideways = true, onFocused = { if (it) row = "speed" })
            if (h.ffMode == FastForwardMode.Adaptive && button != null && !Hotkeys.hasPressure(button))
                PopupInfoNote("${hotkeyKey(button)} can't sense how hard it's pressed, so Adaptive works like Hold.")
            if (row == "alone") TakenFromPlayer1(h, HotkeyAction.FastForward, player1)
            Spacer(Modifier.height(6.dp))
            when (row) {
                "speed" -> DescriptionLine("Speed", "The top speed. Adaptive goes from normal speed up to this as you press harder.")
                "mode" -> DescriptionLine("Mode", "Toggle, Hold or Adaptive. Adaptive is experimental.")
                "alone" -> {}
                else -> DescriptionLine("Button", if (h.isAlone(HotkeyAction.FastForward)) "On its own, without the hotkey button." else "Held with ${hotkeyKey(h.button)}, the hotkey button.")
            }
        }
    }
}

/** The Button row of the Slow motion and Rewind popups: X clears it, as Fast-forward's. */
private fun buttonHints(row: String, extra: List<Hint> = emptyList()) = when (row) {
    "button" -> listOf(Hint("A", "Change"), Hint("X", "Clear"), Hint("B", "Close"))
    else -> extra.ifEmpty { listOf(Hint("A", "Select"), Hint("B", "Close")) }
}

/** Approved Slow motion popup (2026-11-08): Button, Needs the hotkey button, Mode (Toggle or Hold), Speed 20-90%. */
@Composable
private fun SlowMotionPopup(editor: ControlsEditor, version: Int, startRow: String, onDismiss: () -> Unit, player1: PlayerSetup,
                            onButton: () -> Unit, onMode: () -> Unit, onSet: (Hotkeys) -> Unit) {
    @Suppress("UNUSED_EXPRESSION") version
    val h = editor.hotkeys
    var row by remember { mutableStateOf(startRow) }
    val a = HotkeyAction.SlowMotion
    JoeyDialog(onDismiss = onDismiss, width = 400.dp,
        hints = buttonHints(row, if (row == "speed") listOf(Hint("◀▶", "Change"), Hint("B", "Close")) else emptyList()),
        onPadButton = { code -> if (code == KeyEvent.KEYCODE_BUTTON_X && row == "button") onSet(h.withKey(a, null)) }) {
        Column {
            PopupTitle("Slow motion", "Slows the game down while you need it.")
            JoeyRow("Button", Modifier.initialFocus(startRow == "button"), value = hotkeyValue(h, a) + "  ›", onFocused = { if (it) row = "button" }, onClick = onButton)
            NeedsHotkeyRow(h, a, startRow == "alone", { row = "alone" }, onSet)
            JoeyRow("Mode", Modifier.initialFocus(startRow == "mode"), value = h.slowMode.label + "  ›", onFocused = { if (it) row = "mode" }, onClick = onMode)
            JoeyRow("Speed", Modifier.initialFocus(startRow == "speed").onPreviewKeyEvent { e ->
                val n = e.nativeKeyEvent
                val step = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
                if (step == 0) return@onPreviewKeyEvent false
                if (n.action == KeyEvent.ACTION_DOWN) {
                    val i = (Hotkeys.SLOW_SPEEDS.indexOf(h.slowSpeed).takeIf { it >= 0 } ?: Hotkeys.SLOW_SPEEDS.indexOf(Hotkeys.SLOW_SPEED_DEFAULT)) + step
                    onSet(h.copy(slowSpeed = Hotkeys.SLOW_SPEEDS[i.coerceIn(0, Hotkeys.SLOW_SPEEDS.lastIndex)]))
                }
                true
            }, value = "◀ ${h.slowSpeed}% ▶", sideways = true, onFocused = { if (it) row = "speed" })
            if (row == "alone") TakenFromPlayer1(h, a, player1)
            Spacer(Modifier.height(6.dp))
            when (row) {
                "speed" -> DescriptionLine("Speed", "How slow: ${h.slowSpeed}% is ${if (h.slowSpeed == 50) "half" else "${h.slowSpeed}% of full"} speed.")
                "mode" -> DescriptionLine("Mode", "Toggle or Hold.")
                "alone" -> {}
                else -> DescriptionLine("Button", if (h.isAlone(a)) "On its own, without the hotkey button." else "Held with ${hotkeyKey(h.button)}, the hotkey button.")
            }
        }
    }
}

/** Approved Rewind popup (2026-11-08): Button, Needs the hotkey button, Rewind (records while playing), How far back, Steps, and what it costs. */
@Composable
private fun RewindPopup(editor: ControlsEditor, version: Int, startRow: String, onDismiss: () -> Unit, player1: PlayerSetup,
                        onButton: () -> Unit, onBack: () -> Unit, onSteps: () -> Unit, onSet: (Hotkeys) -> Unit) {
    @Suppress("UNUSED_EXPRESSION") version
    val h = editor.hotkeys
    var row by remember { mutableStateOf(startRow) }
    val a = HotkeyAction.Rewind
    JoeyDialog(onDismiss = onDismiss, width = 420.dp, hints = buttonHints(row),
        onPadButton = { code -> if (code == KeyEvent.KEYCODE_BUTTON_X && row == "button") onSet(h.withKey(a, null)) }) {
        Column {
            PopupTitle("Rewind", "Hold the button to go back in time.")
            JoeyRow("Button", Modifier.initialFocus(startRow == "button"), value = hotkeyValue(h, a) + "  ›", onFocused = { if (it) row = "button" }, onClick = onButton)
            NeedsHotkeyRow(h, a, startRow == "alone", { row = "alone" }, onSet)
            JoeyRow("Rewind", Modifier.initialFocus(startRow == "on"), value = if (h.rewindOn) "On" else "Off",
                detail = if (h.rewindOn) "Records while you play" else "Not recording",
                onFocused = { if (it) row = "on" }) { onSet(h.copy(rewindOn = !h.rewindOn)) }
            JoeyRow("How far back", Modifier.initialFocus(startRow == "back"), value = Hotkeys.rewindSecondsLabel(h.rewindSeconds) + "  ›",
                onFocused = { if (it) row = "back" }, onClick = onBack)
            JoeyRow("Steps", Modifier.initialFocus(startRow == "steps"), value = Hotkeys.rewindEveryLabel(h.rewindEvery) + "  ›",
                onFocused = { if (it) row = "steps" }, onClick = onSteps)
            PopupInfoNote("About ${Hotkeys.rewindMb(h.rewindSeconds, h.rewindEvery)} MB of memory for ${Hotkeys.rewindSecondsLabel(h.rewindSeconds)} at " +
                "${Hotkeys.rewindEveryLabel(h.rewindEvery).lowercase()}, and about 3 ms of work for each moment kept (Thor).")
            if (row == "alone") TakenFromPlayer1(h, a, player1)
            Spacer(Modifier.height(6.dp))
            when (row) {
                "on" -> DescriptionLine("Rewind", "Off by default: recording takes memory and a little work every frame.")
                "back" -> DescriptionLine("How far back", "The oldest moment rewind can go back to.")
                "steps" -> DescriptionLine("Steps", "How often a moment is kept.")
                "alone" -> {}
                else -> DescriptionLine("Button", if (h.isAlone(a)) "On its own, without the hotkey button." else "Held with ${hotkeyKey(h.button)}, the hotkey button.")
            }
        }
    }
}

/** Approved Quit game popup (2026-11-07): Button, and Quit to (the library, or out of Joey64). */
@Composable
private fun QuitGamePopup(editor: ControlsEditor, version: Int, startRow: String, onDismiss: () -> Unit, player1: PlayerSetup,
                          onButton: () -> Unit, onQuitTo: () -> Unit, onSet: (Hotkeys) -> Unit) {
    @Suppress("UNUSED_EXPRESSION") version
    val h = editor.hotkeys
    var row by remember { mutableStateOf(startRow) }
    val buttonName = hotkeyValue(h, HotkeyAction.Quit)
    JoeyDialog(onDismiss = onDismiss, width = 400.dp,
        hints = if (row == "button") listOf(Hint("A", "Change"), Hint("X", "Clear"), Hint("B", "Close")) else listOf(Hint("A", "Select"), Hint("B", "Close")),
        onPadButton = { code -> if (code == KeyEvent.KEYCODE_BUTTON_X && row == "button") onSet(h.withKey(HotkeyAction.Quit, null)) }) {
        Column {
            PopupTitle("Quit game")
            JoeyRow("Button", Modifier.initialFocus(startRow == "button"), value = buttonName, onFocused = { if (it) row = "button" }, onClick = onButton)
            NeedsHotkeyRow(h, HotkeyAction.Quit, startRow == "alone", { row = "alone" }, onSet)
            JoeyRow("Quit to", Modifier.initialFocus(startRow == "to"), value = h.quitTo.label + "  ›", onFocused = { if (it) row = "to" }, onClick = onQuitTo)
            if (row == "alone") TakenFromPlayer1(h, HotkeyAction.Quit, player1)
            Spacer(Modifier.height(6.dp))
            when (row) {
                "to" -> DescriptionLine("Quit to", "Where you land after quitting. The game is saved to its auto slot either way.")
                "alone" -> {}
                else -> DescriptionLine("Button", (if (h.isAlone(HotkeyAction.Quit)) "On its own, without the hotkey button." else "Held with ${hotkeyKey(h.button)}, the hotkey button.") +
                    " The pause menu's Quit follows Quit to.")
            }
        }
    }
}

// ---- popups ----

internal data class Choice(val label: String, val detail: String?, val on: Boolean, val value: String? = null, val experimental: Boolean = false,
                            /** False: greyed, A does nothing (Transfer Pak on All games). */ val enabled: Boolean = true)

@Composable
private fun Tick() {
    Icon(painterResource(R.drawable.ic_tick), contentDescription = "Chosen", tint = JoeyTheme.colors.gold, modifier = Modifier.size(18.dp))
}

@Composable
internal fun ChoicePopup(title: String, subtitle: String?, choices: List<Choice>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle(title, subtitle)
            choices.forEachIndexed { i, ch ->
                JoeyRow(ch.label, Modifier.initialFocus(ch.on), value = ch.value, detail = ch.detail, experimental = ch.experimental,
                    enabled = ch.enabled, trailing = if (ch.on) { _ -> Tick() } else null) { if (ch.enabled) onPick(i) }
            }
        }
    }
}

/** Mockup 2: one controller per player; one another player has says so, and picking it swaps the two. */
@Composable
private fun ControllerPopup(player: Int, devices: List<DeviceChoice>, pads: List<android.view.InputDevice>, onDismiss: () -> Unit,
                            onPick: (DeviceChoice, android.view.InputDevice?) -> Unit) {
    val mine = devices[player]
    fun takenBy(d: DeviceChoice) = devices.indices.firstOrNull { it != player && devices[it] == d }?.let { "Player ${it + 1}" }
    val entries = buildList {
        val builtIn = pads.firstOrNull { PadDevices.isBuiltIn(it) }
        if (builtIn != null || mine == DeviceChoice.BuiltIn || devices.contains(DeviceChoice.BuiltIn))
            add(Triple(DeviceChoice.BuiltIn as DeviceChoice, builtIn, "This device's own buttons and sticks"))
        for (d in pads) if (!PadDevices.isBuiltIn(d)) add(Triple(PadDevices.choiceFor(d), d, if (d.isExternal) "Connected" else null))
        // Controllers players have that aren't connected now.
        for (d in devices) if (d is DeviceChoice.Pad && none { it.first == d }) add(Triple(d as DeviceChoice, null, "Not connected"))
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            PopupTitle("Player ${player + 1} controller", "The buttons and sticks Player ${player + 1} plays with.")
            for ((choice, d, detail) in entries) {
                val on = choice == mine
                JoeyRow(deviceName(choice), Modifier.initialFocus(on), detail = detail, value = takenBy(choice),
                    trailing = if (on) { _ -> Tick() } else null) { onPick(choice, d) }
            }
            PopupDivider()
            JoeyRow("None", Modifier.initialFocus(mine == DeviceChoice.None), detail = "Player ${player + 1} isn't plugged in",
                trailing = if (mine == DeviceChoice.None) { _ -> Tick() } else null) { onPick(DeviceChoice.None, null) }
        }
    }
}

/** Mockup 4: asks first, since it replaces changes made by hand. */
@Composable
private fun AutoMapConfirm(player: Int, name: String, dev: android.view.InputDevice?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val known = dev?.let { PadDevices.layouts(it) }.orEmpty()
    val p = "Player ${player + 1}"
    val text = if (known.isNotEmpty())
        "Sets $p's buttons from Joey64's list for this controller (${known.first().name}${if (known.size > 1) ", or another that shares its ID" else ""}). Buttons you changed for $p go back to these."
    else "Sets $p's buttons by where they sit on this controller: A on the bottom face button, B on the left one, Z on the left trigger, " +
        "the C buttons on the right stick. Buttons you changed for $p go back to these."
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Cancel")), width = 470.dp) {
        Column {
            PopupTitle("Auto map $name?")
            PopupNote(text)
            ButtonRow(Modifier.padding(horizontal = 10.dp).padding(top = 12.dp, bottom = 2.dp)) {
                JoeyButton("Auto map", primary = true, modifier = Modifier.initialFocus(), onClick = onConfirm)
                JoeyButton("Cancel", primary = false, onClick = onDismiss)
            }
        }
    }
}

/** The 5 s bar: runs down, then [onTimeout]. */
@Composable
internal fun TimeBar(onTimeout: () -> Unit) {
    val c = JoeyTheme.colors
    val left = remember { Animatable(1f) }
    val timeout by rememberUpdatedState(onTimeout)
    LaunchedEffect(Unit) {
        left.animateTo(0f, tween(5_000, easing = LinearEasing))
        timeout()
    }
    Box(Modifier.fillMaxWidth().height(4.dp).background(c.separator, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(left.value).fillMaxHeight().background(c.gold, RoundedCornerShape(2.dp)))
    }
}

@Composable
internal fun PressBody(title: String, line: String, note: String) {
    val c = JoeyTheme.colors
    PopupTitle(title)
    Text(line, style = JoeyTheme.type.body, color = c.text, modifier = Modifier.padding(horizontal = 10.dp).padding(top = 6.dp, bottom = 12.dp))
}

/** Mockup 4b: one press of A tells which layout a shared ID is. The bar running out, or a button no layout has, maps by position. */
@Composable
private fun OnePressPopup(name: String, onResult: (Int?) -> Unit) {
    var done by remember { mutableStateOf(false) }
    fun finish(code: Int?) { if (!done) { done = true; onResult(code) } }
    JoeyDialog(onDismiss = { finish(null) }, hints = listOf(Hint("", "Any button you press is used")), dismissible = false, width = 400.dp) {
        CaptureInput(active = !done) { input, _ -> if (input is PadInput.Key) finish(input.code) }
        Column(Modifier.padding(horizontal = 8.dp)) {
            PressBody("Auto map $name", "Press A on the controller", "")
            Box(Modifier.padding(horizontal = 10.dp)) { TimeBar { finish(null) } }
            PopupNote("Several controllers share this one's ID. The button you press tells Joey64 which one it is.")
        }
    }
}

/** Mockups 5 and 7: press the button to use; the bar runs out in 5 s and the row keeps what it had. Any button counts, even B. */
@Composable
private fun RemapPopup(target: Target, setup: PlayerSetup, editor: ControlsEditor, face: AutoMap.FaceCodes?, onResult: (PadInput?, Int?) -> Unit) {
    var done by remember { mutableStateOf(false) }
    fun finish(input: PadInput?, code: Int?) { if (!done) { done = true; onResult(input, code) } }
    val h = editor.hotkeys
    val (title, line, note) = when (target) {
        is Target.Button -> {
            val name = when (target.b) {
                in N64Button.DPAD -> "D-pad ${target.b.label.lowercase()}"
                in N64Button.C -> "C ${target.b.label.lowercase()}"
                else -> target.b.label
            }
            val now = setup.buttons[target.b]
            Triple(name, "Press a button", if (now == null) "Stays not set if you don't press anything" else "Keeps ${InputNames.of(now, face)} if you don't press anything")
        }
        Target.HotkeyButton -> Triple("Hotkey button", "Press the button to hold for hotkeys",
            "Keeps ${hotkeyKey(h.button)} if you don't press anything. The game won't see the button you pick, so choose one it doesn't need.")
        is Target.Hotkey -> {
            val now = h.keys[target.action]
            Triple(target.action.label, if (h.isAlone(target.action)) "Press a button" else "Press the button to use with ${hotkeyKey(h.button)}",
                if (now == null) "Stays not set if you don't press anything" else "Keeps ${hotkeyKey(now)} if you don't press anything")
        }
    }
    JoeyDialog(onDismiss = { finish(null, null) }, hints = listOf(Hint("", "Any button you press is used, even B")), dismissible = false, width = 380.dp) {
        CaptureInput(active = !done, keysOnly = target !is Target.Button) { input, _ ->
            finish(input, (input as? PadInput.Key)?.code)
        }
        Column(Modifier.padding(horizontal = 8.dp)) {
            PressBody(title, line, note)
            Box(Modifier.padding(horizontal = 10.dp)) { TimeBar { finish(null, null) } }
            PopupNote(note)
        }
    }
}
