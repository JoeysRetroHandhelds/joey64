package com.joeysretro.joey64.launcher.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.launch

/** One choice in the panel: as a choice popup's row (its grey line, the tick on the one in use). */
class PanelChoice(val label: String, val detail: String? = null, val on: Boolean = false, val experimental: Boolean = false,
                  val enabled: Boolean = true)

/**
 * What the panel shows for the focused row (approved list + panel, Joey 2026-11-11, scratchpad setlist_mock.png): its
 * name, its help, and either its [choices] (a row with a short list: A or ▶ goes into them, mockup screen 4) or the
 * [lines] set inside it (a row that opens a page of its own: Colour's four values, screen 5; A opens it as before).
 */
class PanelInfo(val title: String, val help: String, val choices: List<PanelChoice> = emptyList(), val lines: List<Pair<String, String>> = emptyList(),
                val onPick: ((Int) -> Unit)? = null) {
    val pickable get() = choices.isNotEmpty() && onPick != null
}

/** The page's panel: [enter] moves focus into the focused row's choices (A on such a row, or ▶). */
@Stable
class ListPanelState {
    internal var wide by mutableStateOf(false)
    internal var inside by mutableStateOf(false)
    internal var info: PanelInfo? = null
    internal val list = FocusRequester()
    /** The list has focus (not the panel, the tabs or the chips above it): L2 / R2 page it then. */
    internal var listFocused = false
    internal var viewport = 0
    /** Where focus goes on [enter]: the ticked choice, else the first one ([landFocus], no frame wait). */
    internal val landing = FocusLanding(armed = false)

    /** Into the panel's choices; false on a narrow screen (no panel there: the row opens its popup) or with no choices. */
    fun enter(): Boolean {
        if (!wide || info?.pickable != true) return false
        inside = true
        landing.arm()
        return true
    }

    internal fun leave() {
        runCatching { list.requestFocus() }
        inside = false
    }
}

@Composable
fun rememberListPanel() = remember { ListPanelState() }

/**
 * Help that the panel shows instead of the focused row's while focus is above the list (Joey 2026-11-12, F5): the tab
 * in use while the tab row has focus, the focused place while the top bar has it. Null: the row's own help.
 */
val LocalPanelHelp = androidx.compose.runtime.compositionLocalOf<PanelInfo?> { null }

/**
 * A Settings-type page as one list with a panel beside it (approved 2026-11-11: every Settings tab, Game settings,
 * Controls with Hotkeys, Touch controls; Google TV's own settings pattern): the rows in one column, Down goes through
 * all of them; the panel on the right follows the focus at once with the row's help and its choices, the one in use
 * ticked. Inside the panel Up / Down move, A picks (and goes back to the row), ◀ or B goes back to the row.
 * On a narrow screen ([com.joeysretro.joey64.launcher.layout.ScreenLayout.singleColumnSettings], 4:3 and the Thor's
 * bottom screen) there's no panel: the list alone with the help line under it, and A opens the popup as before.
 * [hints] are the page's own; the panel adds "▶ Choices" for a row with choices and has its own while inside.
 */
