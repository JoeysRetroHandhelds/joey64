package com.joeysretro.joey64.launcher.screens

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
import com.joeysretro.joey64.controls.PakDb
import com.joeysretro.joey64.controls.PlayerSetup
import com.joeysretro.joey64.controls.QuitTo
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

private sealed interface Popup {
    data object Controller : Popup
    data object AutoConfirm : Popup
    data class OnePress(val layouts: List<AutoMap.Layout>) : Popup
    /** [back]: the popup to return to once a button is picked (Fast-forward's and Quit game's Button). */
    data class Remap(val target: Target, val back: Popup? = null) : Popup
    /** The Hotkeys page's Fast-forward and Quit game rows (approved popups, 2026-11-07); [row] has focus on return. */
    data class FastForward(val row: String = "button") : Popup
    data object FfMode : Popup
    data class QuitGame(val row: String = "button") : Popup
    data object QuitTo : Popup
    data object Stick : Popup
    data object PakChoice : Popup
    data object Games : Popup
    data object PickGame : Popup
    data object Diagram : Popup
}

/** What a remap popup sets. */
private sealed interface Target {
    data class Button(val b: N64Button) : Target
    data object HotkeyButton : Target
    data class Hotkey(val action: HotkeyAction) : Target
}

private const val DPAD = "DPad"
private const val CGROUP = "C"
private val DEAD_ZONES = listOf(0, 5, 10, 15, 20, 25, 30)

