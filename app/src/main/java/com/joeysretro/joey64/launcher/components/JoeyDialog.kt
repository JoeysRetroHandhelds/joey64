package com.joeysretro.joey64.launcher.components

import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * The one popup shell: a real Dialog window, carried over from JoeyOS (ui/components/JoeyDialog.kt)
 * and Chameleon's windows rewrite (launcher/docs/windows-rewrite.md: the in-composition overlay
 * was a dead end; a real window contains focus, answers Back and restores focus on close).
 *
 * Joey64's approved layout adds one thing: the hint row stays lit under a popup and shows the
 * popup's hints, while the dim and blur cover everything else. So the dialog window fills the
 * screen, draws its own dim above the hint row (the window's own dim is off), its own [HintRow]
 * at the bottom, and the popup card in the middle. The blur is the window's background blur
 * (Android 12+, where the device allows cross-window blur); elsewhere it's dim only.
 *
 * Focus on open: the item marked `Modifier.initialFocus()` takes it; with no mark, focus enters
 * the popup at its first item.
 *
 * The dialog's window gets keys before the activity, so pad normalisation is done here too: A /
 * Select press the focused item (on release, only a release whose press this window saw), B takes
 * the same exit route as Back, Start calls [onOptions], and every other pad button is swallowed on
 * both edges so Android can't synthesise a fallback.
 */
@Composable
fun JoeyDialog(
    onDismiss: () -> Unit,
    hints: List<Hint>,
    /** False for a popup that must be answered: Back, B and a tap outside do nothing. */
    dismissible: Boolean = true,
    /** Start / Menu: the focused item's options, where the popup has any. */
    onOptions: (() -> Unit)? = null,
    /** Offered every B / Back before the popup closes; return true to consume it. */
    interceptBack: (() -> Boolean)? = null,
    /** Any other pad button (L1/R1, ...), offered on its down edge; both edges are swallowed. */
    onPadButton: ((keyCode: Int) -> Unit)? = null,
    /** The card's width; most popups are a fixed width, a picker with a preview is wider. */
    width: Dp = 360.dp,
    content: @Composable () -> Unit,
) {
    val backRegistry = remember { DialogBackRegistry() }
    val ime = remember { DialogIme() }

    /** The one way out, for Back, B and a tap outside alike: typing steps back first, then an open keyboard closes, then the popup (if it may). */
    fun requestClose() {
        when {
            interceptBack?.invoke() == true -> {}
            backRegistry.handle() -> {}
            ime.isUp() -> ime.hide()
            dismissible -> onDismiss()
        }
    }

    Dialog(
        onDismissRequest = ::requestClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            // The window fills the screen, so "outside" is our own dim: handled below.
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        val blurPx = with(LocalDensity.current) { JoeyTheme.spacing.popupBlur.roundToPx() }
        // A dialog is its own window with its own bar state. Set while the popup is first
        // composed, before its window draws, so it is never born showing the navigation bar for
        // a frame (found in JoeyOS and Chameleon: read as the screen flashing). remember runs it
        // during that first composition, once per window, and keeps the window it set up.
        remember(view) {
            (view.parent as? DialogWindowProvider)?.window?.also { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                window.setDimAmount(0f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window.windowManager.isCrossWindowBlurEnabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply { blurBehindRadius = blurPx }
                }
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                WindowInsetsControllerCompat(window, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        val keyboard = LocalSoftwareKeyboardController.current
        SideEffect {
            ime.isUp = { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            ime.hide = { keyboard?.hide() }
        }

        // A new dialog window starts in touch mode, where Compose won't focus a clickable, so
        // nothing looked selected until a second press (found on device in JoeyOS and Chameleon).
        // Launched ahead of the content's effects, so it's in place before an item asks for focus.
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }

        val landing = remember { FocusLanding() }
        val body = remember { FocusRequester() }
        var aDownTime by remember { mutableLongStateOf(-1L) }
        val c = JoeyTheme.colors
        val s = JoeyTheme.spacing

        Column(Modifier.fillMaxSize()) {
            // The dim: everything above the hint row. A tap on it is "outside".
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(c.scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (dismissible) requestClose()
                    },
                contentAlignment = Alignment.Center,
            ) {
                val shape = RoundedCornerShape(s.popupCorner)
                Box(
                    modifier = Modifier
                        .widthIn(max = width)
                        .fillMaxWidth()
                        .then(if (c.isDark) Modifier else Modifier.shadow(18.dp, shape))
                        .background(c.surface, shape)
                        .border(1.dp, c.border, shape)
                        // Taps on the card mustn't count as outside.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = false) {}
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                        .focusRequester(body)
                        .focusGroup()
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            when (native.keyCode) {
                                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                                    if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) aDownTime = native.downTime
                                    if (native.action == KeyEvent.ACTION_UP && !native.isCanceled && native.downTime == aDownTime) {
                                        aDownTime = -1L
                                        // Posted so it isn't dispatched from inside this dispatch.
                                        view.post {
                                            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                                            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                                        }
                                    }
                                    true
                                }
                                KeyEvent.KEYCODE_BUTTON_B -> {
                                    if (native.action == KeyEvent.ACTION_UP && !native.isCanceled) requestClose()
                                    true
                                }
                                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                                    if (native.action == KeyEvent.ACTION_UP && !native.isCanceled) onOptions?.invoke()
                                    true
                                }
                                else -> {
                                    val pad = KeyEvent.isGamepadButton(native.keyCode)
                                    if (pad && native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) onPadButton?.invoke(native.keyCode)
                                    pad
                                }
                            }
                        },
                ) {
                    CompositionLocalProvider(LocalDialogBack provides backRegistry, LocalFocusLanding provides landing) {
                        content()
                    }
                }
            }
            HintRow(hints)
        }
        // Nothing marked: enter the popup at its first item. After the content, so a marked item
        // that's already attached asks first. (view.requestFocus() only focuses the window, not an
        // item in it: found on device in JoeyOS.)
        LaunchedEffect(Unit) {
            if (landing.pending) body.requestFocus(FocusDirection.Enter)
        }
    }
}

/** A popup's title and the grey line under it. */
@Composable
fun PopupTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 6.dp)) {
        Text(title, style = JoeyTheme.type.popupTitle, color = JoeyTheme.colors.text)
        if (subtitle != null) Text(subtitle, style = JoeyTheme.type.note, color = JoeyTheme.colors.textDim)
    }
}

/** A popup's note under its options. */
@Composable
fun PopupNote(text: String) {
    Text(text, style = JoeyTheme.type.note, color = JoeyTheme.colors.textDim,
        modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(top = 8.dp))
}

/** Lets [JoeyDialog]'s exit route, which lives outside the dialog window, reach that window's keyboard. */
private class DialogIme {
    var isUp: () -> Boolean = { false }
    var hide: () -> Unit = {}
}
