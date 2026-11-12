package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.emulation.EmuAuto
import com.joeysretro.joey64.emulation.EmuChoices
import com.joeysretro.joey64.emulation.EmuKey
import com.joeysretro.joey64.emulation.EmuSettings
import com.joeysretro.joey64.emulation.EmuStore
import com.joeysretro.joey64.emulation.ExpansionPak
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.states.GameStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings › Emulation (approved mockup, 2026-11-08: scratchpad build_emu.py). Left, Console: the
 * Expansion Pak, set for one game only (greyed on All games, and for a game that needs it). Right,
 * Advanced: Overclock, CPU core, Thermal fallback. For one game ([target]) a row set for it has the
 * dot and Y puts it back. Performance mode was dropped (Joey 2026-11-08: measured, no difference on the Thor).
 */
@Composable
fun EmulationTab(target: String?, onHints: (List<Hint>) -> Unit, onControl: (((Control) -> Boolean)?) -> Unit, modifier: Modifier = Modifier,
                 /** Game settings' changed row to open at. */
                 start: EmuKey? = null) {
    val context = LocalContext.current
    val store = remember { EmuStore(context) }
    var version by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") version
    val all = remember(version) { store.all() }
    val own = remember(version, target) { target?.let { store.game(it) } ?: emptyMap() }
    val e = EmuSettings.resolve(all, own)
    // The focused row: an EmuKey's name, or one of the Speed group's ids (SPEED_*).
    var focused by remember { mutableStateOf(start?.name ?: EmuKey.ExpansionPak.name) }
    var popup by remember { mutableStateOf<EmuKey?>(null) }
    var speedPopup by remember { mutableStateOf<String?>(null) }
    // The Speed group (approved emuspeed_mock.png, Joey 2026-11-11): the same settings as Controls › Hotkeys'
    // Fast-forward, Slow motion and Rewind popups, in hotkeys.json; the same for every game.
    val controls = remember { com.joeysretro.joey64.controls.ControlsStore(context) }
    var hk by remember { mutableStateOf(controls.hotkeys()) }
    fun setHk(f: (com.joeysretro.joey64.controls.Hotkeys) -> com.joeysretro.joey64.controls.Hotkeys) {
        val h = f(controls.hotkeys()); controls.saveHotkeys(h); hk = h
    }
    val game = target != null
    // The game's header code, for Auto's pick and the Expansion Pak's needs list.
    val code by produceState<String?>(null, target) {
        val t = target ?: return@produceState
        value = withContext(Dispatchers.IO) {
            try { LibraryDb.get(context).gameWithFile(t, GameStates::saveNameOf)?.headerId }
            catch (x: Exception) { JLog.e("Joey64Emu", "can't look up $t: $x"); null }
        }
    }
    val needsPak = ExpansionPak.needed(code)
    val smoother = remember { context.getSharedPreferences("device", android.content.Context.MODE_PRIVATE).let { p ->
        if (p.contains("smoother")) p.getBoolean("smoother", false) else null } }

    // From the store, not this composition's maps: the Y handler and popups can hold an older composition's
    // functions, which wrote the tab as it was then and undid the changes since (code audit 3, M26).
    fun set(k: EmuKey, value: String) {
        if (target == null) store.saveAll(store.all() + (k to value)) else store.saveGame(target, (store.game(target) ?: emptyMap()) + (k to value))
        version++
    }
    fun reset(k: EmuKey) {
        if (target == null) store.saveAll(store.all() - k) else store.saveGame(target, (store.game(target) ?: emptyMap()) - k)
        version++
    }
    // Greyed: the Expansion Pak on All games, or for a game that needs it.
    // Thermal fallback, now in Speed, is the same for every game like the rest of the group (emuspeed_mock screen 4).
    fun fixed(k: EmuKey) = (k == EmuKey.ExpansionPak && (!game || needsPak)) || (k == EmuKey.Thermal && game)
    // No dot on a greyed row: a game that needs the pak keeps it on whatever its file says.
    fun dot(k: EmuKey) = game && k in own && !fixed(k)
    fun autoGives(k: EmuKey): String? = when (k) {
        EmuKey.CpuCore -> if (game) "Auto gives " + EmuChoices.label(k, EmuAuto.cpuCore(code)) else null
        EmuKey.Overclock -> if (game) EmuAuto.overclock(code, smoother)?.let { "Auto gives " + EmuChoices.label(k, it) + " on this device" } else null
        else -> null
    }

    fun keyOf(id: String) = EmuKey.entries.firstOrNull { it.name == id }
    fun fixedRow(id: String) = keyOf(id)?.let { fixed(it) } ?: game
    val defaults = com.joeysretro.joey64.controls.Hotkeys()
    /** Y on a Speed row: back to its default (they have no All games / game split). */
    fun resetSpeed(id: String) = setHk { h -> when (id) {
        SPEED_FF -> h.copy(ffSpeed = defaults.ffSpeed); SPEED_FF_MODE -> h.copy(ffMode = defaults.ffMode)
        SPEED_SLOW -> h.copy(slowSpeed = defaults.slowSpeed); SPEED_SLOW_MODE -> h.copy(slowMode = defaults.slowMode)
        SPEED_RW -> h.copy(rewindOn = defaults.rewindOn); SPEED_RW_BACK -> h.copy(rewindSeconds = defaults.rewindSeconds); else -> h } }

    // The handler is set once per target; it calls this composition's Y (with the game's needs list as loaded since)
    // through rememberUpdatedState.
    val onY by rememberUpdatedState {
        if (popup != null || speedPopup != null || fixedRow(focused)) false
        else { keyOf(focused)?.let { reset(it) } ?: resetSpeed(focused); true }
    }
    LaunchedEffect(target) {
        onControl { ctl -> if (ctl == Control.Search) onY() else false }
    }
    val y = Hint("Y", if (target == null || keyOf(focused) == null) "Reset to default" else "Use All games")
    val hints = when {
        fixedRow(focused) -> listOf(Hint("B", "Back"))
        focused == SPEED_FF || focused == SPEED_SLOW -> listOf(Hint("◀▶", "Change"), y, Hint("B", "Back"))
        else -> listOf(Hint("A", "Change"), y, Hint("B", "Back"))
    }

    // One list with the panel beside it (approved 2026-11-11).
    val panel = com.joeysretro.joey64.launcher.components.rememberListPanel()
    val allE = EmuSettings.resolve(all, null)
    val fk = keyOf(focused)
    val sameForAll = "The same for every game. Pick All games in the chip above to change it."
    val info = if (fk != null) {
        val help = when {
            fk == EmuKey.ExpansionPak && !game -> "On for every game. To turn it off for one game, pick that game in the chip above."
            fk == EmuKey.ExpansionPak && needsPak -> "This game needs the Expansion Pak, so it's always on."
            fk == EmuKey.Thermal && game -> sameForAll
            dot(fk) -> "Changed for this game. All games uses ${EmuChoices.label(fk, allE[fk])}."
            else -> listOfNotNull(describe(fk), autoGives(fk)?.let { "$it." }).joinToString(" ")
        }
        if (fixed(fk)) com.joeysretro.joey64.launcher.components.PanelInfo(title(fk), help)
        else EmuChoices.of(fk).let { chs -> com.joeysretro.joey64.launcher.components.PanelInfo(title(fk), help,
            chs.map { com.joeysretro.joey64.launcher.components.PanelChoice(it.label, it.detail, it.value == e[fk]) }) { i -> set(fk, chs[i].value) } }
    } else speedInfo(focused, hk, game, sameForAll) { f -> setHk(f) }

    com.joeysretro.joey64.launcher.components.ListPanel(panel, info, hints, onHints, modifier) {
        @Composable
        fun row(k: EmuKey, label: String, value: String = EmuChoices.label(k, e[k])) =
            JoeyRow(label, Modifier.initialFocus(focused == k.name), value = value, sideways = true, enabled = !fixed(k),
                dot = dot(k), onFocused = { if (it) focused = k.name }) { if (!fixed(k) && !panel.enter()) popup = k }
        @Composable
        fun speedRow(id: String, label: String, value: String) =
            JoeyRow(label, Modifier.initialFocus(focused == id), value = value, sideways = true, enabled = !game,
                onFocused = { if (it) focused = id }) {
                if (!game) {
                    if (id == SPEED_RW) setHk { it.copy(rewindOn = !it.rewindOn) } else if (!panel.enter()) speedPopup = id
                }
            }
        SectionLabel("Console")
        row(EmuKey.ExpansionPak, "Expansion Pak", when {
            // All games: on for every game (Joey 2026-11-11: "On" rather than "Auto on"), greyed: it's set per game.
            !game -> "On"
            needsPak -> "Needed by this game"
            else -> EmuChoices.label(EmuKey.ExpansionPak, e[EmuKey.ExpansionPak])
        })
        SectionLabel("Speed")
        val h = hk
        StepRow("Fast-forward speed", if (game) SAME else "${h.ffSpeed}%", com.joeysretro.joey64.controls.Hotkeys.FF_SPEEDS, h.ffSpeed, focused == SPEED_FF,
            first = focused == SPEED_FF, enabled = !game, onFocused = { if (it) focused = SPEED_FF }) { v -> setHk { it.copy(ffSpeed = v) } }
        speedRow(SPEED_FF_MODE, "Fast-forward mode", if (game) SAME else h.ffMode.label + "  ›")
        StepRow("Slow motion speed", if (game) SAME else "${h.slowSpeed}%", com.joeysretro.joey64.controls.Hotkeys.SLOW_SPEEDS, h.slowSpeed, focused == SPEED_SLOW,
            first = focused == SPEED_SLOW, enabled = !game, onFocused = { if (it) focused = SPEED_SLOW }) { v -> setHk { it.copy(slowSpeed = v) } }
        speedRow(SPEED_SLOW_MODE, "Slow motion mode", if (game) SAME else h.slowMode.label + "  ›")
        speedRow(SPEED_RW, "Rewind", if (game) SAME else if (h.rewindOn) "On" else "Off")
        speedRow(SPEED_RW_BACK, "Rewind: how far back", com.joeysretro.joey64.controls.Hotkeys.rewindSecondsLabel(h.rewindSeconds).let { if (game) SAME else "$it  ›" })
        // Thermal fallback moved here from Advanced: it only changes the fast-forward speed (emuspeed_mock screen 4).
        row(EmuKey.Thermal, "Thermal fallback", if (game) SAME else EmuChoices.label(EmuKey.Thermal, e[EmuKey.Thermal]))
        SectionLabel("Advanced")
        row(EmuKey.Overclock, "Overclock")
        row(EmuKey.CpuCore, "CPU core")
    }

    popup?.let { k ->
        EmuChoicePopup(k, e[k], autoGives(k), onDismiss = { popup = null }) { set(k, it); popup = null }
    }
    // The narrow screens' popups for the Speed group's choice rows (no panel there).
    speedPopup?.let { id ->
        val i = speedInfo(id, hk, game, sameForAll) { f -> setHk(f) }
        JoeyDialog(onDismiss = { speedPopup = null }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
            Column {
                PopupTitle(i.title, i.help)
                i.choices.forEachIndexed { n, ch ->
                    JoeyRow(ch.label, Modifier.initialFocus(ch.on), detail = ch.detail, experimental = ch.experimental,
                        trailing = if (ch.on) { _ -> PickerTick() } else null) { i.onPick?.invoke(n); speedPopup = null }
                }
            }
        }
    }
}