@Composable
private fun ControlsContent(game: String?, gameTitle: String?, inGame: Boolean, sink: ControlsSink, onHints: (List<Hint>) -> Unit) {
    val context = LocalContext.current
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    val store = remember { ControlsStore(context) }
    LaunchedEffect(Unit) { if (!store.hasAll()) store.saveAll(PadDevices.firstSet()) }
    var target by remember { mutableStateOf(game) }
    var targetTitle by remember { mutableStateOf(gameTitle) }
    val editor = remember(target) {
        ControlsEditor(store, target) { setup -> PadDevices.autoSetup(PadDevices.deviceFor(setup.device), setup.device, keep = null) }
    }
    var version by remember { mutableIntStateOf(0) }
    fun edit(block: () -> Unit) { block(); version++ }
    var player by remember { mutableIntStateOf(0) }
    var hotkeysOpen by remember { mutableStateOf(false) }
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
    sink.onControl = { control ->
        val (row, p, hk) = current
        when (control) {
            Control.TabPrev -> { if (!hk) { player = (p + 3) % 4; open = emptySet() }; true }
            Control.TabNext -> { if (!hk) { player = (p + 1) % 4; open = emptySet() }; true }
            Control.Extra -> { edit { if (hk) clearHotkey(editor, row) else clearRow(editor, p, row) }; true }
            Control.Search -> { edit { if (hk) resetHotkey(editor, row) else resetRow(editor, p, row) }; true }
            else -> false
        }
    }
    sink.onBack = { if (hotkeysOpen) { hotkeysOpen = false; focused = "chipHotkeys"; true } else false }

    // ---- hints ----
    val backLabel = if (inGame) "Back to the pause menu" else "Back"
    LaunchedEffect(focused, hotkeysOpen, target, off) {
        onHints(when {
            hotkeysOpen -> listOf(Hint("A", if (focused == "hkFastForward" || focused == "hkQuit") "Open" else "Change"), Hint("X", "Clear"),
                Hint("Y", "Reset to default"), Hint("B", "Back"))
            focused.startsWith("chip") -> listOf(Hint("A", "Select"), Hint("L/R", "Player"), Hint("B", backLabel))
            focused == "DeadZone" -> listOf(Hint("◀▶", "Adjust"), Hint("Y", if (target == null) "Reset to default" else "Use All games"),
                Hint("L/R", "Player"), Hint("B", backLabel))
            focused == "Controller" -> listOf(Hint("A", "Change"), Hint("L/R", "Player"), Hint("B", backLabel))
            off -> listOf(Hint("L/R", "Player"), Hint("B", backLabel))
            else -> listOf(Hint("A", "Change"), Hint("X", "Clear"), Hint("Y", if (target == null) "Reset to default" else "Use All games"),
                Hint("L/R", "Player"), Hint("B", backLabel))
        })
    }

    val landing = LocalFocusLanding.current
    LaunchedEffect(hotkeysOpen) { landing?.arm() }

    Column(Modifier.fillMaxSize().padding(horizontal = s.margin).padding(top = 14.dp, bottom = 6.dp)) {
        if (hotkeysOpen) {
            HotkeysPage(editor, version, focused, onFocused = { focused = it }, onOpen = { popup = it }) { popup = Popup.Remap(it) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Controls", style = JoeyTheme.type.pageTitle, color = c.text, modifier = Modifier.weight(1f))
                PageChip((targetTitle ?: "All games") + "  ›", R.drawable.ic_sliders, on = target != null,
                    onFocused = { if (it) focused = "chipGame" }) { popup = Popup.Games }
                Spacer(Modifier.width(8.dp))
                PageChip("Hotkeys", R.drawable.ic_keyboard, modifier = Modifier.initialFocus(focused == "chipHotkeys"),
                    onFocused = { if (it) focused = "chipHotkeys" }) { hotkeysOpen = true; focused = "hkButton" }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0..3) TabChip("Player ${i + 1}", i == player)
            }
            Row(Modifier.padding(top = 6.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PageChip("Auto map", R.drawable.ic_wand, onFocused = { if (it) focused = "chipAuto" }) { if (!off) popup = Popup.AutoConfirm }
                PageChip("View controller", R.drawable.ic_eye, onFocused = { if (it) focused = "chipView" }) { popup = Popup.Diagram }
            }
            Row(Modifier.weight(1f).fillMaxWidth().focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    SectionLabel("Controller")
                    JoeyRow("Controller", Modifier.initialFocus(focused == "Controller"), value = deviceName(setup.device) + "  ›", sideways = true,
                        onFocused = { if (it) focused = "Controller" }) { popup = Popup.Controller }
                    SectionLabel("Buttons")
                    for (b in N64Button.BUTTONS) BindingRow(b, b.label, setup, face, off, b.name in changed, { focused = it }) { popup = Popup.Remap(Target.Button(b)) }
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
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
                        onFocused = { if (it) focused = "Stick" }) { popup = Popup.Stick }
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
                    SectionLabel("Pak")
                    JoeyRow("Pak", value = pakValue(setup.pak, autoPak) + "  ›", enabled = !off, sideways = true,
                        dot = "Pak" in changed,
                        onFocused = { if (it) focused = "Pak" }) { popup = Popup.PakChoice }
                }
            }
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
                    is Target.Hotkey -> keyCode?.let { editor.setHotkeys(editor.hotkeys.copy(keys = editor.hotkeys.keys + (t.action to it))) }
                }
            }
            popup = p.back
        }
        is Popup.FastForward -> FastForwardPopup(editor, version, p.row, close,
            onButton = { popup = Popup.Remap(Target.Hotkey(HotkeyAction.FastForward), Popup.FastForward("button")) },
            onMode = { popup = Popup.FfMode }) { h -> edit { editor.setHotkeys(h) } }
        Popup.FfMode -> ChoicePopup("Mode", null,
            FastForwardMode.entries.map { Choice(it.label, it.detail, it == editor.hotkeys.ffMode) }, { popup = Popup.FastForward("mode") }) { i ->
            edit { editor.setHotkeys(editor.hotkeys.copy(ffMode = FastForwardMode.entries[i])) }; popup = Popup.FastForward("mode")
        }
        is Popup.QuitGame -> QuitGamePopup(editor, version, p.row, close,
            onButton = { popup = Popup.Remap(Target.Hotkey(HotkeyAction.Quit), Popup.QuitGame("button")) },
            onQuitTo = { popup = Popup.QuitTo }) { h -> edit { editor.setHotkeys(h) } }
        Popup.QuitTo -> ChoicePopup("Quit to", null,
            QuitTo.entries.map { Choice(it.label, it.detail, it == editor.hotkeys.quitTo) }, { popup = Popup.QuitGame("to") }) { i ->
            edit { editor.setHotkeys(editor.hotkeys.copy(quitTo = QuitTo.entries[i])) }; popup = Popup.QuitGame("to")
        }
        Popup.Stick -> ChoicePopup("Player ${player + 1} stick", "What moves the N64 stick.",
            StickSource.entries.map { Choice(it.label, if (it == StickSource.DPad) "Full tilt only" else null, it == setup.stick) }, close) { i ->
            edit { editor.set(player, setup.copy(stick = StickSource.entries[i])) }; close()
        }
        Popup.PakChoice -> ChoicePopup("Player ${player + 1} pak", "What's in the back of the controller.",
            Pak.entries.map { Choice(pakValue(it, autoPak), pakDetail(it), it == setup.pak) }, close) { i ->
            edit { editor.set(player, setup.copy(pak = Pak.entries[i])) }; close()
        }
        Popup.Games -> GamesForPopup("Controls for", "A game's own controls are used instead of All games when it starts.", target,
            gamesWithOwn = { editor.gamesWithOwn() }, changeCount = { editor.changeCount(it) }, removeLabel = "Remove the game's controls",
            onDismiss = close, onPick = { save, title -> target = save; targetTitle = title; close() },
            onPickGame = { popup = Popup.PickGame }, onRemoved = { save -> edit { editor.removeGame(save) } })
        Popup.PickGame -> PickGamePopup("Its controls start as a copy of All games.", { popup = Popup.Games }) { save, title -> target = save; targetTitle = title; close() }
        Popup.Diagram -> JoeyDialog(onDismiss = close, hints = listOf(Hint("B", "Close")), width = 760.dp) {
            ControllerDiagram(player, deviceName(setup.device), setup, face)
        }
    }
}

