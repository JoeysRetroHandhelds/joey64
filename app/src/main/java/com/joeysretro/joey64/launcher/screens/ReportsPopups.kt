package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.PromptText
import com.joeysretro.joey64.launcher.components.initialFocus

/*
 * Reports (approved reports mockup, version A, Joey 2026-11-08): Settings › About › Send reports
 * (screens 1, 2 and 4) and the question asked once over the library (screen 5).
 */

/** Screen 4: every field a report holds, and what never goes (server/reports/README.md). */
const val WHATS_SENT = """This device
  • Maker, model, chip, GPU and its driver, Android version
  • Screen size and density
  • Joey64 and core version

Each game you play
  • The game's ID (its ROM's MD5) and header code
  • Renderer, resolution and the settings you changed
  • Minutes played, average speed, and whether it closed by itself
  • Whether it started, and how long its first picture took
  • Stutter (slow frames), audio crackles, and input delay
  • How hot the device got
  • CPU core and graphics timing used, screen refresh rate, how many cheats were on, and whether it started from a save state

Box art
  • Games with no cover, or whose cover you changed: the game's ID and file name

Crashes
  • Where it happened and the first lines of the error, or for a crash in the emulator, the code it was running (library names only)

Never sent: your name, accounts or RetroAchievements sign-in, folders, ROMs, saves, location. The server sees your IP address to stop floods, and never keeps it."""

@Composable
fun WhatsSentPopup(onDismiss: () -> Unit) = TextBoxPopup("What's sent", null, WHATS_SENT, onDismiss)

/** Screen 2: Off or On, What's sent, Report ID (Reset makes a new random one). */
@Composable
fun SendReportsPopup(on: Boolean, onDismiss: () -> Unit, onSet: (Boolean) -> Unit, onResetId: () -> Unit) {
    var whatsSent by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    if (whatsSent) { WhatsSentPopup { whatsSent = false }; return }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle("Send reports", "Helps find the best driver and settings for each device, the games that need box art, and crashes to fix.")
            JoeyRow("Off", Modifier.initialFocus(!on), detail = "Joey64 sends nothing",
                trailing = if (!on) { _ -> PickerTick() } else null) { onSet(false) }
            JoeyRow("On", Modifier.initialFocus(on), detail = "At most once a day, when the library opens",
                trailing = if (on) { _ -> PickerTick() } else null) { onSet(true) }
            PopupDivider()
            JoeyOption("What's sent", value = "›") { whatsSent = true }
            JoeyRow("Report ID", value = if (reset) "New ID made" else "Reset  ›", detail = "A random number, not tied to you or this device") {
                onResetId(); reset = true
            }
        }
    }
}

/** Screen 5: asked once, the first time a game ends; focus on No thanks, B is No thanks. */
@Composable
fun AskReportsPopup(onAnswer: (Boolean) -> Unit) {
    var whatsSent by remember { mutableStateOf(false) }
    if (whatsSent) { WhatsSentPopup { whatsSent = false }; return }
    JoeyDialog(onDismiss = { onAnswer(false) }, hints = listOf(Hint("A", "Select"), Hint("B", "No thanks")), width = 470.dp) {
        Column {
            PopupTitle("Help make Joey64 better?")
            PromptText(AnnotatedString("Send reports on how games run on this device, games without box art, and crashes? " +
                "Nothing about you, and you can turn it off any time in Settings › About."))
            JoeyOption("Send reports") { onAnswer(true) }
            JoeyOption("No thanks", modifier = Modifier.initialFocus()) { onAnswer(false) }
            PopupDivider()
            JoeyOption("What's sent", value = "›") { whatsSent = true }
        }
    }
}
