package com.joeysretro.joey64.launcher.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.controls.ControlsEditor
import com.joeysretro.joey64.controls.Hotkeys
import com.joeysretro.joey64.controls.InputNames
import com.joeysretro.joey64.controls.LATCHABLE
import com.joeysretro.joey64.controls.Macro
import com.joeysretro.joey64.controls.MacroStep
import com.joeysretro.joey64.controls.N64Button
import com.joeysretro.joey64.controls.Profile
import com.joeysretro.joey64.controls.StickDir
import com.joeysretro.joey64.controls.TURBO_BUTTONS
import com.joeysretro.joey64.controls.TURBO_SPEEDS
import com.joeysretro.joey64.controls.fullLabel
import com.joeysretro.joey64.launcher.components.ButtonRow
import com.joeysretro.joey64.launcher.components.ControllerTextField
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyButton
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupNote
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.PopupWarnNote
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.controls.CaptureInput
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/*
 * Controls extras' popups (approved mockup, Joey 2026-11-08, "Approve as drawn"; scratchpad
 * build_ctlextras.py / ctlextras_mock.png): Tap to hold (screen 2), Macros (4-6) and Profiles (7-9).
 * Each is opened from the Controls page and keeps its own steps; B steps back.
 */

/** Left / Right on a row: [onStep] with -1 or 1, on the down edge. */
private fun Modifier.sideways(enabled: Boolean = true, onStep: (Int) -> Unit) = onPreviewKeyEvent { e ->
    val n = e.nativeKeyEvent
    val step = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
    if (step == 0 || !enabled) return@onPreviewKeyEvent false
    if (n.action == KeyEvent.ACTION_DOWN) onStep(step)
    true
}

/** The Macros popup's button name: "R3", "Hotkey + Y", Not set. */
internal fun macroKeyLabel(m: Macro): String {
    val k = m.key ?: return InputNames.NOT_SET
    return if (m.withHotkey) "Hotkey + " + hotkeyKey(k) else hotkeyKey(k)
}

// ---- Tap to hold (screen 2) ----

