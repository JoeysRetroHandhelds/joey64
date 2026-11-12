package com.joeysretro.joey64.launcher.components

import androidx.compose.foundation.layout.isImeVisible
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.focusRestorer
import com.joeysretro.joey64.launcher.theme.LocalJoeyColors
import com.joeysretro.joey64.launcher.theme.LocalJoeySpacing
import com.joeysretro.joey64.launcher.theme.LocalJoeyType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
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
 * One window for the launcher's popups (Joey 2026-11-12, F2): where a [PopupHost] is provided (the launcher), a
 * card popup doesn't open a window of its own: it draws in the host's one window ([PopupHostWindow]), so a popup
 * that takes another's place in the same frame (a swap) keeps the window, its dim and blur, with nothing in
 * between (each its own window, the old one's 80 ms close hold and the new one's dim stacked, B3). A popup opened
 * over another draws over it under the same one dim. The game screen has a host of its own (GameOverlay, F2 step 2)
 * for its cards. Full-screen, side-panel and bare popups still open their own window, and the popups inside those
 * do too.
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
    /**
     * False: a tap outside does nothing, Back and B still close it (a netplay room, where a stray tap beside the card
     * left the room; code audit 3, M31).
     */
    tapOutsideCloses: Boolean = true,
    /** Start / Menu: the focused item's options, where the popup has any. */
    onOptions: (() -> Unit)? = null,
    /** Offered every B / Back before the popup closes; return true to consume it. */
    interceptBack: (() -> Boolean)? = null,
    /** Any other pad button (L1/R1, ...), offered on its down edge; both edges are swallowed. */
    onPadButton: ((keyCode: Int) -> Unit)? = null,
    /** The card's width; most popups are a fixed width, a picker with a preview is wider. */
    width: Dp = 360.dp,
    /**
     * A whole page over the paused game (Controls in game, approved mockup 11): no card, the page
     * fills the window above the hint row, on the page colour.
     */
    fullScreen: Boolean = false,
    /** The card near the top of the screen, so the keyboard below doesn't cover it (New collection). */
    atTop: Boolean = false,
    /** Centred, and near the top while the keyboard is up (Sign in to RetroAchievements). */
    riseWithKeyboard: Boolean = false,
    /**
     * A panel down the right side over the running picture (the in-game Picture panel, approved 2026-11-05):
     * no dim and no blur, so changes show on the game as they're made; [width] wide, the window's height.
     */
    sidePanel: Boolean = false,
    /** A line on the right of the hint row (the screenshot viewer's game, date and "1 of 8"). */
    hintEnd: String? = null,
    /**
     * The whole window is the content's, with nothing of the popup's own: no dim, blur, card or hint row
     * (the touch controls' layout editor, approved mockup 2026-11-09 screen 3, draws its own over the game).
     */
    bare: Boolean = false,
    /**
     * Up on the first item goes to the last, Down on the last to the first (the pause menu, Joey 2026-11-10:
     * "up from resume should reach quit"). A held key still stops at the end.
     */
    wrap: Boolean = false,
    content: @Composable () -> Unit,
) {
    val spec = PopupSpec(onDismiss, hints, dismissible, tapOutsideCloses, onOptions, interceptBack, onPadButton, width,
        fullScreen, atTop, riseWithKeyboard, sidePanel, hintEnd, bare, wrap,
        JoeyTheme.colors, JoeyTheme.type, JoeyTheme.spacing, content)
    val entry = remember { PopupEntry(spec) }
    // Handed over while composing, not after: a popup whose contents follow its own state (Options turning into
    // Details) gets its new width in the same frame as its new contents (Thor 2026-11-12: one frame at the old width).
    entry.spec = spec
    val host = LocalPopupHost.current
    if (host != null && !fullScreen && !sidePanel && !bare) {
        // In the host's window (F2): added once, taken out when this popup leaves the composition.
        DisposableEffect(host, entry) {
            host.entries.add(entry)
            onDispose { host.entries.remove(entry) }
        }
        return
    }
    // A full-screen page or side panel in its own window gets a host of its own (F2 step 2): the cards opened over
    // it share one window above it, so one taking another's place there keeps it too. A card in its own window (no
    // host where it was called) keeps the old way: what it opens opens its own window.
    val inner = if (fullScreen || sidePanel || bare) remember { PopupHost() } else null
    Dialog(
        onDismissRequest = { entry.requestClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            // The window fills the screen, so "outside" is our own dim: handled below.
            dismissOnClickOutside = false,
        ),
    ) {
        PopupWindowSetup(blur = !sidePanel && !bare)
        CompositionLocalProvider(LocalPopupHost provides inner) {
            PopupFrame(listOf(entry))
            if (inner != null) PopupHostWindow(inner)
        }
    }
}

