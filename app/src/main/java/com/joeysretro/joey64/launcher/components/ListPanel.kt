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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.theme.JoeyTheme

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
    internal val first = FocusRequester()

    /** Into the panel's choices; false on a narrow screen (no panel there: the row opens its popup) or with no choices. */
    fun enter(): Boolean {
        if (!wide || info?.pickable != true) return false
        inside = true
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
    LaunchedEffect(inside, hints, wide, info.pickable) {
        onHints(when {
            inside -> listOf(Hint("A", "Pick"), Hint("◀", "Back to the list"), Hint("B", "Back"))
            wide && info.pickable && hints.none { it.key == "◀▶" } -> hints.take(1) + Hint("▶", "Choices") + hints.drop(1)
            else -> hints
        })
    }
    if (!wide) {
        Column(modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().focusRequester(state.list).focusRestorer().focusGroup()
                .verticalScroll(rememberScrollState()), content = list)
            Spacer(Modifier.height(6.dp))
            DescriptionLine(info.title, info.help)
        }
        return
    }
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(0.56f).fillMaxHeight()
            .onPreviewKeyEvent { e ->
                // ▶ on a row with choices goes into them; rows that step with ◀ ▶ have none, so their own handler gets it.
                val n = e.nativeKeyEvent
                if (n.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT || state.inside) return@onPreviewKeyEvent false
                if (n.action == KeyEvent.ACTION_DOWN) state.enter() else info.pickable
            }
            .focusRequester(state.list).focusRestorer().focusGroup()
            .verticalScroll(rememberScrollState()), content = list)
        Panel(state, info, Modifier.weight(0.44f).fillMaxHeight().padding(bottom = 6.dp))
    }
}

@Composable
private fun Panel(state: ListPanelState, info: PanelInfo, modifier: Modifier) {
    val c = JoeyTheme.colors
    val inside = state.inside
    val shown = info.choices
    LaunchedEffect(inside) {
        if (inside) { withFrameNanos { }; runCatching { state.first.requestFocus() } }
    }
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
                    .then(if (i == firstAt) Modifier.focusRequester(state.first) else Modifier)
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