@Composable
internal fun TapToHoldPopup(player: Int, latch: Set<N64Button>, onSet: (Set<N64Button>) -> Unit, onDismiss: () -> Unit) {
    var focused by remember { mutableStateOf(LATCHABLE.firstOrNull { it in latch } ?: N64Button.A) }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Tick"), Hint("B", "Close")), width = 560.dp) {
        Column {
            PopupTitle("Player ${player + 1} tap to hold",
                "A ticked button stays held after one tap, until it's tapped again. For holding Z to aim, R to shield, A to run.")
            Row {
                for (col in listOf(LATCHABLE.take(5), LATCHABLE.drop(5))) Column(Modifier.weight(1f)) {
                    for (b in col) {
                        val on = b in latch
                        JoeyRow(b.fullLabel(), Modifier.initialFocus(b == focused), leading = { _ -> TickBox(on) }, sideways = true,
                            onFocused = { if (it) focused = b }) { onSet(if (on) latch - b else latch + b) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            val n = focused.fullLabel()
            DescriptionLine(n, "Tap $n and the game sees it held; tap it again to let go. The other buttons work as always.")
        }
    }
}

// ---- Turbo (approved mockup 2026-11-08, scratchpad build_turbo.py, screen 2) ----

@Composable
internal fun TurboPopup(player: Int, turbo: Set<N64Button>, speed: Int, latch: Set<N64Button>,
                        onSet: (Set<N64Button>, Int) -> Unit, onDismiss: () -> Unit) {
    // null: the Speed row.
    var focused by remember { mutableStateOf<N64Button?>(TURBO_BUTTONS.firstOrNull { it in turbo && it !in latch } ?: TURBO_BUTTONS.firstOrNull { it !in latch }) }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Tick"), Hint("◀▶", "Change"), Hint("B", "Close")), width = 560.dp) {
        Column {
            PopupTitle("Player ${player + 1} turbo",
                "A ticked button presses itself again and again while you hold it. For mashing A through text, firing with Z or B.")
            Row {
                for (col in listOf(TURBO_BUTTONS.take(5), TURBO_BUTTONS.drop(5))) Column(Modifier.weight(1f)) {
                    for (b in col) {
                        val held = b in latch
                        val on = b in turbo && !held
                        JoeyRow(b.fullLabel(), Modifier.initialFocus(b == focused), value = if (held) "Tap to hold" else null,
                            enabled = !held, leading = { _ -> Box(Modifier.alpha(if (held) 0.35f else 1f)) { TickBox(on) } }, sideways = true,
                            onFocused = { if (it) focused = b }) { if (!held) onSet(if (on) turbo - b else turbo + b, speed) }
                    }
                }
            }
            PopupDivider()
            val step: (Int) -> Unit = { by ->
                val i = (TURBO_SPEEDS.indexOf(speed).takeIf { it >= 0 } ?: TURBO_SPEEDS.indexOf(com.joeysretro.joey64.controls.DEFAULT_TURBO_SPEED)) + by
                onSet(turbo, TURBO_SPEEDS[i.coerceIn(0, TURBO_SPEEDS.lastIndex)])
            }
            // Every button on Tap to hold: nothing above can take the focus, so the popup starts here (code audit 4, D-L2).
            JoeyRow("Speed", Modifier.initialFocus(focused == null).sideways(onStep = step), detail = "How many presses a second",
                value = "◀ $speed a second ▶", sideways = true,
                onFocused = { if (it) focused = null }) { }
            Spacer(Modifier.height(6.dp))
            val f = focused
            if (f == null) DescriptionLine("Speed", "5, 8, 10, 15 or 20 presses a second, with Left and Right. 10 is the default.")
            else {
                val n = f.fullLabel()
                DescriptionLine(n, "Hold $n and the game sees it pressed and let go $speed times a second. Let go and it stops. Unticked buttons work as always.")
            }
        }
    }
}

// ---- Macros (screens 4-6) ----

private sealed interface MacroPage {
    data class List(val focus: Int = 0) : MacroPage
    data class Remove(val index: Int) : MacroPage
    data class Edit(val index: Int, val row: String = "name") : MacroPage
    data class Name(val index: Int) : MacroPage
    data class Button(val index: Int) : MacroPage
    data class Held(val index: Int) : MacroPage
    data class Step(val index: Int, val step: Int) : MacroPage
}

/** Player [player]'s macros: the list, a macro, a step. [onSet] saves the player's macros as they change. */
@Composable
internal fun MacrosPopup(player: Int, macros: List<Macro>, hotkeys: Hotkeys, onSet: (List<Macro>) -> Unit, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf<MacroPage>(MacroPage.List()) }
    fun setMacro(i: Int, m: Macro) = onSet(macros.toMutableList().also { it[i] = m })
    when (val p = page) {
        is MacroPage.List -> MacroList(player, macros, p.focus, onDismiss,
            onEdit = { i -> page = MacroPage.Edit(i) },
            onAdd = {
                onSet(macros + Macro(Macro.newName(macros)))
                page = MacroPage.Edit(macros.size)
            },
            onRemove = { i -> page = MacroPage.Remove(i) })
        is MacroPage.Remove -> {
            val m = macros.getOrNull(p.index) ?: run { page = MacroPage.List(); return }
            ResetTabPopup("Remove ${m.name}?", "${macroKeyLabel(m)} goes back to what it does in the game.", "Keep it", "Remove", { page = MacroPage.List(p.index) }) {
                onSet(macros.filterIndexed { i, _ -> i != p.index }); page = MacroPage.List((p.index - 1).coerceAtLeast(0))
            }
        }
        is MacroPage.Edit -> {
            val m = macros.getOrNull(p.index) ?: run { page = MacroPage.List(); return }
            MacroEditor(m, hotkeys, p.row, {
                    // A macro left with nothing set goes (a new one: back on Add a macro, where it was made).
                    if (m.blank) { onSet(macros.filterIndexed { i, _ -> i != p.index }); page = MacroPage.List(if (p.index >= macros.lastIndex) -1 else p.index) }
                    else page = MacroPage.List(p.index) },
                onName = { page = MacroPage.Name(p.index) }, onButton = { page = MacroPage.Button(p.index) },
                onHeld = { page = MacroPage.Held(p.index) },
                onStep = { s -> page = MacroPage.Step(p.index, s) },
                onAddStep = { setMacro(p.index, m.copy(steps = m.steps + MacroStep())); page = MacroPage.Step(p.index, m.steps.size) },
                onSet = { setMacro(p.index, it) })
        }
        is MacroPage.Name -> {
            val m = macros.getOrNull(p.index) ?: run { page = MacroPage.List(); return }
            NameEntryPopup("Name", m.name, null, taken = { n -> macros.anyIndexed { i, o -> i != p.index && o.name.equals(n, ignoreCase = true) } },
                onDismiss = { page = MacroPage.Edit(p.index, "name") }) { n -> setMacro(p.index, m.copy(name = n)); page = MacroPage.Edit(p.index, "name") }
        }
        is MacroPage.Button -> {
            val m = macros.getOrNull(p.index) ?: run { page = MacroPage.List(); return }
            MacroButtonPopup(m, hotkeys) { code, withHotkey ->
                if (code != null) setMacro(p.index, m.copy(key = code, withHotkey = withHotkey))
                page = MacroPage.Edit(p.index, "button")
            }
        }
        is MacroPage.Held -> {
            val m = macros.getOrNull(p.index) ?: run { page = MacroPage.List(); return }
            ChoicePopup("While held", null, listOf(
                Choice("Once", "Plays it once per press", !m.repeat),
                Choice("Repeat", "Plays it again and again while held", m.repeat),
            ), { page = MacroPage.Edit(p.index, "held") }) { i -> setMacro(p.index, m.copy(repeat = i == 1)); page = MacroPage.Edit(p.index, "held") }
        }
        is MacroPage.Step -> {
            val m = macros.getOrNull(p.index)
            val s = m?.steps?.getOrNull(p.step) ?: run { page = MacroPage.Edit(p.index); return }
            StepPopup(p.step, s, { page = MacroPage.Edit(p.index, "s${p.step}") }) { ns ->
                setMacro(p.index, m.copy(steps = m.steps.toMutableList().also { it[p.step] = ns }))
            }
        }
    }
}

private inline fun <T> List<T>.anyIndexed(pred: (Int, T) -> Boolean): Boolean { forEachIndexed { i, t -> if (pred(i, t)) return true }; return false }

@Composable
private fun MacroList(player: Int, macros: List<Macro>, focus: Int, onDismiss: () -> Unit, onEdit: (Int) -> Unit, onAdd: () -> Unit, onRemove: (Int) -> Unit) {
    var focused by remember { mutableIntStateOf(if (macros.isEmpty() || focus < 0) -1 else focus.coerceIn(0, macros.lastIndex)) }
    JoeyDialog(onDismiss = onDismiss, width = 520.dp,
        hints = if (focused >= 0) listOf(Hint("A", "Edit"), Hint("X", "Remove"), Hint("B", "Close")) else listOf(Hint("A", "Select"), Hint("B", "Close")),
        onPadButton = { code -> if (code == KeyEvent.KEYCODE_BUTTON_X && focused >= 0) onRemove(focused) }) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            PopupTitle("Player ${player + 1} macros", "One button plays a run of N64 presses.")
            macros.forEachIndexed { i, m ->
                JoeyRow(m.name, Modifier.initialFocus(i == focused), detail = m.summary(), value = macroKeyLabel(m), sideways = true,
                    onFocused = { if (it) focused = i }) { onEdit(i) }
            }
            if (macros.size < Macro.MAX) JoeyRow("Add a macro", Modifier.initialFocus(focused < 0), value = "›", sideways = true,
                onFocused = { if (it) focused = -1 }, onClick = onAdd)
            else PopupNote("${Macro.MAX} is the most a player can have.")
        }
    }
}