/** The launcher's popups, drawn in [PopupHostWindow] (F2). */
@Stable
class PopupHost {
    internal val entries = androidx.compose.runtime.mutableStateListOf<PopupEntry>()
}

/** Where a card popup draws: the launcher's (or the game screen's) one popup window; null (inside a popup's own window): its own. */
val LocalPopupHost = androidx.compose.runtime.staticCompositionLocalOf<PopupHost?> { null }

/** Everything a [JoeyDialog] call was given, and the theme where it was called (its content is drawn in the host). */
internal class PopupSpec(
    val onDismiss: () -> Unit, val hints: List<Hint>, val dismissible: Boolean, val tapOutsideCloses: Boolean,
    val onOptions: (() -> Unit)?, val interceptBack: (() -> Boolean)?, val onPadButton: ((Int) -> Unit)?,
    val width: Dp, val fullScreen: Boolean, val atTop: Boolean, val riseWithKeyboard: Boolean, val sidePanel: Boolean,
    val hintEnd: String?, val bare: Boolean, val wrap: Boolean,
    val colors: com.joeysretro.joey64.launcher.theme.JoeyColors, val type: com.joeysretro.joey64.launcher.theme.JoeyType,
    val spacing: com.joeysretro.joey64.launcher.theme.JoeySpacing, val content: @Composable () -> Unit,
)

/** One open popup: its latest [spec], its own Back handlers and keyboard. */
@Stable
internal class PopupEntry(initial: PopupSpec) {
    var spec by androidx.compose.runtime.mutableStateOf(initial)
    val backRegistry = DialogBackRegistry()
    val ime = DialogIme()

    /** The one way out, for Back, B and a tap outside alike: typing steps back first, then an open keyboard closes, then the popup (if it may). */
    fun requestClose() {
        val s = spec
        when {
            s.interceptBack?.invoke() == true -> {}
            backRegistry.handle() -> {}
            ime.isUp() -> ime.hide()
            s.dismissible -> s.onDismiss()
        }
    }

    fun tapOutside() { if (spec.dismissible && spec.tapOutsideCloses) requestClose() }
}

/**
 * The launcher's one popup window (F2): up while any card popup is open, so one popup taking another's place
 * keeps it. Put once at the launcher's root, under the theme; popups find it through [LocalPopupHost].
 */
