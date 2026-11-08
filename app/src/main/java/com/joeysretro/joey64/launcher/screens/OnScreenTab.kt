package com.joeysretro.joey64.launcher.screens

import com.joeysretro.joey64.launcher.components.TwoColumns
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.achievements.RaOptions
import com.joeysretro.joey64.achievements.RaSettings
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.onscreen.OnScreen
import com.joeysretro.joey64.onscreen.OnScreenStore
import com.joeysretro.joey64.onscreen.StatsCorner
import com.joeysretro.joey64.onscreen.StatsLine
import com.joeysretro.joey64.onscreen.StatsMode
import com.joeysretro.joey64.onscreen.StatsSize

/**
 * Settings › On screen (approved Stats overlay mockup, 2026-11-07: scratchpad build_stats.py): what
 * shows over the game, the same for every game. Left, Stats overlay: Stats, Position, Size (Left /
 * Right), What it shows (Custom only). Right, Pop-ups by type; the three achievement rows are
 * Settings › RetroAchievements' own (the same setting on both tabs). Y puts the focused row back.
 */
@Composable
fun OnScreenTab(onHints: (List<Hint>) -> Unit, onControl: (((Control) -> Boolean)?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { OnScreenStore(context) }
    var o by remember { mutableStateOf(store.read()) }
    val ra by RaSettings.options(context).collectAsState()
    val r = ra ?: RaOptions()
    var focused by remember { mutableStateOf("stats") }
    var popup by remember { mutableStateOf<String?>(null) }
    val d = OnScreen()
    val dr = RaOptions()

    fun set(next: OnScreen) { store.save(next); o = next }
    fun setRa(change: (RaOptions) -> RaOptions) = RaSettings.update(context, change)
    fun reset(row: String) = when (row) {
        "stats" -> set(o.copy(stats = d.stats))
        "pos" -> set(o.copy(corner = d.corner))
        "size" -> set(o.copy(size = d.size))
        "what" -> set(o.copy(custom = d.custom))
        "st" -> set(o.copy(popStates = d.popStates))
        "sh" -> set(o.copy(popScreenshots = d.popScreenshots))
        "rs" -> set(o.copy(popReset = d.popReset))
        "hk" -> set(o.copy(popHotkeys = d.popHotkeys))
        "ach" -> setRa { it.copy(popUnlocks = dr.popUnlocks) }
        "pr" -> setRa { it.copy(popProgress = dr.popProgress) }
        else -> setRa { it.copy(popSummary = dr.popSummary) }
    }
    val custom = o.stats == StatsMode.Custom

    LaunchedEffect(Unit) {
        onControl { ctl -> if (ctl == Control.Search && popup == null) { if (focused != "what" || custom) reset(focused); true } else false }
    }
    LaunchedEffect(focused, custom) {
        val y = Hint("Y", "Reset to default")
        onHints(when {
            focused == "what" && !custom -> listOf(Hint("L/R", "Tab"), Hint("B", "Back"))
            focused == "size" -> listOf(Hint("◀▶", "Change"), y, Hint("L/R", "Tab"), Hint("B", "Back"))
            else -> listOf(Hint("A", "Change"), y, Hint("L/R", "Tab"), Hint("B", "Back"))
        })
    }

    Column(modifier.fillMaxSize()) {
        @Composable
        fun toggle(key: String, label: String, on: Boolean, change: () -> Unit) =
            JoeyRow(label, value = if (on) "On" else "Off", sideways = true, onFocused = { if (it) focused = key }) { change() }
        TwoColumns(Modifier.weight(1f), left = {
            SectionLabel("Stats overlay")
            JoeyRow("Stats", Modifier.initialFocus(), value = o.stats.label + "  ›", sideways = true,
                onFocused = { if (it) focused = "stats" }) { popup = "stats" }
            JoeyRow("Position", value = o.corner.label + "  ›", sideways = true, onFocused = { if (it) focused = "pos" }) { popup = "pos" }
            val sizes = StatsSize.entries
            StepRow("Size", o.size.label, sizes.indices.toList(), o.size.ordinal, focused == "size", first = false,
                onFocused = { if (it) focused = "size" }) { set(o.copy(size = sizes[it])) }
            JoeyRow("What it shows", value = if (custom) "${o.custom.size} of ${StatsLine.entries.size}  ›" else "Custom only",
                enabled = custom, sideways = true, onFocused = { if (it) focused = "what" }) { popup = "what" }
        }) {
            SectionLabel("Pop-ups")
            toggle("st", "Save states", o.popStates) { set(o.copy(popStates = !o.popStates)) }
            toggle("sh", "Screenshots", o.popScreenshots) { set(o.copy(popScreenshots = !o.popScreenshots)) }
            toggle("rs", "Reset", o.popReset) { set(o.copy(popReset = !o.popReset)) }
            toggle("hk", "Hotkey changes", o.popHotkeys) { set(o.copy(popHotkeys = !o.popHotkeys)) }
            toggle("ach", "Achievement unlocks", r.popUnlocks) { setRa { it.copy(popUnlocks = !it.popUnlocks) } }
            toggle("pr", "Achievement progress", r.popProgress) { setRa { it.copy(popProgress = !it.popProgress) } }
            toggle("gm", "Game start banner", r.popSummary) { setRa { it.copy(popSummary = !it.popSummary) } }
        }
        Spacer(Modifier.height(6.dp))
        val (title, text) = describe(focused)
        DescriptionLine(title, if (focused == "what" && !custom) "Pick Custom in Stats to choose the lines." else text)
    }

    when (popup) {
        "stats" -> ChoiceList("Stats", StatsMode.entries.map { Triple(it.label, it.detail, it == o.stats) }, { popup = null }) {
            set(o.copy(stats = StatsMode.entries[it])); popup = null
        }
        "pos" -> ChoiceList("Position", StatsCorner.entries.map { Triple(it.label, null, it == o.corner) }, { popup = null }) {
            set(o.copy(corner = StatsCorner.entries[it])); popup = null
        }
        "what" -> LinesPopup(o.custom, { popup = null }) { line ->
            set(o.copy(custom = if (line in o.custom) o.custom - line else o.custom + line))
        }
    }
}

private fun describe(row: String): Pair<String, String> = when (row) {
    "stats" -> "Stats" to "Off, Simple (one line), Full (everything) or Custom (the lines you pick). The Stats overlay hotkey steps through them in game."
    "pos" -> "Position" to "The corner the stats sit in. Pop-ups in the same corner move to the other side."
    "size" -> "Size" to "How big the stats' text is."
    "what" -> "What it shows" to "The lines Custom shows."
    "st" -> "Save states" to "Saved, loaded and the slot when you change it. A state that didn't save or load always shows."
    "sh" -> "Screenshots" to "Screenshot saved. One that wasn't saved always shows."
    "rs" -> "Reset" to "Game reset."
    "hk" -> "Hotkey changes" to "Volume, mute, resolution and stats overlay pop-ups when you use their hotkeys."
    "ach" -> "Achievement unlocks" to "Unlocks, Beaten and Mastered. The same as Settings › RetroAchievements › Unlocks."
    "pr" -> "Achievement progress" to "The small box with how far you are on an achievement (e.g. 7 of 10 stars)."
    else -> "Game start banner" to "The game's achievements as it starts. The same as Settings › RetroAchievements › Game start summary."
}

/** The approved small popup: a title and the choices with their lines, the tick on the one in use. */
@Composable
internal fun ChoiceList(title: String, choices: List<Triple<String, String?, Boolean>>, onDismiss: () -> Unit, sub: String? = null, onPick: (Int) -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle(title, sub)
            choices.forEachIndexed { i, (label, detail, on) ->
                JoeyRow(label, Modifier.initialFocus(on), detail = detail,
                    trailing = if (on) { _ -> PickerTick() } else null) { onPick(i) }
            }
        }
    }
}

/** What it shows (mockup screen 5): the ten lines in two columns, A turns one on or off. */
@Composable
private fun LinesPopup(on: Set<StatsLine>, onDismiss: () -> Unit, onToggle: (StatsLine) -> Unit) {
    val c = JoeyTheme.colors
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "On / Off"), Hint("B", "Close")), width = 640.dp) {
        Column {
            PopupTitle("What it shows")
            Text("The lines of the stats overlay, top to bottom.", style = JoeyTheme.type.note, color = c.textDim,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
            val lines = StatsLine.entries
            val half = (lines.size + 1) / 2
            @Composable
            fun line(l: StatsLine) = JoeyRow(l.label, Modifier.initialFocus(l == lines.first()), value = if (l in on) "On" else "Off",
                sideways = true) { onToggle(l) }
            TwoColumns(Modifier.weight(1f, fill = false), gap = 18.dp, left = { for (l in lines.take(half)) line(l) }) { for (l in lines.drop(half)) line(l) }
        }
    }
}