@Composable
private fun MacroEditor(m: Macro, hotkeys: Hotkeys, startRow: String, onDismiss: () -> Unit, onName: () -> Unit, onButton: () -> Unit,
                        onHeld: () -> Unit, onStep: (Int) -> Unit, onAddStep: () -> Unit, onSet: (Macro) -> Unit) {
    var row by remember { mutableStateOf(startRow) }
    val c = JoeyTheme.colors
    val stepRow = row.startsWith("s") && row.drop(1).toIntOrNull() != null
    val stepIdx = if (stepRow) row.drop(1).toInt() else -1
    fun time(i: Int, d: Int) {
        val s = m.steps.getOrNull(i) ?: return
        onSet(m.copy(steps = m.steps.toMutableList().also { it[i] = s.copy(frames = (s.frames + d).coerceIn(MacroStep.MIN_FRAMES, MacroStep.MAX_FRAMES)) }))
    }
    JoeyDialog(onDismiss = onDismiss, width = 520.dp,
        hints = if (stepRow) listOf(Hint("A", "Edit"), Hint("◀▶", "Change"), Hint("X", "Remove"), Hint("B", "Close"))
            else listOf(Hint("A", "Edit"), Hint("B", "Close")),
        onPadButton = { code ->
            if (code == KeyEvent.KEYCODE_BUTTON_X && stepIdx in m.steps.indices) {
                onSet(m.copy(steps = m.steps.filterIndexed { i, _ -> i != stepIdx }))
                if (stepIdx > m.steps.lastIndex - 1) row = if (m.steps.size > 1) "s${m.steps.size - 2}" else "add"
            }
        }) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            PopupTitle(m.name)
            JoeyRow("Name", Modifier.initialFocus(startRow == "name"), value = m.name + "  ›", sideways = true,
                onFocused = { if (it) row = "name" }, onClick = onName)
            JoeyRow("Button", Modifier.initialFocus(startRow == "button"), detail = "The controller button that plays it", value = macroKeyLabel(m) + "  ›",
                sideways = true, onFocused = { if (it) row = "button" }, onClick = onButton)
            JoeyRow("While held", Modifier.initialFocus(startRow == "held"), value = (if (m.repeat) "Repeat" else "Once") + "  ›", sideways = true,
                onFocused = { if (it) row = "held" }, onClick = onHeld)
            macroClash(m, hotkeys)?.let { PopupWarnNote(it) }
            SectionLabel("Steps")
            m.steps.forEachIndexed { i, s ->
                val on = row == "s$i"
                JoeyRow(s.label(), Modifier.initialFocus(startRow == "s$i").sideways { d -> time(i, d) },
                    value = "◀ ${s.framesLabel()} ▶", sideways = true,
                    leading = { _ -> Text("${i + 1}", style = JoeyTheme.type.small, color = c.textDim, modifier = Modifier.width(22.dp)) },
                    onFocused = { if (it) row = "s$i" }) { onStep(i) }
            }
            JoeyRow("Add a step", Modifier.initialFocus(startRow == "add" || (startRow.startsWith("s") && m.steps.isEmpty())), value = "›", sideways = true,
                onFocused = { if (it) row = "add" }, onClick = onAddStep)
        }
    }
}