@Composable
fun PopupHostWindow(host: PopupHost) {
    if (host.entries.isEmpty()) return
    Dialog(
        onDismissRequest = { host.entries.lastOrNull()?.requestClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        PopupWindowSetup(blur = true)
        CompositionLocalProvider(LocalPopupHost provides host) {
            PopupFrame(host.entries.toList())
        }
    }
}

/**
 * A popup window's own setup. A dialog is its own window with its own bar state. Set while the popup is first
 * composed, before its window draws, so it is never born showing the navigation bar for a frame (found in JoeyOS
 * and Chameleon: read as the screen flashing). remember runs it during that first composition, once per window,
 * and keeps the window it set up.
 */
@Composable
private fun PopupWindowSetup(blur: Boolean) {
    val view = LocalView.current
    val blurPx = with(LocalDensity.current) { JoeyTheme.spacing.popupBlur.roundToPx() }
    remember(view) {
        (view.parent as? DialogWindowProvider)?.window?.also { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            window.setDimAmount(0f)
            window.setWindowAnimations(com.joeysretro.joey64.R.style.JoeyPopupWindow)
            if (blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window.windowManager.isCrossWindowBlurEnabled) {
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
    // A new dialog window starts in touch mode, where Compose won't focus a clickable, so
    // nothing looked selected until a second press (found on device in JoeyOS and Chameleon).
    // Launched ahead of the content's effects, so it's in place before an item asks for focus.
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
}

/**
 * What a popup window draws: one dim above the hint row (a tap on it is the top popup's "outside"), each popup's
 * card over the ones before, and the top popup's hint row. [entries] bottom first; the last has focus and keys.
 */
@Composable
private fun PopupFrame(entries: List<PopupEntry>) {
    val top = entries.lastOrNull() ?: return
    val ts = top.spec
    CompositionLocalProvider(LocalJoeyColors provides ts.colors, LocalJoeyType provides ts.type, LocalJoeySpacing provides ts.spacing) {
        val view = LocalView.current
        val c = JoeyTheme.colors
        // Its own window: laid out at the launcher's debug size too, and measured for its own
        // ScreenLayout (in a game, the game's window provides none: its pages were always two columns).
        val isTv = remember { com.joeysretro.joey64.launcher.layout.Tv.isTv(view.context) }
        val sidePanel = ts.sidePanel
        val bare = ts.bare
        val latestTop by rememberUpdatedState(top)
        com.joeysretro.joey64.launcher.layout.DevFrame {
        // On a TV the popup and its hint row stay inside the safe area; the dim still covers the edges (TV mockup screens 5-6).
        Box(Modifier.fillMaxSize().then(if (isTv && !sidePanel && !bare) Modifier.background(c.scrim) else Modifier)
            .then(if (isTv) Modifier.padding(horizontal = com.joeysretro.joey64.launcher.layout.Tv.SAFE_SIDES,
                vertical = com.joeysretro.joey64.launcher.layout.Tv.SAFE_ENDS) else Modifier)) {
        com.joeysretro.joey64.launcher.layout.ProvideScreenLayout(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // The dim: everything above the hint row, once however many popups are open. A tap on it is "outside".
            // A tap handler, not a clickable: a clickable is a focus target, and since it holds the card, a tap
            // on it left focus on the dim with the D-pad unable to reach an option (found on the
            // Thor in a popup that must be answered).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (sidePanel || bare || isTv) Modifier else Modifier.background(c.scrim))
                    .then(if (bare) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { latestTop.tapOutside() } }),
            ) {
                for (e in entries) androidx.compose.runtime.key(e) {
                    val es = e.spec
                    CompositionLocalProvider(LocalJoeyColors provides es.colors, LocalJoeyType provides es.type, LocalJoeySpacing provides es.spacing) {
                        PopupCard(e, active = e === top)
                    }
                }
            }
            if (!bare) CompositionLocalProvider(LocalPageHints provides null) { HintRow(ts.hints, end = ts.hintEnd) }
        }
        } } }
    }
}

/** One popup's card (or page, panel, bare content) in its place over the dim; [active]: the top one, with focus and keys. */
@Composable
private fun PopupCard(entry: PopupEntry, active: Boolean) {
    val sp = entry.spec
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    SideEffect {
        entry.ime.isUp = { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        entry.ime.hide = { keyboard?.hide() }
    }
    val isActive by rememberUpdatedState(active)
    val landing = remember { FocusLanding() }
    val body = remember { FocusRequester() }
    // A long press on a row: its key-only actions, run as this popup's own keys run them (review sheet M30).
    val latest by rememberUpdatedState(sp)
    val rowActions = rememberRowActionsHost({ latest.hints }) { key ->
        when (key) {
            "Start" -> latest.onOptions?.invoke()
            "X" -> latest.onPadButton?.invoke(KeyEvent.KEYCODE_BUTTON_X)
            "Y" -> latest.onPadButton?.invoke(KeyEvent.KEYCODE_BUTTON_Y)
        }
    }
    var aDownTime by remember { mutableLongStateOf(-1L) }
    val s = JoeyTheme.spacing
    val c = JoeyTheme.colors
    val fullScreen = sp.fullScreen
    val sidePanel = sp.sidePanel
    val bare = sp.bare
    // The window's own insets: the keyboard opens over this window, not the activity's.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val atTop = sp.atTop || (sp.riseWithKeyboard && androidx.compose.foundation.layout.WindowInsets.Companion.isImeVisible)
    val isTv = remember { com.joeysretro.joey64.launcher.layout.Tv.isTv(view.context) }
    val okHold = remember { com.joeysretro.joey64.launcher.layout.RemoteKeys.OkHold() }
    // Each popup puts focus back on show (a popup in the shared window after a tap on the one under it, F2).
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (atTop) Modifier.padding(top = 44.dp) else Modifier)
            // A card never touches the screen's edges: on a narrow screen it's the screen's width less these.
            .then(if (fullScreen || sidePanel || bare) Modifier else Modifier.padding(horizontal = 16.dp, vertical = 8.dp)),
        contentAlignment = if (sidePanel) Alignment.CenterEnd else if (atTop) Alignment.TopCenter else Alignment.Center,
    ) {
        val shape = RoundedCornerShape(s.popupCorner)
        Box(
            modifier = Modifier
                .then(if (bare) Modifier.fillMaxSize()
                else if (fullScreen) Modifier.fillMaxSize().background(c.page)
                else if (sidePanel) Modifier.fillMaxHeight().widthIn(max = sp.width).fillMaxWidth().background(c.surface)
                    .border(1.dp, c.border).padding(horizontal = 8.dp, vertical = 10.dp)
                else Modifier
                    .widthIn(max = sp.width)
                    .fillMaxWidth()
                    .then(if (c.isDark) Modifier else Modifier.shadow(18.dp, shape))
                    .background(c.surface, shape)
                    .border(1.dp, c.border, shape))
                // Taps on the card mustn't count as outside (the options take their own first).
                .then(if (bare) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { } })
                .then(if (fullScreen || sidePanel || bare) Modifier else Modifier.padding(horizontal = 10.dp, vertical = 12.dp))
                .focusRequester(body)
                // Compose turns a Back key it sees into a focus move out of the group
                // (FocusDirection.Exit). In a popup that must be answered nothing closes,
                // so focus fell out of the card and the D-pad was dead (found on the Thor:
                // Android's Back; the pad's B is handled above and never gets there).
                // A popup under the top one lets focus go (to the one opened over it) and takes none until it's on top again.
                .focusProperties {
                    onExit = { if (isActive) cancelFocusChange() }
                    // Focus coming into the popup (the window getting focus) goes straight to the marked row (A7).
                    onEnter = {
                        if (!isActive) cancelFocusChange()
                        else {
                            val t = landing.target
                            if (landing.pending && t != null && t.requestFocus()) landing.landedAt = landing.armedAt
                        }
                    }
                }
                .focusRestorer()
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (!isActive) return@onPreviewKeyEvent false
                    val native = event.nativeKeyEvent
                    val p = latest
                    com.joeysretro.joey64.launcher.layout.RemoteKeys.note(native, isTv)
                    // A TV remote's OK: a press on release, Hold OK is Start (TV mockup screen 5).
                    if (isTv && okHold.handle(native, click = {
                            view.post {
                                view.dispatchKeyEvent(com.joeysretro.joey64.launcher.layout.RemoteKeys.synth(KeyEvent.ACTION_DOWN))
                                view.dispatchKeyEvent(com.joeysretro.joey64.launcher.layout.RemoteKeys.synth(KeyEvent.ACTION_UP))
                            }
                        }, hold = { if (!RowActions.holdOk(rowActions)) p.onOptions?.invoke() })) return@onPreviewKeyEvent true
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                            if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) aDownTime = native.downTime
                            if (native.action == KeyEvent.ACTION_UP && !native.isCanceled && native.downTime == aDownTime) {
                                aDownTime = -1L
                                // Posted so it isn't dispatched from inside this dispatch.
                                view.post {
                                    view.dispatchKeyEvent(com.joeysretro.joey64.launcher.layout.RemoteKeys.synth(KeyEvent.ACTION_DOWN))
                                    view.dispatchKeyEvent(com.joeysretro.joey64.launcher.layout.RemoteKeys.synth(KeyEvent.ACTION_UP))
                                }
                            }
                            true
                        }
                        KeyEvent.KEYCODE_BUTTON_B -> {
                            if (native.action == KeyEvent.ACTION_UP && !native.isCanceled) entry.requestClose()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!p.wrap) false else {
                            if (native.action == KeyEvent.ACTION_DOWN) {
                                val up = native.keyCode == KeyEvent.KEYCODE_DPAD_UP
                                val dir = if (up) FocusDirection.Up else FocusDirection.Down
                                // At the end: walk to the other end (the rows are few; capped all the same).
                                if (!focusManager.moveFocus(dir) && native.repeatCount == 0) {
                                    val back = if (up) FocusDirection.Down else FocusDirection.Up
                                    var n = 0
                                    while (n++ < 64 && focusManager.moveFocus(back)) {}
                                }
                            }
                            true
                        }
                        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                            if (native.action == KeyEvent.ACTION_UP && !native.isCanceled) p.onOptions?.invoke()
                            true
                        }
                        else -> {
                            val pad = KeyEvent.isGamepadButton(native.keyCode)
                            if (pad && native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) p.onPadButton?.invoke(native.keyCode)
                            pad
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalDialogBack provides entry.backRegistry, LocalFocusLanding provides landing, LocalRowActions provides rowActions,
                LocalPageHints provides null, LocalListPanel provides null,
                LocalPopupBack provides (if (fullScreen && !bare) ({ entry.requestClose() }) else null)) {
                sp.content()
            }
        }
    }
    // Nothing marked: enter the popup at its first item. After the content, so a marked item
    // that's already attached asks first. (view.requestFocus() only focuses the window, not an
    // item in it: found on device in JoeyOS.)
    // Both wait for the window's focus (A7, 2026-11-11): this one ran at once, before the window had focus, while a
    // marked row's own request ([landFocus]) waited for it, so the first row lit and then focus jumped to the marked
    // one (Player 2 › Controller moving to None). Keyed the same, the marked row's effect, launched first, lands first.
    // In the shared window (F2) a popup under another is on top again once that one closes: focus goes back into it,
    // to the row it was on ([focusRestorer]).
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    var wasBelow by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(windowFocused, active) {
        if (!active) { wasBelow = true; return@LaunchedEffect }
        if (windowFocused && landing.pending) body.requestFocus(FocusDirection.Enter)
        else if (windowFocused && wasBelow) { wasBelow = false; body.requestFocus(FocusDirection.Enter) }
    }
}

