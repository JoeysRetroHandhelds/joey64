package com.joeysretro.joey64.launcher.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.audio.AudioKey
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.InfoChip
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.RowTag
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.controls.ControlBus
import com.joeysretro.joey64.launcher.gamesettings.GamePart
import com.joeysretro.joey64.launcher.gamesettings.GameRef
import com.joeysretro.joey64.launcher.gamesettings.GameSettingsModel
import com.joeysretro.joey64.launcher.gamesettings.GameSummary
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * Game settings (approved mockup 2026-11-07: scratchpad build_gameset.py), one page per game.
 * Left, This game: Video, Audio and Controls with how many rows the game has of its own; All of it:
 * Reset to All games, Copy to another game. Right: every row it changed, with what All games uses;
 * A opens that row. Video and Audio open Settings' tabs set to the game (only those two tabs), Controls
 * the Controls page; B comes back here. Opened from the library (a game's options, [GameSettingsScreen])
 * and from the pause menu ([InGameGameSettings]). The Picture and Texture pack panels (approved
 * 2026-11-05) join This game when they're built.
 */
@Composable
fun GameSettingsScreen(game: GameRef, onHints: (List<Hint>) -> Unit, onClose: () -> Unit) {
    val sink = remember { ControlsSink() }
    BackHandler { if (!sink.onBack()) onClose() }
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { sink.onControl(it) }
        ControlBus.push(handler)
        onDispose { ControlBus.pop(handler) }
    }
    GameSettingsContent(game, inGame = false, sink = sink, onHints = onHints)
}

/** Game settings over the paused game (mockup screen 9): the same page without the rail. B on the page goes back to the pause menu. */
@Composable
fun InGameGameSettings(game: GameRef, onPictureChanged: () -> Unit = {}, onClose: () -> Unit) {
    val sink = remember { ControlsSink() }
    var hints by remember { mutableStateOf(emptyList<Hint>()) }
    // Video's Shader, Sides or Overlay row: the Picture panel over the game instead of this page; back on its row.
    var panel by remember { mutableStateOf<String?>(null) }
    var back by remember { mutableStateOf<String?>(null) }
    panel?.let { row ->
        if (row == "crop") CropPanel(game.saveName) { back = row; panel = null }
        else PicturePanel(game.saveName, row, onPictureChanged) { back = row; panel = null }
        return
    }
    JoeyDialog(
        onDismiss = onClose,
        hints = hints,
        fullScreen = true,
        interceptBack = { sink.onBack() },
        onPadButton = { code -> com.joeysretro.joey64.launcher.controls.Controls.intentFor(code)?.let { sink.onControl(it) } },
    ) {
        GameSettingsContent(game, inGame = true, sink = sink, onHints = { hints = it },
            startView = back?.let { GsView.Tabs(GamePart.Video, it) } ?: GsView.Page, onPicturePanel = { panel = it })
    }
}

private sealed interface GsView {
    data object Page : GsView
    /** Settings' Video or Audio tab set to the game; [start] is the changed row to open at. */
    data class Tabs(val part: GamePart, val start: String?) : GsView
    data class Controls(val player: Int) : GsView
}

private sealed interface GsPopup {
    data object Reset : GsPopup
    data object Copy : GsPopup
    data object PickGame : GsPopup
    data class Replace(val to: String, val title: String) : GsPopup
}

/** The name in sentences: "Banjo-Kazooie" for "Banjo-Kazooie (USA)". */
private fun shortTitle(t: String) = t.substringBefore(" (").ifBlank { t }