/** A macro's button that a hotkey already has (the hotkey wins), or the hotkey button itself. */
private fun macroClash(m: Macro, h: Hotkeys): String? {
    val k = m.key ?: return null
    if (!m.withHotkey && k == h.button) return "${hotkeyKey(k)} is the hotkey button, so it can't play a macro on its own."
    val a = h.actionFor(k, alone = !m.withHotkey) ?: return null
    return "${macroKeyLabel(m)} is ${a.label}'s button too, and the hotkey wins. Pick another, or change Hotkeys."
}

/** Screen 5's Button: press the one to use, or the hotkey button and then a button. 5 s, as the remap popup. */
@Composable
private fun MacroButtonPopup(m: Macro, h: Hotkeys, onResult: (Int?, Boolean) -> Unit) {
    var done by remember { mutableStateOf(false) }
    var withHotkey by remember { mutableStateOf(false) }
    fun finish(code: Int?, hk: Boolean) { if (!done) { done = true; onResult(code, hk) } }
    val now = m.key
    JoeyDialog(onDismiss = { finish(null, false) }, hints = listOf(Hint("", "Any button you press is used, even B")), dismissible = false, width = 400.dp) {
        CaptureInput(active = !done, keysOnly = true) { input, _ ->
            val code = (input as? com.joeysretro.joey64.controls.PadInput.Key)?.code ?: return@CaptureInput
            if (!withHotkey && code == h.button) withHotkey = true else finish(code, withHotkey)
        }
        Column(Modifier.padding(horizontal = 8.dp)) {
            PressBody("Button", if (withHotkey) "Now press the button to use with ${hotkeyKey(h.button)}" else "Press a button, or ${hotkeyKey(h.button)} and then a button", "")
            // A new bar once the hotkey button is pressed.
            if (withHotkey) Box(Modifier.padding(horizontal = 10.dp)) { TimeBar { finish(null, false) } }
            else Box(Modifier.padding(horizontal = 10.dp)) { TimeBar { finish(null, false) } }
            PopupNote(if (now == null) "Stays not set if you don't press anything" else "Keeps ${macroKeyLabel(m)} if you don't press anything")
        }
    }
}