private const val TAG = "Joey64Controls"

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

private fun pakValue(p: Pak, auto: Pak?) = if (p == Pak.Auto && auto != null) "Auto · ${auto.label}" else p.label

private fun pakDetail(p: Pak) = when (p) {
    Pak.Auto -> "The Rumble Pak for games that use one, else the Controller Pak"
    Pak.Controller -> "For games that save to the pak"
    Pak.Rumble -> "Vibrates the controller, or this device"
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
    editor.setHotkeys(editor.hotkeys.copy(keys = editor.hotkeys.keys + (a to null)))
}

private fun resetHotkey(editor: ControlsEditor, row: String) {
    if (row == "hkButton") { editor.setHotkeys(editor.hotkeys.copy(button = KeyEvent.KEYCODE_BUTTON_SELECT)); return }
    val a = HotkeyAction.entries.firstOrNull { "hk" + it.name == row } ?: return
    val d = Hotkeys()
    var h = editor.hotkeys.copy(keys = editor.hotkeys.keys + (a to a.default))
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

@Composable
private fun HotkeysPage(editor: ControlsEditor, version: Int, focused: String, onFocused: (String) -> Unit, onOpen: (Popup) -> Unit,
                        onChange: (Target) -> Unit) {
    val c = JoeyTheme.colors
    @Suppress("UNUSED_EXPRESSION") version
    val h = editor.hotkeys
    fun value(a: HotkeyAction): String {
        val k = h.keys[a] ?: return InputNames.NOT_SET
        return if (a.alone) hotkeyKey(k) else hotkeyKey(h.button) + " + " + hotkeyKey(k)
    }
    // Fast-forward and Quit game open their own popups (their button and how they work).
    val popupRows = mapOf(HotkeyAction.FastForward to Popup.FastForward(), HotkeyAction.Quit to Popup.QuitGame())
    @Composable
    fun row(a: HotkeyAction) = JoeyRow(a.label, value = value(a) + if (a in popupRows) "  ›" else "", sideways = true,
        onFocused = { if (it) onFocused("hk" + a.name) }) {
        popupRows[a]?.let(onOpen) ?: onChange(Target.Hotkey(a))
    }
    Column(Modifier.fillMaxSize()) {
    Text("Hotkeys", style = JoeyTheme.type.pageTitle, color = c.text)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f)) {
            SectionLabel("Hotkey button")
            JoeyRow("Hotkey button", Modifier.initialFocus(), value = hotkeyKey(h.button) + "  ›", sideways = true,
                onFocused = { if (it) onFocused("hkButton") }) { onChange(Target.HotkeyButton) }
            SectionLabel("States")
            for (a in listOf(HotkeyAction.Save, HotkeyAction.Load, HotkeyAction.NextSlot, HotkeyAction.PrevSlot)) row(a)
        }
        Column(Modifier.weight(1f)) {
            SectionLabel("Game")
            for (a in listOf(HotkeyAction.PauseMenu, HotkeyAction.FastForward, HotkeyAction.Screenshot, HotkeyAction.GameShark, HotkeyAction.Reset, HotkeyAction.Quit)) row(a)
        }
    }
    Spacer(Modifier.weight(1f))
    when (focused) {
        "hkFastForward" -> DescriptionLine("Fast-forward", "Speeds the game up. Pick its button, Toggle, Hold or Adaptive, and how fast.")
        "hkQuit" -> DescriptionLine("Quit game", "Saves to the auto slot, then goes to the library or exits Joey64.")
    }
    }
}