@Composable
private fun GameSettingsContent(game: GameRef, inGame: Boolean, sink: ControlsSink, onHints: (List<Hint>) -> Unit,
                                startView: GsView = GsView.Page, onPicturePanel: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val model = remember { GameSettingsModel(context) }
    var view by remember { mutableStateOf(startView) }
    var popup by remember { mutableStateOf<GsPopup?>(null) }
    var version by remember { mutableIntStateOf(0) }
    // The page's row with focus, kept so B from a part lands back on it: "Video", "Audio", "Controls", "reset", "copy", "ch<i>".
    var focused by remember { mutableStateOf(GamePart.Video.name) }
    val inner = remember(view) { ControlsSink() }
    var tabControl by remember { mutableStateOf<((Control) -> Boolean)?>(null) }
    val landing = LocalFocusLanding.current
    // Each view lands focus on its first row, except a tab opened from the tab row, which keeps focus there.
    var tabsFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!(view is GsView.Tabs && tabsFocused)) landing?.arm() }
    // Armed with the change, not after it (B1): armed only once the new view had composed, the old row was gone a frame
    // with nothing to land on, so focus fell to the top bar's Library for that frame.
    fun show(next: GsView) {
        if (!(next is GsView.Tabs && tabsFocused)) landing?.arm()
        view = next
    }

    sink.onControl = { ctl ->
        when (val v = view) {
            GsView.Page -> false
            is GsView.Tabs -> when (ctl) {
                Control.TabPrev, Control.TabNext -> {
                    val i = TAB_PARTS.indexOf(v.part) + if (ctl == Control.TabNext) 1 else TAB_PARTS.size - 1
                    show(GsView.Tabs(TAB_PARTS[i % TAB_PARTS.size], null)); true
                }
                else -> tabControl?.invoke(ctl) ?: false
            }
            is GsView.Controls -> { inner.onControl(ctl); true }
        }
    }
    sink.onBack = {
        when (view) {
            GsView.Page -> false
            is GsView.Tabs -> { show(GsView.Page); version++; true }
            is GsView.Controls -> { if (!inner.onBack()) { show(GsView.Page); version++ }; true }
        }
    }

    when (val v = view) {
        GsView.Page -> {
            val summary = remember(version) { model.summary(game.saveName) }
            GamePage(game, summary, inGame, focused, onFocused = { focused = it }, onHints = onHints,
                onOpen = { part, row ->
                    show(when (part) {
                        GamePart.Controls -> GsView.Controls(row?.removePrefix("p")?.toIntOrNull() ?: 0)
                        else -> GsView.Tabs(part, row)
                    })
                },
                onReset = { popup = GsPopup.Reset }, onCopy = { popup = GsPopup.Copy })
            GamePopupsFor(game, summary, model, popup, onPopup = { popup = it }, onChanged = { version++ })
        }
        is GsView.Tabs -> GameTabs(game, v, onHints, { tabControl = it }, onPicturePanel, { tabsFocused = it }) { show(GsView.Tabs(it, null)) }
        is GsView.Controls -> Column(Modifier.fillMaxSize()) {
            GameControlsPage(game.saveName, game.title, v.player, inner, onHints)
        }
    }
}

/** The parts that are Settings tabs (Controls has its own page). */
private val TAB_PARTS = listOf(GamePart.Video, GamePart.Emulation, GamePart.Audio)

/** Settings' Video, Emulation and Audio tabs, set to the game: no other tabs, the game's name in the header. */
@Composable
private fun GameTabs(game: GameRef, v: GsView.Tabs, onHints: (List<Hint>) -> Unit, onControl: (((Control) -> Boolean)?) -> Unit,
                     onPicturePanel: ((String) -> Unit)?, onTabsFocused: (Boolean) -> Unit, onTab: (GamePart) -> Unit) {
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    val parts = TAB_PARTS
    Column(Modifier.fillMaxSize().padding(horizontal = s.margin).padding(top = 14.dp, bottom = 8.dp)) {
        com.joeysretro.joey64.launcher.components.PageHeader("Settings") { InfoChip(game.title, R.drawable.ic_sliders, on = true) }
        // The tab row has focus: its own hints; the tab's come back when focus goes down (Joey 2026-11-09, D-pad tabs).
        var onTabs by remember { mutableStateOf(false) }
        var pageHints by remember { mutableStateOf(listOf(Hint("B", "Back"))) }
        LaunchedEffect(onTabs, pageHints) { onHints(if (onTabs) listOf(Hint("A", "Open"), Hint("B", "Back")) else pageHints) }
        var focusedPart by remember { mutableStateOf(v.part) }
        SettingsTabs(parts.map { it.label }, parts.indexOf(v.part), onFocused = { onTabs = it; onTabsFocused(it) },
            onTabFocused = { focusedPart = parts[it] }) { onTab(parts[it]) }
        // The tab row has focus: the panel shows the tab's own help (F5), as Settings' tab row does.
        val tabHelp = if (onTabs) com.joeysretro.joey64.launcher.components.PanelInfo(focusedPart.label, when (focusedPart) {
            GamePart.Emulation -> "This game's overclock, CPU core, Expansion Pak and thermal fallback."
            GamePart.Audio -> "This game's own volume and latency."
            else -> "How this game's picture looks and which renderer and driver draw it."
        } + " " + TAB_ROW_HELP) else com.joeysretro.joey64.launcher.components.LocalPanelHelp.current
        androidx.compose.runtime.CompositionLocalProvider(com.joeysretro.joey64.launcher.components.LocalPanelHelp provides tabHelp) {
        key(v) {
            val onHints: (List<Hint>) -> Unit = { pageHints = it }
            when (v.part) {
                GamePart.Emulation -> EmulationTab(game.saveName, onHints, onControl, Modifier.weight(1f),
                    start = v.start?.let { r -> com.joeysretro.joey64.emulation.EmuKey.entries.firstOrNull { it.name == r } })
                GamePart.Audio -> AudioTab(game.saveName, onHints, onControl, Modifier.weight(1f),
                    start = v.start?.let { r -> AudioKey.entries.firstOrNull { it.name == r } })
                else -> VideoTab(game.saveName, onHints, onControl, Modifier.weight(1f), start = v.start, onPicturePanel = onPicturePanel)
            }
        }
        }
    }
}