/** Screen 6: what's held in a step (N64 buttons and stick directions; none is a wait), and its time. */
@Composable
private fun StepPopup(index: Int, s: MacroStep, onDismiss: () -> Unit, onSet: (MacroStep) -> Unit) {
    var row by remember { mutableStateOf("A") }
    val cols = listOf(
        listOf(N64Button.A, N64Button.B, N64Button.Z, N64Button.Start, N64Button.L),
        listOf(N64Button.R, N64Button.CUp, N64Button.CDown, N64Button.CLeft, N64Button.CRight),
    )
    fun frames(d: Int) = onSet(s.copy(frames = (s.frames + d).coerceIn(MacroStep.MIN_FRAMES, MacroStep.MAX_FRAMES)))
    JoeyDialog(onDismiss = onDismiss, width = 680.dp,
        hints = if (row == "time") listOf(Hint("◀▶", "Change"), Hint("B", "Close")) else listOf(Hint("A", "Tick"), Hint("B", "Close"))) {
        Column {
            PopupTitle("Step ${index + 1}")
            Row {
                for (col in cols) Column(Modifier.weight(1f)) {
                    for (b in col) {
                        val on = b in s.buttons
                        JoeyRow(b.fullLabel(), Modifier.initialFocus(b == N64Button.A), leading = { _ -> TickBox(on) }, sideways = true,
                            onFocused = { if (it) row = b.name }) { onSet(s.copy(buttons = if (on) s.buttons - b else s.buttons + b)) }
                    }
                }
                Column(Modifier.weight(1f)) {
                    for (d in StickDir.entries) {
                        val on = d in s.stick
                        JoeyRow(d.label, leading = { _ -> TickBox(on) }, sideways = true,
                            onFocused = { if (it) row = d.name }) { onSet(s.copy(stick = if (on) s.stick - d else s.stick + d)) }
                    }
                }
            }
            JoeyRow("Time", Modifier.sideways { frames(it) }, detail = s.msLabel(), value = "◀ ${s.framesLabel()} ▶", sideways = true,
                onFocused = { if (it) row = "time" })
            Spacer(Modifier.height(4.dp))
            DescriptionLine("Step ${index + 1}", "Ticked buttons and stick directions are held together for its time; nothing ticked is a wait. A frame is 1/60 s.")
        }
    }
}