/** D6: the Speed group with a game picked (these are set once for every game). */
private const val SAME = "Same for every game"
private const val SPEED_FF = "speed_ff"
private const val SPEED_FF_MODE = "speed_ff_mode"
private const val SPEED_SLOW = "speed_slow"
private const val SPEED_SLOW_MODE = "speed_slow_mode"
private const val SPEED_RW = "speed_rw"
private const val SPEED_RW_BACK = "speed_rw_back"

/** The Speed group's panel (emuspeed_mock): its help, which button turns it on, and the choices of a mode row. */
private fun speedInfo(id: String, h: com.joeysretro.joey64.controls.Hotkeys, game: Boolean, sameForAll: String,
                      set: ((com.joeysretro.joey64.controls.Hotkeys) -> com.joeysretro.joey64.controls.Hotkeys) -> Unit):
    com.joeysretro.joey64.launcher.components.PanelInfo {
    fun button(a: com.joeysretro.joey64.controls.HotkeyAction) = "Button" to (if (h.keys[a] == null) "Not set · set one in Controls › Hotkeys"
        else hotkeyValue(h, a) + " · change it in Controls › Hotkeys")
    fun g(help: String) = if (game) sameForAll else help
    val hk = com.joeysretro.joey64.controls.Hotkeys
    return when (id) {
        SPEED_FF -> com.joeysretro.joey64.launcher.components.PanelInfo("Fast-forward speed",
            g("How fast the game runs while fast-forward is on. Also in Controls › Hotkeys › Fast-forward: it's the same setting."),
            lines = listOf(button(com.joeysretro.joey64.controls.HotkeyAction.FastForward), "125% to 400%" to "In steps of 25. Default 400%"))
        SPEED_FF_MODE -> if (game) com.joeysretro.joey64.launcher.components.PanelInfo("Fast-forward mode", sameForAll)
            else com.joeysretro.joey64.controls.FastForwardMode.entries.let { m ->
                com.joeysretro.joey64.launcher.components.PanelInfo("Fast-forward mode", "How the button works.",
                    m.map { com.joeysretro.joey64.launcher.components.PanelChoice(it.label, it.detail, it == h.ffMode, it.experimental) }) { i ->
                    set { x -> x.copy(ffMode = m[i]) } } }
        SPEED_SLOW -> com.joeysretro.joey64.launcher.components.PanelInfo("Slow motion speed",
            g("How slow the game runs while slow motion is on. Same setting as Controls › Hotkeys › Slow motion."),
            lines = listOf(button(com.joeysretro.joey64.controls.HotkeyAction.SlowMotion), "20% to 90%" to "In steps of 10. Default 50%"))
        SPEED_SLOW_MODE -> if (game) com.joeysretro.joey64.launcher.components.PanelInfo("Slow motion mode", sameForAll)
            else com.joeysretro.joey64.controls.SlowMode.entries.let { m ->
                com.joeysretro.joey64.launcher.components.PanelInfo("Slow motion mode", "How the button works.",
                    m.map { com.joeysretro.joey64.launcher.components.PanelChoice(it.label, it.detail, it == h.slowMode) }) { i ->
                    set { x -> x.copy(slowMode = m[i]) } } }
        SPEED_RW -> com.joeysretro.joey64.launcher.components.PanelInfo("Rewind",
            g("Records while you play, so holding the rewind button goes back in time. Takes memory and a little work every frame. A turns it on or off."),
            lines = listOf(button(com.joeysretro.joey64.controls.HotkeyAction.Rewind),
                "Memory" to "About ${hk.rewindMb(h.rewindSeconds, h.rewindEvery)} MB for ${hk.rewindSecondsLabel(h.rewindSeconds)}"))
        else -> if (game) com.joeysretro.joey64.launcher.components.PanelInfo("Rewind: how far back", sameForAll)
            else hk.REWIND_SECONDS.let { secs ->
                com.joeysretro.joey64.launcher.components.PanelInfo("Rewind: how far back", "The oldest moment rewind can go back to. Longer takes more memory.",
                    secs.map { com.joeysretro.joey64.launcher.components.PanelChoice(hk.rewindSecondsLabel(it), "About ${hk.rewindMb(it, h.rewindEvery)} MB", it == h.rewindSeconds) }) { i ->
                    set { x -> x.copy(rewindSeconds = secs[i]) } } }
    }
}