@Composable
fun ListPanel(state: ListPanelState, info: PanelInfo, hints: List<Hint>, onHints: (List<Hint>) -> Unit, modifier: Modifier = Modifier,
              list: @Composable ColumnScope.() -> Unit) {
    val wide = !com.joeysretro.joey64.launcher.layout.LocalScreenLayout.current.singleColumnSettings
    state.wide = wide
    state.info = info
    if (!wide && state.inside) state.inside = false
    val inside = state.inside
    val shownInfo = LocalPanelHelp.current ?: info
    // L2 / R2: a screen of rows less one, by the focus system's own moves (all rows are composed: no frame wait).
    // A move that leaves the list (Up from the first row goes to the tabs) is taken back and the paging stops there.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val listScroll = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
    androidx.compose.runtime.DisposableEffect(state) {
        val pager: (com.joeysretro.joey64.launcher.controls.Control) -> Boolean = p@{ ctl ->
            val dir = when (ctl) {
                com.joeysretro.joey64.launcher.controls.Control.PagePrev -> androidx.compose.ui.focus.FocusDirection.Up
                com.joeysretro.joey64.launcher.controls.Control.PageNext -> androidx.compose.ui.focus.FocusDirection.Down
                else -> return@p false
            }
            if (!state.listFocused || state.inside) return@p false
            val back = if (dir == androidx.compose.ui.focus.FocusDirection.Up) androidx.compose.ui.focus.FocusDirection.Down
                else androidx.compose.ui.focus.FocusDirection.Up
            // At either end the list scrolls all the way, so the first heading shows (it stayed cut off, Thor 2026-11-12).
            fun end() { scope.launch { listScroll.animateScrollTo(if (dir == back) listScroll.maxValue else 0) } }
            repeat(maxOf(1, (state.viewport / rowPx).toInt() - 1)) {
                if (!focusManager.moveFocus(dir)) { end(); return@p true }
                if (!state.listFocused) { focusManager.moveFocus(back); end(); return@p true }
            }
            true
        }
        com.joeysretro.joey64.launcher.controls.ControlBus.addPager(pager)
        onDispose { com.joeysretro.joey64.launcher.controls.ControlBus.removePager(pager) }
    }
    val tracked = Modifier
        .onFocusChanged { state.listFocused = it.hasFocus }
        .onSizeChanged { state.viewport = it.height }
    LaunchedEffect(inside, hints, wide, info.pickable) {
        onHints(when {
            inside -> listOf(Hint("A", "Pick"), Hint("◀", "Back to the list"), Hint("B", "Back"))
            wide && info.pickable && hints.none { it.key == "◀▶" } -> hints.take(1) + Hint("▶", "Choices") + hints.drop(1)
            else -> hints
        })
    }
    if (!wide) {
        Column(modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().then(tracked).focusRequester(state.list).focusRestorer().focusGroup()
                .verticalScroll(listScroll), content = list)
            Spacer(Modifier.height(6.dp))
            DescriptionLine(shownInfo.title, shownInfo.help)
        }
        return
    }
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(0.56f).fillMaxHeight().then(tracked)
            .onPreviewKeyEvent { e ->
                // ▶ on a row with choices goes into them; rows that step with ◀ ▶ have none, so their own handler gets it.
                val n = e.nativeKeyEvent
                if (n.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT || state.inside) return@onPreviewKeyEvent false
                if (n.action == KeyEvent.ACTION_DOWN) state.enter() else info.pickable
            }
            .focusRequester(state.list).focusRestorer().focusGroup()
            .verticalScroll(listScroll), content = list)
        Panel(state, shownInfo, Modifier.weight(0.44f).fillMaxHeight().padding(bottom = 6.dp))
    }
}

@Composable
private fun Panel(state: ListPanelState, info: PanelInfo, modifier: Modifier) {
    val c = JoeyTheme.colors
    val inside = state.inside
    val shown = info.choices
    Column(modifier
        .background(c.surface, RoundedCornerShape(10.dp))
        .onPreviewKeyEvent { e ->
            val n = e.nativeKeyEvent
            if (!state.inside) return@onPreviewKeyEvent false
            if (n.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || n.keyCode == KeyEvent.KEYCODE_BACK) {
                if (n.action == KeyEvent.ACTION_DOWN) state.leave()
                true
            } else false
        }
        .focusGroup()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 6.dp, vertical = 10.dp)) {
        Text(info.title, style = JoeyTheme.type.popupTitle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH))
        if (info.help.isNotEmpty()) Text(info.help, style = JoeyTheme.type.note, color = c.textDim,
            modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(top = 2.dp, bottom = 8.dp))
        val firstAt = shown.indexOfFirst { it.on && it.enabled }.takeIf { it >= 0 } ?: shown.indexOfFirst { it.enabled }.coerceAtLeast(0)
        shown.forEachIndexed { i, ch ->
            JoeyRow(ch.label,
                Modifier
                    .landFocus(state.landing, enabled = inside && i == firstAt)
                    .focusProperties {
                        canFocus = inside
                        if (i == 0) up = FocusRequester.Cancel
                        if (i == shown.lastIndex) down = FocusRequester.Cancel
                    },
                detail = ch.detail, enabled = ch.enabled, experimental = ch.experimental,
                trailing = if (ch.on) { _ -> com.joeysretro.joey64.launcher.screens.PickerTick() } else null) {
                info.onPick?.invoke(i)
                state.leave()
            }
        }
        for ((label, value) in info.lines) {
            Column(Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = 3.dp)) {
                Text(label, style = JoeyTheme.type.row, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, style = JoeyTheme.type.small, color = c.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