/**
 * A full-screen popup's way back by touch (review sheet L2, approved 2026-11-11): it fills the screen, so there's no
 * outside to tap. [PageHeader] draws [PopupBackButton] before the title when this is set; a full-screen popup without
 * a title (the screenshot viewer, Place the game) draws it in its top-left corner. A tap does what B does.
 */
val LocalPopupBack = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/** The ‹ before a full-screen popup's title: a 30 dp round chip, touch only (B is the key). Nothing outside one. */
@Composable
fun PopupBackButton(modifier: Modifier = Modifier) {
    val back = LocalPopupBack.current ?: return
    // Touch only: without a touchscreen (a TV, a controller-only box) there's nothing to tap, so no back arrow (Joey 2026-11-11).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (!androidx.compose.runtime.remember { com.joeysretro.joey64.launcher.layout.Tv.hasTouch(ctx) }) return
    val c = JoeyTheme.colors
    Box(modifier.size(30.dp).background(c.chip, androidx.compose.foundation.shape.CircleShape)
        .focusProperties { canFocus = false }
        .clickable(onClickLabel = "Back") { back() }
        .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center) {
        Text("‹", style = JoeyTheme.type.heading, color = c.text)
    }
}

/** A popup's title and the grey line under it. */
@Composable
fun PopupTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 6.dp)) {
        Text(title, style = JoeyTheme.type.popupTitle, color = JoeyTheme.colors.text, modifier = Modifier.semantics { heading() })
        if (subtitle != null) Text(subtitle, style = JoeyTheme.type.note, color = JoeyTheme.colors.textDim)
    }
}