/** Approved fast-forward popup (2026-11-07): Button (set like the other hotkeys), Mode, Speed (Left / Right). */
@Composable
private fun FastForwardPopup(editor: ControlsEditor, version: Int, startRow: String, onDismiss: () -> Unit,
                             onButton: () -> Unit, onMode: () -> Unit, onSet: (Hotkeys) -> Unit) {
    @Suppress("UNUSED_EXPRESSION") version
    val h = editor.hotkeys
    var row by remember { mutableStateOf(startRow) }
    val button = h.keys[HotkeyAction.FastForward]
    val buttonName = button?.let { hotkeyKey(h.button) + " + " + hotkeyKey(it) } ?: InputNames.NOT_SET
    JoeyDialog(onDismiss = onDismiss, width = 400.dp,
        hints = when (row) {
            "speed" -> listOf(Hint("◀▶", "Speed"), Hint("B", "Close"))
            "button" -> listOf(Hint("A", "Change"), Hint("X", "Clear"), Hint("B", "Close"))
            else -> listOf(Hint("A", "Select"), Hint("B", "Close"))
        },
        onPadButton = { code -> if (code == KeyEvent.KEYCODE_BUTTON_X && row == "button") onSet(h.copy(keys = h.keys + (HotkeyAction.FastForward to null))) }) {
        Column {
            PopupTitle("Fast-forward")
            JoeyRow("Button", Modifier.initialFocus(startRow == "button"), value = buttonName, onFocused = { if (it) row = "button" }, onClick = onButton)
            JoeyRow("Mode", Modifier.initialFocus(startRow == "mode"), value = h.ffMode.label + "  ›", onFocused = { if (it) row = "mode" }, onClick = onMode)
            JoeyRow("Speed", Modifier.initialFocus(startRow == "speed").onPreviewKeyEvent { e ->
                val n = e.nativeKeyEvent
                val step = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
                if (step == 0) return@onPreviewKeyEvent false
                if (n.action == KeyEvent.ACTION_DOWN) {
                    val i = (Hotkeys.FF_SPEEDS.indexOf(h.ffSpeed).takeIf { it >= 0 } ?: Hotkeys.FF_SPEEDS.lastIndex) + step
                    onSet(h.copy(ffSpeed = Hotkeys.FF_SPEEDS[i.coerceIn(0, Hotkeys.FF_SPEEDS.lastIndex)]))
                }
                true
            }, value = if (row == "speed") "◀ ${h.ffSpeed}% ▶" else "${h.ffSpeed}%", sideways = true, onFocused = { if (it) row = "speed" })
            if (h.ffMode == FastForwardMode.Adaptive && button != null && !Hotkeys.hasPressure(button))
                PopupInfoNote("${hotkeyKey(button)} can't sense how hard it's pressed, so Adaptive works like Hold.")
            Spacer(Modifier.height(6.dp))
            when (row) {
                "speed" -> DescriptionLine("Speed", "The top speed. Adaptive goes from normal speed up to this as you press harder.")
                "mode" -> DescriptionLine("Mode", "Toggle, Hold or Adaptive.")
                else -> DescriptionLine("Button", "Held with ${hotkeyKey(h.button)}, the hotkey button.")
            }
        }
    }
}