@Composable
private fun GamePage(game: GameRef, summary: GameSummary, inGame: Boolean, focused: String, onFocused: (String) -> Unit,
                     onHints: (List<Hint>) -> Unit, onOpen: (GamePart, String?) -> Unit, onReset: () -> Unit, onCopy: () -> Unit) {
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    val name = shortTitle(game.title)
    val none = summary.total == 0
    val (title, text) = when {
        focused == GamePart.Video.name -> "Video" to when {
            summary.count(GamePart.Video) == 0 -> "$name follows All games' Video. Change a row there to give it its own."
            inGame -> "$name's own Video settings. Most rows apply when you go back to the game. Resolution and the Renderer, driver, pacing rows apply when it next starts."
            else -> "$name's own Video settings. The rows with a dot are its own; the rest follow All games."
        }
        focused == GamePart.Emulation.name -> "Emulation" to when {
            summary.count(GamePart.Emulation) == 0 -> "$name follows All games' Emulation. Change a row there to give it its own."
            inGame -> "$name's own Emulation settings. They apply when it next starts."
            else -> "$name's own Emulation settings. The rows with a dot are its own; the rest follow All games."
        }
        focused == GamePart.Audio.name -> "Audio" to when {
            summary.count(GamePart.Audio) == 0 -> "$name follows All games' Audio. A game can have its own Volume and Latency."
            inGame -> "$name's own Audio settings. Volume applies when you go back to the game, Latency when it next starts."
            else -> "$name's own Audio settings: Volume and Latency."
        }
        focused == GamePart.Controls.name -> "Controls" to when {
            summary.count(GamePart.Controls) == 0 -> "$name follows All games' Controls. Change a row there to give it its own."
            inGame -> "$name's own Controls. They apply when you go back to the game."
            else -> "$name's own Controls. The rows with a dot are its own; the rest follow All games."
        }
        focused == "reset" -> "Reset to All games" to (if (none) "Nothing to reset: $name uses All games." else "Drops this game's own settings.")
        focused == "copy" -> "Copy to another game" to (if (none) "Nothing to copy: $name uses All games." else "Gives another game these settings.")
        else -> summary.changes.getOrNull(focused.removePrefix("ch").toIntOrNull() ?: -1)?.let {
            it.name to "${it.value} for this game, ${it.allGames} on All games. A opens it."
        } ?: ("" to "")
    }
    // One list with the panel beside it (approved 2026-11-11): a part's row shows what's changed in it.
    val part = GamePart.entries.firstOrNull { it.name == focused }
    val info = com.joeysretro.joey64.launcher.components.PanelInfo(title, text,
        lines = part?.let { p -> summary.changes.filter { it.part == p }.map { it.name to "${it.value} · All games: ${it.allGames}" } } ?: emptyList())
    val panel = com.joeysretro.joey64.launcher.components.rememberListPanel()
    Column(Modifier.fillMaxSize().padding(horizontal = s.margin).padding(top = 14.dp, bottom = 8.dp)) {
        com.joeysretro.joey64.launcher.components.PageHeader("Game settings") { InfoChip(game.title, R.drawable.ic_rail_controls) }
        Spacer(Modifier.height(12.dp))
        com.joeysretro.joey64.launcher.components.ListPanel(panel, info, listOf(Hint("A", "Open"), Hint("B", "Back")), onHints, Modifier.weight(1f)) {
        run {
            SectionLabel("This game")
            for (p in GamePart.entries) {
                JoeyRow(p.label, Modifier.initialFocus(focused == p.name), value = summary.partLabel(p) + "  ›", sideways = true,
                    onFocused = { if (it) onFocused(p.name) }) { onOpen(p, null) }
            }
            SectionLabel("All of it")
            JoeyRow("Reset to All games", Modifier.initialFocus(focused == "reset"), enabled = !none, sideways = true,
                onFocused = { if (it) onFocused("reset") }) { if (!none) onReset() }
            JoeyRow("Copy to another game", Modifier.initialFocus(focused == "copy"), value = "›", enabled = !none, sideways = true,
                onFocused = { if (it) onFocused("copy") }) { if (!none) onCopy() }
            SectionLabel("Changed for this game")
            if (none) Text("Nothing yet: $name uses All games' Video, Emulation, Audio and Controls.", style = JoeyTheme.type.note, color = c.textDim,
                modifier = Modifier.padding(horizontal = s.rowPadH, vertical = 6.dp))
            summary.changes.forEachIndexed { i, ch ->
                JoeyRow(ch.name, Modifier.initialFocus(focused == "ch$i"), value = ch.value, detail = "All games: ${ch.allGames}", sideways = true,
                    onFocused = { if (it) onFocused("ch$i") },
                    leading = { _ -> RowTag(ch.part.label, c.textSecondary, c.chip) }) { onOpen(ch.part, ch.row) }
            }
        }
        }
    }
}

