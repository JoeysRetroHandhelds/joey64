package com.joeysretro.joey64.launcher.screens

import com.joeysretro.joey64.launcher.components.TwoColumns
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.audio.AudioChoices
import com.joeysretro.joey64.audio.AudioKey
import com.joeysretro.joey64.audio.AudioSettings
import com.joeysretro.joey64.audio.AudioStore
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.controls.Control

/**
 * Settings › Audio (approved mockup, 2026-11-07: scratchpad build_audio.py). Left, Sound: Volume
 * (Left / Right on the row), Mute, Mono, Play alongside other apps. Right, Fast-forward: Fast-forward
 * sound; Advanced: Latency. For one game ([target], the header chip) only Volume and Latency can
 * differ; the other rows are greyed, being the same for every game. Y puts the focused row back.
 */
@Composable
fun AudioTab(target: String?, onHints: (List<Hint>) -> Unit, onControl: (((Control) -> Boolean)?) -> Unit, modifier: Modifier = Modifier,
             /** Game settings' changed row to open at. */
             start: AudioKey? = null) {
    val context = LocalContext.current
    val store = remember { AudioStore(context) }
    var version by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") version
    val all = remember(version) { store.all() }
    val own = remember(version, target) { target?.let { store.game(it) } ?: emptyMap() }
    val a = AudioSettings.resolve(all, own)
    var focused by remember { mutableStateOf(start ?: AudioKey.Volume) }
    var volumeFocused by remember { mutableStateOf(false) } // the ◀ ▶ only while it really has focus
    var popup by remember { mutableStateOf<AudioKey?>(null) }
    val game = target != null

    // From the store, not this composition's maps: the Y handler and popups can hold an older composition's
    // functions, which wrote the tab as it was then and undid the changes since (code audit 3, M26).
    fun set(k: AudioKey, value: String) {
        if (target == null) store.saveAll(store.all() + (k to value)) else store.saveGame(target, (store.game(target) ?: emptyMap()) + (k to value))
        version++
    }
    fun reset(k: AudioKey) {
        if (target == null) store.saveAll(store.all() - k) else store.saveGame(target, (store.game(target) ?: emptyMap()) - k)
        version++
    }
    fun dot(k: AudioKey) = game && k in own
    // A game's greyed rows: the same for every game, so nothing to change or reset here.
    fun fixed(k: AudioKey) = game && k !in AudioKey.PER_GAME

    // The handler is set once per target; it calls this composition's Y through rememberUpdatedState.
    val onY by rememberUpdatedState { if (popup == null && !fixed(focused)) { reset(focused); true } else false }
    LaunchedEffect(target) {
        onControl { ctl -> if (ctl == Control.Search) onY() else false }
    }
    LaunchedEffect(target, focused) {
        val y = Hint("Y", if (target == null) "Reset to default" else "Use All games")
        onHints(when {
            fixed(focused) -> listOf(Hint("L/R", "Tab"), Hint("B", "Back"))
            focused == AudioKey.Volume -> listOf(Hint("◀▶", "Change"), y, Hint("L/R", "Tab"), Hint("B", "Back"))
            else -> listOf(Hint("A", "Change"), y, Hint("L/R", "Tab"), Hint("B", "Back"))
        })
    }

    Column(modifier.fillMaxSize()) {
        @Composable
        fun row(k: AudioKey, label: String) =
            JoeyRow(label, Modifier.initialFocus(start == k), value = AudioChoices.label(k, a[k]), sideways = true, enabled = !fixed(k),
                dot = dot(k), experimental = AudioChoices.of(k).any { it.value == a[k] && it.experimental },
                onFocused = { if (it) focused = k }) { popup = k }
        TwoColumns(Modifier.weight(1f), left = {
            SectionLabel("Sound")
            StepRow("Volume", "${a.volume}%", AudioChoices.volumeSteps, a.volume, volumeFocused, first = start == null || start == AudioKey.Volume,
                dot = dot(AudioKey.Volume), onFocused = { volumeFocused = it; if (it) focused = AudioKey.Volume }) { set(AudioKey.Volume, it.toString()) }
            row(AudioKey.Mute, "Mute")
            row(AudioKey.Mono, "Mono")
            row(AudioKey.Apps, "Play alongside other apps")
        }) {
            SectionLabel("Fast-forward")
            row(AudioKey.FfSound, "Fast-forward sound")
            SectionLabel("Advanced")
            row(AudioKey.Latency, "Latency")
        }
        Spacer(Modifier.height(6.dp))
        val (title, text) = describe(focused)
        DescriptionLine(title, when {
            dot(focused) -> "Changed for this game. All games uses ${AudioChoices.label(focused, AudioSettings.resolve(all, null)[focused])}."
            fixed(focused) -> "The same for every game. Change it on All games."
            else -> text
        })
    }

    popup?.let { k ->
        AudioChoicePopup(k, a[k], onDismiss = { popup = null }) { set(k, it); popup = null }
    }
}

private fun describe(k: AudioKey): Pair<String, String> = when (k) {
    AudioKey.Volume -> "Volume" to "How loud the game is, on top of the device's own volume."
    AudioKey.Mute -> "Mute" to "No sound. The game still runs at its own speed."
    AudioKey.Mono -> "Mono" to "The same sound from both sides, for one earbud or a mono speaker."
    AudioKey.Apps -> "Play alongside other apps" to "Keep music from other apps playing during a game. Applies when the game next starts."
    AudioKey.FfSound -> "Fast-forward sound" to "What you hear while the game runs faster."
    AudioKey.Latency -> "Latency" to "How far the sound runs behind the picture. Low is experimental. Applies when the game next starts."
}

private fun title(k: AudioKey) = when (k) {
    AudioKey.Apps -> "Play alongside other apps"
    AudioKey.FfSound -> "Fast-forward sound"
    else -> k.name
}

/** The approved small popup (as Video's): a title and the choices with their lines, the tick on the one in use. */
@Composable
private fun AudioChoicePopup(k: AudioKey, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle(title(k), describe(k).second) // the row's description (review sheet L9)
            for (ch in AudioChoices.of(k)) {
                val on = ch.value == current
                JoeyRow(ch.label, Modifier.initialFocus(on), detail = ch.detail, experimental = ch.experimental,
                    trailing = if (on) { _ -> PickerTick() } else null) { onPick(ch.value) }
            }
        }
    }
}