// ---- a name ----

/** A name field with the keyboard up (as New collection); [taken] says when another has it. */
@Composable
internal fun NameEntryPopup(title: String, initial: String, note: String?, taken: (String) -> Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var clash by remember { mutableStateOf<String?>(null) }
    val c = JoeyTheme.colors
    fun save() {
        val n = name.trim()
        if (n.isEmpty() || n == initial) { onDismiss(); return }
        if (taken(n)) clash = n else onSave(n)
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Type"), Hint("B", "Close")), width = 460.dp, atTop = true) {
        Column {
            PopupTitle(title)
            ControllerTextField(name, { name = it; clash = null }, "Name", Modifier.initialFocus().padding(horizontal = 4.dp),
                startTyping = true, onSubmit = ::save)
            val line = clash?.let { "There's already one called $it" } ?: note
            if (line != null) Text(line, style = JoeyTheme.type.small, color = if (clash != null) c.error else c.textDim,
                modifier = Modifier.padding(start = JoeyTheme.spacing.rowPadH, top = 6.dp))
        }
    }
}

// ---- Profiles (screens 7-9) ----

private sealed interface ProfilePage {
    data object List : ProfilePage
    data object Save : ProfilePage
    data class Replace(val name: String) : ProfilePage
    data class Load(val name: String) : ProfilePage
    data class Options(val name: String) : ProfilePage
    data class Rename(val name: String) : ProfilePage
    data class Delete(val name: String) : ProfilePage
}

/**
 * Profiles: Save Player N as a profile, then each saved one (A loads it, asking first; Start: Rename,
 * Delete). [gameTitle] is the game in the chip (loading changes that game only), null on All games.
 */
@Composable
internal fun ProfilesPopup(player: Int, deviceName: String, gameTitle: String?, editor: ControlsEditor, version: Int,
                           onChanged: () -> Unit, onLoaded: (String) -> Unit, onDismiss: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") version
    var page by remember { mutableStateOf<ProfilePage>(ProfilePage.List) }
    var listVersion by remember { mutableIntStateOf(0) }
    val profiles = remember(listVersion) { editor.profiles() }
    val who = "Player ${player + 1}"
    fun saveAs(name: String) {
        editor.saveProfile(player, name, deviceName, System.currentTimeMillis())
        listVersion++; onChanged(); page = ProfilePage.List
    }
    when (val p = page) {
        ProfilePage.List -> ProfileList(who, profiles, onDismiss, onSave = { page = ProfilePage.Save },
            onLoad = { page = ProfilePage.Load(it) }, onOptions = { page = ProfilePage.Options(it) })
        ProfilePage.Save -> SaveProfilePopup(who, deviceName, { page = ProfilePage.List }) { n ->
            val same = profiles.firstOrNull { it.name.equals(n, ignoreCase = true) }
            if (same != null) page = ProfilePage.Replace(n) else saveAs(n)
        }
        is ProfilePage.Replace -> ResetTabPopup("Replace ${p.name}?", "The saved ${p.name} becomes $who's setup now.", "Keep it", "Replace",
            { page = ProfilePage.Save }) { saveAs(p.name) }
        is ProfilePage.Load -> {
            val prof = profiles.firstOrNull { it.name == p.name } ?: run { page = ProfilePage.List; return }
            LoadProfilePopup(prof.name, who, gameTitle, { page = ProfilePage.List }) {
                editor.loadProfile(player, prof); onChanged(); onLoaded(prof.name)
            }
        }
        is ProfilePage.Options -> ChoicePopup(p.name, null, listOf(Choice("Rename", null, false), Choice("Delete", null, false)), { page = ProfilePage.List }) { i ->
            page = if (i == 0) ProfilePage.Rename(p.name) else ProfilePage.Delete(p.name)
        }
        is ProfilePage.Rename -> NameEntryPopup("Rename ${p.name}", p.name, null,
            taken = { n -> profiles.any { it.name != p.name && it.name.equals(n, ignoreCase = true) } },
            onDismiss = { page = ProfilePage.List }) { n -> editor.renameProfile(p.name, n); listVersion++; page = ProfilePage.List }
        is ProfilePage.Delete -> ResetTabPopup("Delete ${p.name}?", "Players who loaded it keep their controls.", "Keep it", "Delete",
            { page = ProfilePage.List }) { editor.removeProfile(p.name); listVersion++; page = ProfilePage.List }
    }
}

