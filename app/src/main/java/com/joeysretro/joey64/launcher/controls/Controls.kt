package com.joeysretro.joey64.launcher.controls

import android.util.Log
import android.view.KeyEvent

/**
 * The buttons the launcher speaks, and the one place hardware becomes them.
 *
 * Carried over from JoeyOS (JoeyOS/docs/input-rewrite.md) and Chameleon's input rewrite
 * (launcher/docs/input-rewrite.md), which both follow developer.android.com/training/tv/
 * get-started/controllers and .../navigation and "Focus in Compose":
 *
 *  - **Navigation, confirm and cancel are the platform's.** The D-pad moves Compose focus on its
 *    own; confirm is DPAD centre and cancel is Back, which every `clickable` and `BackHandler`
 *    already answers. A pad's A (and Select) and B are those same intentions with keycodes
 *    Compose doesn't recognise, so [Controls.translate] turns them into the ones it does.
 *  - **Everything else is a [Control]**, delivered by [ControlBus] to one owner.
 *
 * No hold-A: one button, one job. Options is Start (Joey, 2026-11-05), and by touch a long-press.
 */
enum class Control {
    /** Start (or a remote's Menu): the focused item's options popup. */
    Options,

    /** Y: Search. */
    Search,

    /** X: the screen's extra action (Clear on the Controls list; nothing elsewhere yet). */
    Extra,

    /** L1 / R1: the previous / next tab, or player on Controls. */
    TabPrev,
    TabNext,

    /** L2 / R2: a page up / down in a long list or grid. */
    PagePrev,
    PageNext,
}

object Controls {

    /** The intent a keycode carries, or null when it is navigation, confirm, cancel or unknown. */
    fun intentFor(keyCode: Int): Control? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_MENU -> Control.Options
        KeyEvent.KEYCODE_BUTTON_Y -> Control.Search
        KeyEvent.KEYCODE_BUTTON_X -> Control.Extra
        KeyEvent.KEYCODE_BUTTON_L1 -> Control.TabPrev
        KeyEvent.KEYCODE_BUTTON_R1 -> Control.TabNext
        KeyEvent.KEYCODE_BUTTON_L2 -> Control.PagePrev
        KeyEvent.KEYCODE_BUTTON_R2 -> Control.PageNext
        else -> null
    }

    /**
     * The platform keycode a pad button stands for, or null to pass the key through untouched.
     * Compose's `clickable` answers DPAD centre and Enter, never BUTTON_A, and `BackHandler`
     * answers Back, never BUTTON_B; Android's own fallback for these is unreliable on handhelds
     * (learned in Chameleon), so they are translated here. The TV controllers guide lists
     * BUTTON_SELECT as a selection key too.
     */
    fun translate(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_SELECT -> KeyEvent.KEYCODE_DPAD_CENTER
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
        else -> null
    }
}

/**
 * The owner of control intents. One handler is in charge at a time, the top of a stack: the
 * library sets the base, and a page opened over it (Settings, Controls, Achievements) pushes its
 * own while open, so a button is never handled in two places that drift apart.
 */
object ControlBus {
    private val handlers = ArrayDeque<(Control) -> Boolean>()
    private var base: ((Control) -> Boolean)? = null
    private var loggedNoHandler = false

    /**
     * Sets the base handler under any pages already open. Cleared only by [clearHandler] with the
     * same handler: when Android recreates a screen, the new one sets its handler before the old
     * one is disposed (found on the Thor in JoeyOS: the old one wiping the stack left Start, X, Y
     * and the shoulders dead).
     */
    fun setHandler(handler: (Control) -> Boolean) {
        base?.let { handlers.remove(it) }
        base = handler
        handlers.addFirst(handler)
    }

    fun clearHandler(handler: (Control) -> Boolean) {
        if (base !== handler) return
        handlers.remove(handler)
        base = null
    }

    /** A page takes the buttons while it's open. */
    fun push(handler: (Control) -> Boolean) { handlers.addLast(handler) }
    fun pop(handler: (Control) -> Boolean) { handlers.remove(handler) }

    /** Offers an intent to the handler in charge. True when it was consumed. */
    fun dispatch(control: Control): Boolean {
        val handler = handlers.lastOrNull()
        if (handler == null) {
            if (!loggedNoHandler) { loggedNoHandler = true; Log.w(TAG, "$control pressed but nothing is handling buttons") }
            return false
        }
        return handler(control)
    }

    private const val TAG = "Joey64Controls"
}

/**
 * The activity's whole input layer (an activity's `dispatchKeyEvent` calls [dispatch]). Hardware
 * is cleaned up here and nothing else:
 *  - A / Select become DPAD centre, sent as a press on release, so a hold can never turn into a
 *    long-press. Only a release whose press this activity saw counts: quitting a game with A
 *    presses it in the game, and the release lands here.
 *  - B becomes Back.
 *  - The launcher's own buttons become one [Control] on the [ControlBus], both edges consumed so
 *    Android never synthesises a fallback (an unhandled Start's fallback is a confirm).
 *  - Everything else (the D-pad, a remote's centre and Back) passes through to the focus system.
 *  - A pad button that isn't mapped is logged once, so a new device names itself.
 */
class KeyNormaliser(private val superDispatch: (KeyEvent) -> Boolean) {
    private var centerDownTime = -1L
    private val loggedUnknownKeys = mutableSetOf<Int>()

    fun dispatch(event: KeyEvent): Boolean {
        Controls.translate(event.keyCode)?.let { keyCode ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) centerDownTime = event.downTime
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.downTime == centerDownTime) {
                    centerDownTime = -1L
                    superDispatch(event.withKeyCode(keyCode, KeyEvent.ACTION_DOWN))
                    superDispatch(event.withKeyCode(keyCode, KeyEvent.ACTION_UP))
                }
            } else {
                superDispatch(event.withKeyCode(keyCode, event.action))
            }
            return true
        }
        Controls.intentFor(event.keyCode)?.let { control ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) ControlBus.dispatch(control)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            KeyEvent.isGamepadButton(event.keyCode) && loggedUnknownKeys.add(event.keyCode)) {
            Log.i("Joey64Controls", "Unmapped gamepad button ${KeyEvent.keyCodeToString(event.keyCode)} " +
                "from ${event.device?.name ?: "unknown device"}")
        }
        return superDispatch(event)
    }

    private fun KeyEvent.withKeyCode(keyCode: Int, action: Int) = KeyEvent(
        downTime, eventTime, action, keyCode, 0, metaState, deviceId, scanCode, flags, source
    )
}
