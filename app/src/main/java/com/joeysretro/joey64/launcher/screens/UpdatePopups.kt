package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.ProgressLine
import com.joeysretro.joey64.launcher.components.PromptHeader
import com.joeysretro.joey64.launcher.components.PromptNotes
import com.joeysretro.joey64.launcher.components.PromptText
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.library.Progress
import com.joeysretro.joey64.launcher.update.Releases
import com.joeysretro.joey64.launcher.update.UpdateModel
import com.joeysretro.joey64.launcher.update.UpdateModel.Prompt

/**
 * The updater's popups over the library (approved 2026-11-05, scratchpad build_update.py):
 *  - Update available (a testing build carries the "Testing build" tag): Update or Later. It must
 *    be answered: B, Back and a tap outside do nothing.
 *  - Updating Joey64: the download with Cancel.
 *  - Let Joey64 install its updates: Install unknown apps is off. Open Android settings or Not now.
 *  - Couldn't download the update: Try again or Later (B is Later).
 */
@Composable
fun UpdatePopup(prompt: Prompt, model: UpdateModel) {
    val r = prompt.release
    val installed = model.installed
    // One popup window for every step, its contents changing: a JoeyDialog per step closed one
    // window and opened another, and the library showed through undimmed in between (Joey saw it
    // flicker on Update; filmed on the Thor, 2026-11-06).
    val failed = prompt is Prompt.Failed
    val hints = when (prompt) {
        is Prompt.Downloading -> listOf(Hint("A", "Cancel"))
        is Prompt.Failed -> listOf(Hint("A", "Select"), Hint("B", "Later"))
        else -> listOf(Hint("A", "Select"))
    }
    JoeyDialog(onDismiss = { if (failed) model.later() }, dismissible = failed, hints = hints, width = 440.dp) {
        // The window stays, so focus has to land again on each new step's first option.
        val landing = LocalFocusLanding.current
        LaunchedEffect(prompt::class) { landing?.arm() }
        Column {
            when (prompt) {
                is Prompt.Available -> {
                    if (r.testing) {
                        PromptHeader(R.drawable.ic_flask, "Update available", tag = "Testing build", tagIcon = R.drawable.ic_flask)
                        PromptText(bold("Joey64 ", r.version, " is ready. You have $installed. Testing builds get fixes first and can have new bugs; your saves and settings are kept."))
                    } else {
                        PromptHeader(R.drawable.ic_download, "Update available")
                        val size = if (r.bytes > 0) "It's ${Releases.megabytes(r.bytes)} MB, and your" else "Your"
                        PromptText(bold("Joey64 ", r.version, " is ready. You have $installed. $size games, saves and settings are kept."))
                    }
                    if (r.notes.isNotBlank()) PromptNotes("What's new in ${r.version}", r.notes)
                    JoeyOption("Update", modifier = Modifier.initialFocus()) { model.update() }
                    JoeyOption("Later") { model.later() }
                }
                is Prompt.Downloading -> {
                    PromptHeader(R.drawable.ic_download, "Updating Joey64")
                    PromptText(bold("Downloading ", r.version, ". Android asks you to confirm the install when it's done."))
                    val total = if (r.bytes > 0) r.bytes else prompt.bytes
                    ProgressLine(
                        Progress(done = Releases.megabytes(prompt.bytes).toInt(), total = Releases.megabytes(total).toInt(),
                            startedAt = prompt.startedAt, doneBytes = prompt.bytes, totalBytes = r.bytes, unit = " MB"),
                        width = 400.dp, centred = false,
                        modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp),
                    )
                    JoeyOption("Cancel", modifier = Modifier.initialFocus()) { model.cancel() }
                }
                is Prompt.NeedPermission -> {
                    PromptHeader(R.drawable.ic_shield_check, "Let Joey64 install its updates")
                    PromptText(bold("Android asks this once. On the next screen, turn on ", "Allow from this source",
                        ", then come back. The update installs as soon as you do."))
                    JoeyOption("Open Android settings", modifier = Modifier.initialFocus()) { model.openPermission() }
                    JoeyOption("Not now") { model.notNow() }
                }
                is Prompt.Failed -> {
                    PromptHeader(R.drawable.ic_wifi_off, "Couldn't download the update")
                    val where = if (prompt.bytes > 0 && r.bytes > 0)
                        "The connection dropped at ${Releases.megabytes(prompt.bytes)} of ${Releases.megabytes(r.bytes)} MB."
                    else "The download didn't start."
                    PromptText(AnnotatedString("$where Nothing was changed: you're still on $installed."))
                    JoeyOption("Try again", modifier = Modifier.initialFocus()) { model.update() }
                    JoeyOption("Later") { model.later() }
                }
            }
        }
    }
}

/** [before], then [strong] in bold, then [after]. */
private fun bold(before: String, strong: String, after: String) = buildAnnotatedString {
    append(before)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(strong) }
    append(after)
}
