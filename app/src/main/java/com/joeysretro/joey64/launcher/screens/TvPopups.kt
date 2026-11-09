package com.joeysretro.joey64.launcher.screens

import android.content.Intent
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus

/**
 * Play on a TV with only the remote (approved Android TV mockup 2026-11-09, scratchpad build_tv.py / tv_mock.png,
 * screen 6): "Connect a controller", Bluetooth settings (the TV's own pairing page) or Not now. A controller that
 * connects while it's up starts the game; one paired in the TV's settings starts it when Joey64 is back in front
 * (Android doesn't let an app start a screen from behind another app's).
 */
@Composable
internal fun NeedsControllerPopup(model: LibraryModel) {
    val ask = model.needsController.collectAsStateWithLifecycle().value ?: return
    val context = LocalContext.current
    val start by rememberUpdatedState<(Intent) -> Unit> { context.startActivity(it) }
    fun check() { if (com.joeysretro.joey64.launcher.layout.Tv.controllerConnected(context)) model.controllerConnected(start) }
    DisposableEffect(Unit) {
        val im = context.getSystemService(InputManager::class.java)
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) = check()
            override fun onInputDeviceRemoved(id: Int) {}
            override fun onInputDeviceChanged(id: Int) = check()
        }
        im?.registerInputDeviceListener(l, Handler(Looper.getMainLooper()))
        onDispose { im?.unregisterInputDeviceListener(l) }
    }
    LifecycleResumeEffect(Unit) { check(); onPauseOrDispose { } }
    JoeyDialog(onDismiss = { model.controllerCancelled() }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 520.dp) {
        Column {
            PopupTitle("Connect a controller",
                "N64 games need a controller. Pair one in the TV's Bluetooth settings, and ${ask.game.title} starts as soon as it connects.")
            JoeyRow("Bluetooth settings", Modifier.initialFocus(), detail = "Opens the TV's settings to pair a controller") {
                val opened = listOf(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS).any { action ->
                    try { context.startActivity(Intent(action)); true } catch (e: Exception) { false }
                }
                if (!opened) JLog.w("Joey64Library", "no settings page to open for Bluetooth")
            }
            JoeyRow("Not now") { model.controllerCancelled() }
        }
    }
}
