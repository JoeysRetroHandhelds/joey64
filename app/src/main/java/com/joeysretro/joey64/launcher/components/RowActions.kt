package com.joeysretro.joey64.launcher.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A long press on a row (Hold OK on a TV remote) opens a small popup with that row's key-only actions, each with its key
 * on the right (review sheet M30, approved 2026-11-11): with no controller, or on a remote, X, Y and Start can't be
 * pressed, so "Reset to default", "Clear", "Remove" and the rest couldn't be done at all. The actions are the screen's
 * own hints for the focused row, so the popup always says what the keys say, and picking one does exactly what its key
 * does. With none, a long press does what a tap does (as before).
 *
 * [JoeyPage] and [JoeyDialog] each provide a host for the rows inside them; [JoeyRow] uses it on its own.
 */
class RowActionsHost internal constructor(
    /** The hints showing now (the focused row's, once focus has moved to it). */
    internal val hints: () -> List<Hint>,
    /** Does what [key] ("X", "Y" or "Start") does on this page or popup. */
    internal val press: (key: String) -> Unit,
) {
    internal var open by mutableStateOf<Opened?>(null)
    /** The focused row's title and tap, for a TV remote's Hold OK. */
    internal var focused: Focused? = null

    internal class Opened(val title: String, val actions: List<Hint>)
    internal class Focused(val title: String, val focus: FocusRequester?, val tap: () -> Unit)
}

object RowActions {
    /** The keys a touch screen or a remote can't press; L1/R1 and L/R only jump or switch tabs, which a touch can do. */
    private val KEYS = setOf("X", "Y", "Start")
    /** Search keys aren't a row's actions (a touch opens the search box itself). */
    private val NOT_ACTIONS = setOf("Search", "Edit search")

    /** The row actions among [hints], in the order the hint row shows them. Pure, so RowActionsTest checks it. */
    fun of(hints: List<Hint>): List<Hint> = hints.filter { it.key in KEYS && it.action !in NOT_ACTIONS }

    /** The pages open in the main window, newest last, for the activity's Hold OK (dialogs handle their own). */
    private val pages = ArrayDeque<RowActionsHost>()
    internal fun push(h: RowActionsHost) { pages.addLast(h) }
    internal fun pop(h: RowActionsHost) { pages.remove(h) }

    /** Hold OK on a page: true when the focused row had more than Start to offer and its popup opened. */
    fun holdOnPage(): Boolean = pages.lastOrNull()?.let { holdOk(it) } ?: false

    /** Hold OK: the focused row's popup when it has more than Start to offer, else false (Start as before). */
    internal fun holdOk(host: RowActionsHost): Boolean {
        val row = host.focused ?: return false
        val actions = of(host.hints())
        if (actions.none { it.key != "Start" }) return false
        host.open = RowActionsHost.Opened(row.title, actions)
        return true
    }
}

/** The host a [JoeyRow] long press reaches; null outside a page or popup. */
val LocalRowActions = staticCompositionLocalOf<RowActionsHost?> { null }

/** The page hint rows showing in the launcher's window, newest last: pages draw their own [HintRow]. */
class PageHints internal constructor() {
    internal val shown = mutableListOf<Array<List<Hint>>>()
    internal fun current(): List<Hint> = shown.lastOrNull()?.get(0) ?: emptyList()
}

/** Where a page's [HintRow] reports its hints; null inside a [JoeyDialog], which hosts its own rows. */
val LocalPageHints = staticCompositionLocalOf<PageHints?> { null }

/**
 * The host for the launcher's pages (Settings, Controls, Netplay...): the hints are the page hint row's, and a key's
 * action goes to the page as the key itself would ([ControlBus]: X Extra, Y Search, Start Options).
 */