/** A popup's note under its options. */
@Composable
fun PopupNote(text: String) {
    Text(text, style = JoeyTheme.type.note, color = JoeyTheme.colors.textDim,
        modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(top = 8.dp))
}

/** A line between a popup's options and the ones that act on more than one thing ("Do the same for the other 2"). */
@Composable
fun PopupDivider() {
    androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = JoeyTheme.colors.separator,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
}

/** Lets [JoeyDialog]'s exit route, which lives outside the dialog window, reach that window's keyboard. */
internal class DialogIme {
    var isUp: () -> Boolean = { false }
    var hide: () -> Unit = {}
}

/**
 * The top of a prompt popup (the updater's, approved 2026-11-05): an icon over the title, and an
 * optional tag beside the title ("Testing build").
 */
@Composable
fun PromptHeader(@androidx.annotation.DrawableRes icon: Int, title: String, tag: String? = null, @androidx.annotation.DrawableRes tagIcon: Int? = null) {
    val c = JoeyTheme.colors
    Column(Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(top = 2.dp, bottom = 4.dp)) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), contentDescription = null,
            tint = c.gold, modifier = Modifier.padding(bottom = 6.dp).size(22.dp))
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = JoeyTheme.type.popupTitle, color = c.text)
            if (tag != null) {
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(start = 8.dp).background(c.chip, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tagIcon != null) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(tagIcon), contentDescription = null,
                        tint = c.gold, modifier = Modifier.padding(end = 3.dp).size(12.dp))
                    Text(tag, style = JoeyTheme.type.label, color = c.gold)
                }
            }
        }
    }
}