/** Approved Quit game popup (2026-11-07): Button, and Quit to (the library, or out of Joey64). */
@Composable
private fun QuitGamePopup(editor: ControlsEditor, version: Int, startRow: String, onDismiss: () -> Unit,
                          onButton: () -> Unit, onQuitTo: () -> Unit, onSet: (Hotkeys) -> Unit) {
    @Suppress("UNUSED_EXPRESSION") version
    val h = editor.hotkeys
    var row by remember { mutableStateOf(startRow) }
    val buttonName = h.keys[HotkeyAction.Quit]?.let { hotkeyKey(h.button) + " + " + hotkeyKey(it) } ?: InputNames.NOT_SET
    JoeyDialog(onDismiss = onDismiss, width = 400.dp,
        hints = if (row == "button") listOf(Hint("A", "Change"), Hint("X", "Clear"), Hint("B", "Close")) else listOf(Hint("A", "Select"), Hint("B", "Close")),
        onPadButton = { code -> if (code == KeyEvent.KEYCODE_BUTTON_X && row == "button") onSet(h.copy(keys = h.keys + (HotkeyAction.Quit to null))) }) {
        Column {
            PopupTitle("Quit game")
            JoeyRow("Button", Modifier.initialFocus(startRow == "button"), value = buttonName, onFocused = { if (it) row = "button" }, onClick = onButton)
            JoeyRow("Quit to", Modifier.initialFocus(startRow == "to"), value = h.quitTo.label + "  ›", onFocused = { if (it) row = "to" }, onClick = onQuitTo)
            Spacer(Modifier.height(6.dp))
            if (row == "to") DescriptionLine("Quit to", "Where you land after quitting. The game is saved to its auto slot either way.")
            else DescriptionLine("Button", "Held with ${hotkeyKey(h.button)}, the hotkey button. The pause menu's Quit follows Quit to.")
        }
    }
}

// ---- popups ----

private data class Choice(val label: String, val detail: String?, val on: Boolean, val value: String? = null)

@Composable
private fun Tick() {
    Icon(painterResource(R.drawable.ic_tick), contentDescription = "Chosen", tint = JoeyTheme.colors.gold, modifier = Modifier.size(18.dp))
}

@Composable
private fun ChoicePopup(title: String, subtitle: String?, choices: List<Choice>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Back")), width = 470.dp) {
        Column {
            PopupTitle(title, subtitle)
            choices.forEachIndexed { i, ch ->
                JoeyRow(ch.label, Modifier.initialFocus(ch.on), value = ch.value, detail = ch.detail,
                    trailing = if (ch.on) { _ -> Tick() } else null) { onPick(i) }
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
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Back")), width = 470.dp) {
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
private fun TimeBar(onTimeout: () -> Unit) {
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
private fun PressBody(title: String, line: String, note: String) {
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
            Triple(target.action.label, if (target.action.alone) "Press a button" else "Press the button to use with ${hotkeyKey(h.button)}",
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
