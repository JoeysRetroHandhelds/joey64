package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus

/**
 * Start: Reset tab (approved whole-tab reset mockup B, Joey 2026-11-07: scratchpad build_reset.py).
 * The hint row shows "Start Reset tab" on every tab with settings (Appearance, Video, Audio, On
 * screen, Saves, and Controls for a player); Start asks this question. The keep row has focus, so a
 * stray press changes nothing.
 */
@Composable
internal fun ResetTabPopup(title: String, note: String, keep: String, reset: String, onDismiss: () -> Unit, onReset: () -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle(title, note)
            JoeyRow(keep, Modifier.initialFocus(true)) { onDismiss() }
            JoeyRow(reset) { onReset() }
        }
    }
}

/** A game with nothing of its own on this tab: nothing to reset, so only OK. */
@Composable
internal fun NothingToResetPopup(title: String, note: String, onDismiss: () -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle(title, note)
            JoeyRow("OK", Modifier.initialFocus(true)) { onDismiss() }
        }
    }
}

/** The hints with "Start Reset tab" before L/R (the mockup's place), or before the last one (Back). */
internal fun withResetHint(hints: List<Hint>): List<Hint> {
    val start = Hint("Start", "Reset tab")
    if (start in hints || hints.isEmpty()) return hints
    val at = hints.indexOfFirst { it.key == "L/R" }.takeIf { it >= 0 } ?: hints.lastIndex
    return hints.take(at) + start + hints.drop(at)
}

/** "Its 2 changes [where] go" / "Its 1 change [where] goes": a game's changes, counted as the chip's list counts them. */
internal fun changesGo(n: Int, where: String) = if (n == 1) "Its 1 change $where goes" else "Its $n changes $where go"
