package com.joeysretro.joey64.launcher.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.controls.ControlBus
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * A full-screen page (Settings, Controls, a game's Achievements), drawn in the main window,
 * carried over from JoeyOS (ui/components/JoeyPage.kt).
 *
 * Pages are not Dialog windows: Android keeps a dialog inside the system bars, which left strips
 * of the screen beneath showing (found on device in JoeyOS), and the dialogs guidance is for
 * small, focused choices. Drawn in the activity, a page gets the activity's input layer.
 *  - B / Back closes it (a text field being typed in registers after, so it's asked first).
 *  - While open it takes the launcher's own buttons via the ControlBus stack.
 *  - It contains focus: one focus group that cancels any move out of it, so the D-pad can't reach
 *    the screen beneath, and that screen's focusRestorer puts focus back when the page closes.
 *  - Focus lands on the item marked `Modifier.initialFocus()` as soon as it's attached.
 *  - It swallows touches, so nothing underneath reacts.
 *  - The [HintRow] runs along its bottom.
 */
@Composable
fun JoeyPage(
    onClose: () -> Unit,
    hints: List<Hint>,
    /** The page's own buttons; return true when handled. Unhandled ones do nothing. */
    onControl: (Control) -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onClose)

    val currentOnControl by rememberUpdatedState(onControl)
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { currentOnControl(it); true }
        ControlBus.push(handler)
        onDispose { ControlBus.pop(handler) }
    }

    // A page can be opened by a tap, which leaves the window in touch mode, where Compose won't
    // focus a clickable. A page is a controller screen first, so it asks for keyboard mode on
    // open; opened from the pad it already is, and this does nothing.
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }

    val landing = remember { FocusLanding() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JoeyTheme.colors.page)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
        ) {
            CompositionLocalProvider(LocalFocusLanding provides landing) { content() }
        }
        HintRow(hints)
    }
}