/** A note in a popup, in a box with the info icon: how a choice will work ("Y can't sense how hard it's pressed ..."). */
@Composable
fun PopupInfoNote(text: String) {
    val c = JoeyTheme.colors
    androidx.compose.foundation.layout.Row(
        Modifier.padding(horizontal = 6.dp).padding(top = 4.dp, bottom = 2.dp).fillMaxWidth()
            .background(c.page, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.joeysretro.joey64.R.drawable.ic_info), contentDescription = null,
            tint = c.gold, modifier = Modifier.padding(end = 8.dp).size(16.dp))
        Text(text, style = JoeyTheme.type.small, color = c.textSecondary)
    }
}

/** A warning in a popup, in a box with the warning triangle: what a choice takes away ("L2 is Z for Player 1 ..."). */
@Composable
fun PopupWarnNote(text: String) {
    val c = JoeyTheme.colors
    androidx.compose.foundation.layout.Row(
        Modifier.padding(horizontal = 6.dp).padding(top = 4.dp, bottom = 2.dp).fillMaxWidth()
            .background(c.warnBg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.joeysretro.joey64.R.drawable.ic_alert_triangle), contentDescription = null,
            tint = c.warn, modifier = Modifier.padding(end = 8.dp, top = 1.dp).size(16.dp))
        Text(text, style = JoeyTheme.type.small, color = c.warn)
    }
}

/** A prompt popup's text, with its bold parts (a version number) as the caller marks them. */
@Composable
fun PromptText(text: androidx.compose.ui.text.AnnotatedString) {
    Text(text, style = JoeyTheme.type.body, color = JoeyTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 8.dp))
}

/** "What's new in 0.3.0" and the release's notes, in a box; long notes scroll inside it. */
@Composable
fun PromptNotes(heading: String, text: String) {
    val c = JoeyTheme.colors
    Column(
        Modifier
            .padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 8.dp)
            .fillMaxWidth()
            .background(c.page, RoundedCornerShape(8.dp))
            .heightIn(max = 140.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(heading, style = JoeyTheme.type.label, color = c.text, modifier = Modifier.padding(bottom = 4.dp))
        Text(text, style = JoeyTheme.type.small, color = c.textSecondary)
    }
}