private val SAVED_DAY = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)
private val SAVED_DAY_YEAR = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH)

/** "8BitDo pad · saved Nov 6" (the year too when it isn't this one). */
internal fun profileLine(p: Profile, now: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val d = java.time.Instant.ofEpochMilli(p.savedAt).atZone(zone)
    val sameYear = d.year == java.time.Instant.ofEpochMilli(now).atZone(zone).year
    return listOfNotNull(p.deviceName.ifBlank { null }, "saved " + d.format(if (sameYear) SAVED_DAY else SAVED_DAY_YEAR)).joinToString(" · ")
}

@Composable
private fun ProfileList(who: String, profiles: List<Profile>, onDismiss: () -> Unit, onSave: () -> Unit, onLoad: (String) -> Unit, onOptions: (String) -> Unit) {
    var focused by remember { mutableStateOf<String?>(null) }
    JoeyDialog(onDismiss = onDismiss, width = 520.dp,
        hints = if (focused != null) listOf(Hint("A", "Select"), Hint("Start", "Options"), Hint("B", "Close")) else listOf(Hint("A", "Select"), Hint("B", "Close")),
        onOptions = { focused?.let(onOptions) }) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            PopupTitle("Profiles")
            JoeyRow("Save $who as a profile", Modifier.initialFocus(), value = "›", sideways = true,
                onFocused = { if (it) focused = null }, onClick = onSave)
            if (profiles.isNotEmpty()) PopupDivider()
            for (p in profiles) JoeyRow(p.name, detail = profileLine(p), value = "Load", sideways = true,
                onFocused = { if (it) focused = p.name }) { onLoad(p.name) }
        }
    }
}

@Composable
private fun SaveProfilePopup(who: String, deviceName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(deviceName) }
    fun save() { val n = name.trim(); if (n.isNotEmpty()) onSave(n) }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Cancel")), width = 460.dp, atTop = true) {
        Column {
            PopupTitle("Save $who as a profile", "Buttons, stick, Stick feel, tap to hold and macros.")
            ControllerTextField(name, { name = it }, "Name", Modifier.initialFocus().padding(horizontal = 4.dp), startTyping = true, onSubmit = ::save)
            ButtonRow(Modifier.padding(horizontal = 10.dp).padding(top = 12.dp, bottom = 2.dp)) {
                JoeyButton("Cancel", primary = false, onClick = onDismiss)
                JoeyButton("Save", primary = true, onClick = ::save)
            }
        }
    }
}

@Composable
private fun LoadProfilePopup(name: String, who: String, gameTitle: String?, onDismiss: () -> Unit, onLoad: () -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Cancel")), width = 470.dp) {
        Column {
            PopupTitle("Load $name for $who?")
            PopupNote("$who's buttons, stick, tap to hold and macros (${gameTitle ?: "All games"}) become the profile's. The controller and pak stay.")
            ButtonRow(Modifier.padding(horizontal = 10.dp).padding(top = 12.dp, bottom = 2.dp)) {
                JoeyButton("Cancel", primary = false, onClick = onDismiss)
                JoeyButton("Load", primary = true, modifier = Modifier.initialFocus(), onClick = onLoad)
            }
        }
    }
}

