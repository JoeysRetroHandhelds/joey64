package com.joeysretro.joey64.launcher.components

import com.joeysretro.joey64.JLog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch

/**
 * Focus building blocks, carried over from JoeyOS (ui/components/Focusables.kt) with its rules
 * (JoeyOS/docs/input-rewrite.md, launcher/docs/dpad-nav-rewrite.md):
 *  - The highlight is real Compose focus, never a remembered index.
 *  - One focus target per element: `clickable` already is one, so it is never paired with a
 *    separate `focusable` (that made the D-pad "stop twice" in Chameleon).
 *  - No timers in focus code: no delay, frame wait or retry before a request. A target that may
 *    not exist yet uses a [FocusLanding].
 *  - Containers remember where you were (`focusRestorer`); the app doesn't.
 */

private const val TAG = "Joey64Focus"

/** An interaction source plus whether it currently holds focus: the highlight for one element. */
@Composable
fun rememberFocusState(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return source to focused
}

/**
 * A row or grid of items with focus memory: coming back into it lands on the item you left, the
 * first time on [first]. Without it Android picks the item nearest the one you came from, which in
 * rows of different widths is rarely the one you expect (found on device in JoeyOS).
 */
fun Modifier.focusRow(first: FocusRequester): Modifier = this.focusRestorer(first).focusGroup()

/**
 * A one-shot "focus lands here next" hand-off, for when focus has to go to an element that may
 * not exist yet (rows still loading, a lazy item composed at the next layout, a button a run is
 * about to show). The element asks for focus itself ([landFocus]) from an effect that only runs
 * once it is in the tree, so there is no frame wait, no retry and no "not attached" error to
 * swallow. [arm] it in the event that makes the change.
 */
@Stable
class FocusLanding(armed: Boolean = true) {
    internal var armedAt by mutableIntStateOf(if (armed) 1 else 0)
    internal var landedAt by mutableIntStateOf(0)
    val pending: Boolean get() = armedAt != landedAt
    /** Land again, on the next marked element that is attached (at once if one already is). */
    fun arm() { armedAt = maxOf(armedAt, landedAt) + 1 }
}

/** The landing a popup or page provides for its content ([initialFocus]). */
val LocalFocusLanding = staticCompositionLocalOf<FocusLanding?> { null }

/**
 * Marks where focus lands for [landing]. [enabled] picks which of several candidates is the
 * target (the row you came from, the current choice). Works in lazy lists: an item that isn't
 * composed yet takes focus when the list composes it.
 */
fun Modifier.landFocus(landing: FocusLanding?, enabled: Boolean = true): Modifier = composed {
    val requester = remember { FocusRequester() }
    if (landing != null && enabled && landing.pending) {
        val generation = landing.armedAt
        LaunchedEffect(landing, generation) {
            if (requester.requestFocus(FocusDirection.Enter)) landing.landedAt = generation
            // Refused (not focusable now, or another window has focus): logged, since the only
            // symptom is "nothing highlighted".
            else JLog.i(TAG, "A focus request was refused (landing $generation)")
        }
    }
    this.focusRequester(requester)
}

/** Where focus lands when the popup ([JoeyDialog]) or page ([JoeyPage]) around it opens. */
fun Modifier.initialFocus(enabled: Boolean = true): Modifier = composed {
    this.landFocus(LocalFocusLanding.current, enabled)
}

/**
 * A pressable element the controller and touch both reach: A (DPAD centre after the input
 * layer) and a tap call [onClick]; a long-press calls [onLongClick] (touch's way to Options, since
 * a pad opens Options with Start). One focus target; [source] lights the highlight.
 */
fun Modifier.pressable(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier =
    if (onLongClick == null) this.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
    else this.combinedClickable(interactionSource = source, indication = null, enabled = enabled,
        onLongClick = onLongClick, onClick = onClick)

/**
 * Makes a read-only row a focus stop. Android only scrolls a list to follow focus, so a row with
 * nothing to press needs a stop of its own for the D-pad to reach it.
 */
fun Modifier.readOnlyFocus(source: MutableInteractionSource): Modifier = this.focusable(interactionSource = source)

/**
 * For a panel drawn over a page (not a popup window): focus moves into it when it opens, and the
 * D-pad can't leave it for the page underneath. The request is made from an effect of the panel
 * itself, so its group is attached when it runs.
 */
fun Modifier.overlayFocus(): Modifier = composed {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus(FocusDirection.Enter) }
    this
        .focusRequester(requester)
        .focusProperties { onExit = { cancelFocusChange() } }
        .focusGroup()
}

/**
 * Lets a text field inside a [JoeyDialog] claim B / Back while it's being typed in, so cancel
 * steps back one level (stop typing) before it closes the popup.
 */
class DialogBackRegistry {
    private val handlers = mutableListOf<() -> Boolean>()
    fun add(handler: () -> Boolean) { handlers += handler }
    fun remove(handler: () -> Boolean) { handlers -= handler }
    /** True when a registered handler consumed the press. */
    fun handle(): Boolean = handlers.asReversed().any { it() }
}

val LocalDialogBack = staticCompositionLocalOf<DialogBackRegistry?> { null }

/**
 * Brings this whole block into view (a heading and the rows under it) whenever focus is inside
 * it. A list on its own scrolls only far enough to show the focused control, so a heading above
 * a first row stayed hidden (found in JoeyOS). Uses BringIntoViewRequester: no scroll index and
 * no frame wait.
 */
fun Modifier.revealWholeOnFocus(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    this
        .bringIntoViewRequester(requester)
        .onFocusChanged { if (it.hasFocus) scope.launch { requester.bringIntoView() } }
}

/** On a page (not in a popup window) a field being typed in takes Back first. */
@Composable
internal fun TypingBackHandler(typing: Boolean, inDialog: Boolean, stop: () -> Unit) {
    BackHandler(enabled = typing && !inDialog) { stop() }
}