private fun title(k: EmuKey) = when (k) {
    EmuKey.Overclock -> "Overclock"
    EmuKey.CpuCore -> "CPU core"
    EmuKey.ExpansionPak -> "Expansion Pak"
    EmuKey.Thermal -> "Thermal fallback"
}

private fun describe(k: EmuKey): String = when (k) {
    EmuKey.Overclock -> "A faster N64 CPU removes the slowdown real hardware had. Too fast can break some games' timing. Applies when the game next starts."
    EmuKey.CpuCore -> "How the N64's CPU is run. Auto is right for almost every game. Applies when the game next starts."
    EmuKey.ExpansionPak -> "The extra 4 MB of memory. Off can help speed in a few games. Applies when the game next starts."
    EmuKey.Thermal -> "Lowers the fast-forward speed as the device gets hot. Applies when the game next starts."
}

/** The approved popup (mockup screens 3 to 5): the choices with their lines, the tick on the one in use, "Auto gives ..." under the title. */
@Composable
private fun EmuChoicePopup(k: EmuKey, current: String, autoGives: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 500.dp) {
        Column {
            // The row's description (review sheet L9), then "Auto gives ..."; the note below says when it applies.
            PopupTitle(title(k), listOfNotNull(describe(k).removeSuffix(" Applies when the game next starts."), autoGives).joinToString("\n"))
            for (ch in EmuChoices.of(k)) {
                val on = ch.value == current
                JoeyRow(ch.label, Modifier.initialFocus(on), detail = ch.detail,
                    trailing = if (on) { _ -> PickerTick() } else null) { onPick(ch.value) }
            }
            com.joeysretro.joey64.launcher.components.PopupNote("Applies when the game next starts.")
        }
    }
}
