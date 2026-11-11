package com.joeysretro.joey64.launcher.screens

import com.joeysretro.joey64.launcher.components.TwoColumns
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
    var focused by remember { mutableStateOf(start ?: EmuKey.ExpansionPak) }
    var popup by remember { mutableStateOf<EmuKey?>(null) }
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
    fun fixed(k: EmuKey) = k == EmuKey.ExpansionPak && (!game || needsPak)
    // No dot on a greyed row: a game that needs the pak keeps it on whatever its file says.
    fun dot(k: EmuKey) = game && k in own && !fixed(k)
    fun autoGives(k: EmuKey): String? = when (k) {
        EmuKey.CpuCore -> if (game) "Auto gives " + EmuChoices.label(k, EmuAuto.cpuCore(code)) else null
        EmuKey.Overclock -> if (game) EmuAuto.overclock(code, smoother)?.let { "Auto gives " + EmuChoices.label(k, it) + " on this device" } else null
        else -> null
    }

    // The handler is set once per target; it calls this composition's Y (with the game's needs list as loaded since)
    // through rememberUpdatedState.
    val onY by rememberUpdatedState { if (popup == null && !fixed(focused)) { reset(focused); true } else false }
    LaunchedEffect(target) {
        onControl { ctl -> if (ctl == Control.Search) onY() else false }
    }
    LaunchedEffect(target, focused, needsPak) {
        val y = Hint("Y", if (target == null) "Reset to default" else "Use All games")
        onHints(if (fixed(focused)) listOf(Hint("L/R", "Tab"), Hint("B", "Back"))
            else listOf(Hint("A", "Change"), y, Hint("L/R", "Tab"), Hint("B", "Back")))
    }

    Column(modifier.fillMaxSize()) {
        @Composable
        fun row(k: EmuKey, label: String, value: String = EmuChoices.label(k, e[k])) =
            JoeyRow(label, Modifier.initialFocus(focused == k), value = value, sideways = true, enabled = !fixed(k),
                dot = dot(k), onFocused = { if (it) focused = k }) { if (!fixed(k)) popup = k }
        TwoColumns(Modifier.weight(1f), left = {
            SectionLabel("Console")
            row(EmuKey.ExpansionPak, "Expansion Pak", when {
                !game -> "Set for one game"
                needsPak -> "Needed by this game"
                else -> EmuChoices.label(EmuKey.ExpansionPak, e[EmuKey.ExpansionPak])
            })
        }) {
            SectionLabel("Advanced")
            row(EmuKey.Overclock, "Overclock")
            row(EmuKey.CpuCore, "CPU core")
            row(EmuKey.Thermal, "Thermal fallback")
        }
        Spacer(Modifier.height(6.dp))
        val allE = EmuSettings.resolve(all, null)
        DescriptionLine(title(focused), when {
            focused == EmuKey.ExpansionPak && !game -> "The extra 4 MB of memory. Set it for one game: pick the game in the chip above."
            focused == EmuKey.ExpansionPak && needsPak -> "This game needs the Expansion Pak, so it's always on."
            dot(focused) -> "Changed for this game. All games uses ${EmuChoices.label(focused, allE[focused])}."
            else -> describe(focused)
        })
    }

    popup?.let { k ->
        EmuChoicePopup(k, e[k], autoGives(k), onDismiss = { popup = null }) { set(k, it); popup = null }
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
            PopupTitle(title(k), autoGives)
            for (ch in EmuChoices.of(k)) {
                val on = ch.value == current
                JoeyRow(ch.label, Modifier.initialFocus(on), detail = ch.detail,
                    trailing = if (on) { _ -> PickerTick() } else null) { onPick(ch.value) }
            }
            com.joeysretro.joey64.launcher.components.PopupNote("Applies when the game next starts.")
        }
    }
}