@Composable
fun ProvidePageRowActions(content: @Composable () -> Unit) {
    val sink = remember { PageHints() }
    val host = rememberRowActionsHost({ sink.current() }) { key ->
        when (key) {
            "Start" -> com.joeysretro.joey64.launcher.controls.ControlBus.dispatch(com.joeysretro.joey64.launcher.controls.Control.Options)
            "X" -> com.joeysretro.joey64.launcher.controls.ControlBus.dispatch(com.joeysretro.joey64.launcher.controls.Control.Extra)
            "Y" -> com.joeysretro.joey64.launcher.controls.ControlBus.dispatch(com.joeysretro.joey64.launcher.controls.Control.Search)
        }
    }
    androidx.compose.runtime.DisposableEffect(host) {
        RowActions.push(host)
        onDispose { RowActions.pop(host) }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPageHints provides sink, LocalRowActions provides host, content = content)
}

/** A page's [HintRow] tells the page host what it shows (the newest one showing wins). */
@Composable
internal fun ReportPageHints(hints: List<Hint>) {
    val sink = LocalPageHints.current ?: return
    val slot = remember(sink) { arrayOf(hints) }
    slot[0] = hints
    androidx.compose.runtime.DisposableEffect(sink) {
        sink.shown.add(slot)
        onDispose { sink.shown.remove(slot) }
    }
}

/** A host for a page or popup: [hints] read when a long press asks, [press] runs a key's action. */
@Composable
internal fun rememberRowActionsHost(hints: () -> List<Hint>, press: (String) -> Unit): RowActionsHost =
    remember { RowActionsHost(hints, press) }.also { RowActionsPopup(it) }

/**
 * A row's long press: focuses the row first (so the hints become its own), waits for them to follow, then opens the
 * actions popup, or taps the row when it has none. Null where no host is provided.
 */
@Composable
fun rememberRowLongPress(title: String, focus: FocusRequester?, tap: () -> Unit): (() -> Unit)? {
    val host = LocalRowActions.current ?: return null
    val scope = rememberCoroutineScope()
    val inputMode = LocalInputModeManager.current
    val latestTap = androidx.compose.runtime.rememberUpdatedState(tap)
    val latestTitle = androidx.compose.runtime.rememberUpdatedState(title)
    return remember(host) {
        {
            scope.launch {
                inputMode.requestInputMode(InputMode.Keyboard)
                runCatching { focus?.requestFocus() }
                // The screen's hints follow focus through an effect and a recomposition: three frames covers both. Waiting
                // for the row to report itself focused instead was too early: Controls builds its hints from its own
                // focused-row state a recomposition later (Thor 2026-11-12: Hold OK on A showed only Reset tab, no X / Y).
                repeat(3) { withFrameNanos { } }
                val actions = RowActions.of(host.hints())
                if (actions.isEmpty()) latestTap.value() else host.open = RowActionsHost.Opened(latestTitle.value, actions)
            }
            Unit
        }
    }
}

/** Tells the host which row has focus, for Hold OK. */
@Composable
fun ReportFocusedRow(focused: Boolean, title: String, focus: FocusRequester?, tap: () -> Unit) {
    val host = LocalRowActions.current ?: return
    val latestTap = androidx.compose.runtime.rememberUpdatedState(tap)
    LaunchedEffect(focused, title) {
        if (focused) host.focused = RowActionsHost.Focused(title, focus) { latestTap.value() }
        else if (host.focused?.title == title) host.focused = null
    }
}

/** The popup itself (mockup: review sheet item 3): the row's name, its actions with their keys, A Select, B Close. */
@Composable
private fun RowActionsPopup(host: RowActionsHost) {
    val o = host.open ?: return
    JoeyDialog(onDismiss = { host.open = null }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 300.dp) {
        Column {
            PopupTitle(o.title)
            o.actions.forEachIndexed { i, a ->
                // No long press of its own: the popup is the end of the road.
                androidx.compose.runtime.CompositionLocalProvider(LocalRowActions provides null) {
                    JoeyRow(a.action, androidx.compose.ui.Modifier.initialFocus(i == 0), trailing = { _ -> KeyBadge(a.key) }) {
                        host.open = null
                        host.press(a.key)
                    }
                }
            }
        }
    }
}