/** Reset's question, Copy's parts, the game picker and Replace's question (mockup screens 5-7). */
@Composable
private fun GamePopupsFor(game: GameRef, summary: GameSummary, model: GameSettingsModel, popup: GsPopup?,
                          onPopup: (GsPopup?) -> Unit, onChanged: () -> Unit) {
    val own = remember(summary) { GamePart.entries.filter { summary.count(it) > 0 }.toSet() }
    var picked by remember(summary) { mutableStateOf(own) }
    fun rows(n: Int) = if (n == 1) "1 row" else "$n rows"
    fun copyTo(to: String) { model.copy(game.saveName, to, picked); onPopup(null); onChanged() }
    when (popup) {
        null -> {}
        GsPopup.Reset -> JoeyDialog(onDismiss = { onPopup(null) }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
            Column {
                PopupTitle("Reset to All games?", "${game.title} uses All games' Video, Audio and Controls again. Its cheats, saves and states stay.")
                JoeyOption("Reset") { model.reset(game.saveName); onPopup(null); onChanged() }
                JoeyOption("Cancel", Modifier.initialFocus()) { onPopup(null) }
            }
        }
        GsPopup.Copy -> JoeyDialog(onDismiss = { onPopup(null) }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
            Column {
                PopupTitle("Copy to another game", "What to copy from ${game.title}.")
                GamePart.entries.forEachIndexed { i, p ->
                    val has = p in own
                    JoeyRow(p.label, if (i == 0) Modifier.initialFocus() else Modifier,
                        value = if (has && p in picked) "On" else "Off", enabled = has,
                        detail = if (has) rows(summary.count(p)) else "Same as All games, nothing to copy") {
                        if (has) picked = if (p in picked) picked - p else picked + p
                    }
                }
                PopupDivider()
                JoeyOption("Pick a game", value = "›", enabled = picked.isNotEmpty()) { if (picked.isNotEmpty()) onPopup(GsPopup.PickGame) }
            }
        }
        GsPopup.PickGame -> PickGamePopup("Gets ${shortTitle(game.title)}'s ${picked.sortedBy { it.ordinal }.joinToString(" and ") { it.label }}.",
            onDismiss = { onPopup(GsPopup.Copy) }, exclude = game.title) { save, title ->
            when {
                save == game.saveName -> onPopup(GsPopup.Copy)
                model.parts(save).any { it in picked } -> onPopup(GsPopup.Replace(save, title))
                else -> copyTo(save)
            }
        }
        is GsPopup.Replace -> {
            val theirs = remember(popup.to) { model.summary(popup.to) }
            val clash = picked.filter { theirs.count(it) > 0 || it in model.parts(popup.to) }.sortedBy { it.ordinal }
            val rest = picked.filter { it !in clash }.sortedBy { it.ordinal }
            val counts = clash.joinToString(" and ") { "${theirs.count(it).coerceAtLeast(1)} ${it.label}" }
            val rowWord = if (clash.size == 1 && theirs.count(clash[0]) <= 1) "row" else "rows"
            JoeyDialog(onDismiss = { onPopup(null) }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
                Column {
                    PopupTitle("Replace ${popup.title}'s ${clash.joinToString(" and ") { it.label }}?",
                        "${popup.title} has $counts $rowWord of its own. They're replaced by ${game.title}'s." +
                            if (rest.isEmpty()) "" else " Its ${rest.joinToString(" and ") { it.label }} ${if (rest.size == 1) "is" else "are"} copied as well.")
                    // Cancel focused: Replace overwrites another game's own settings (code audit 3, p2 L4), as Reset and Delete focus Keep.
                    JoeyOption("Replace and copy") { copyTo(popup.to) }
                    JoeyOption("Cancel", Modifier.initialFocus()) { onPopup(null) }
                }
            }
        }
    }
}
