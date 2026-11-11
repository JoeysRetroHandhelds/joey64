package com.joeysretro.joey64.launcher.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * A text field that behaves on a controller the TV way, carried over from JoeyOS (found on
 * device there: a plain field popped the keyboard just by being landed on, swallowed B, and
 * trapped the D-pad). Used for the search box and RetroAchievements sign-in.
 *  - Landing on it only highlights it. A, or a tap, starts typing and opens the keyboard.
 *  - While typing, B / Back stops typing and leaves it highlighted; the keyboard's Done stops
 *    typing and moves on ([onDone], or the next item down).
 *  - While the keyboard is up the D-pad belongs to the keyboard.
 */
@Composable
fun ControllerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    /** Enter: moves focus on itself, true when it did (else focus goes back to the box). */
    onDone: (() -> Boolean)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Typing (keyboard up) from the start: a name popup, where typing is the only thing to do. */
    startTyping: Boolean = false,
    /** The keyboard's Done: when set, it's handed the press instead of typing stopping and focus moving on. */
    onSubmit: (() -> Unit)? = null,
    /** GameShark codes (Add code): monospace, capitals, several lines; Enter starts a new line and B stops typing. */
    code: Boolean = false,
) {
    val c = JoeyTheme.colors
    var typing by remember { mutableStateOf(startTyping) }
    val fieldFocus = remember { FocusRequester() }
    // The highlight-only stop comes back when typing stops; this puts focus on it as it arrives.
    val backToBox = remember { FocusLanding(armed = false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val (boxSource, boxFocused) = rememberFocusState()
    val (fieldSource, fieldFocused) = rememberFocusState()
    val lit = boxFocused || fieldFocused

    fun stopTyping(moveOn: Boolean) {
        val movedOn = moveOn && (if (onDone != null) onDone() else focusManager.moveFocus(FocusDirection.Down))
        if (!movedOn) backToBox.arm()
        keyboard?.hide()
        typing = false
    }

    val registry = LocalDialogBack.current
    val backHandler: () -> Boolean = remember { { if (typing) { stopTyping(moveOn = false); true } else false } }
    DisposableEffect(registry) {
        registry?.add(backHandler)
        onDispose { registry?.remove(backHandler) }
    }
    TypingBackHandler(typing = typing, inDialog = registry != null) { stopTyping(moveOn = false) }
    // The field is always composed (only its canFocus follows typing), so it is attached when this
    // runs, after the recomposition that turned typing on.
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(typing) {
        if (typing) {
            // A popup that opens typing (New collection): Android ignores the keyboard until the
            // popup's window has focus (found on the Thor: the field had the cursor, no keyboard).
            androidx.compose.runtime.snapshotFlow { windowInfo.isWindowFocused }.first { it }
            fieldFocus.requestFocus()
            keyboard?.show()
        }
    }

    val shape = RoundedCornerShape(JoeyTheme.spacing.rowCorner)
    val textColor = rowTextColor(c, lit, c.text)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.chip, shape)
            .border(1.dp, c.border, shape)
            .rowSelection(lit, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = !code,
                minLines = if (code) 2 else 1,
                interactionSource = fieldSource,
                visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = if (code) KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Default,
                ) else KeyboardOptions(
                    keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (onSubmit != null) onSubmit() else stopTyping(moveOn = true) }),
                textStyle = (if (code) JoeyTheme.type.code else JoeyTheme.type.row).copy(color = textColor),
                cursorBrush = SolidColor(if (c.isDark) c.accent else c.navy),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, style = JoeyTheme.type.row,
                        color = rowTextColor(c, lit, c.textDim))
                    inner()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // Back while the keyboard is up goes to the keyboard, which hides itself and doesn't tell us
                    // (Thor release pass 2026-11-10: typing stayed on, Down only brought the cursor back, so focus
                    // stayed here until Enter). A D-pad Up or Down while typing ends it: Down moves on as Enter
                    // does, Up stays on the box. Not in the code box, where they move between lines.
                    .onPreviewKeyEvent { e ->
                        if (!typing || code || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { stopTyping(moveOn = true); true }
                            Key.DirectionUp -> { stopTyping(moveOn = false); true }
                            else -> false
                        }
                    }
                    .focusRequester(fieldFocus)
                    .focusProperties { canFocus = typing },
            )
            if (!typing) {
                // The focus stop while not typing. Pressing it (A or a tap) starts typing.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .landFocus(backToBox)
                        .clickable(interactionSource = boxSource, indication = null) { typing = true },
                )
            }
        }
        trailing?.invoke()
    }
}
