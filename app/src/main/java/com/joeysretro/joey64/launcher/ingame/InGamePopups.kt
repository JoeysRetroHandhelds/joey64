package com.joeysretro.joey64.launcher.ingame

import androidx.compose.runtime.Composable
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.components.JoeyToast
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * A pop-up over the running game (plan, Phase 3: the pop-up system): the approved toast's shape in
 * the top left corner, with nothing to press. [key] names what it's about: a newer pop-up with the
 * same key takes the place of the one showing or waiting (pressing the slot hotkey five times
 * shows the last slot, not five pop-ups in a row).
 */
data class InGamePopup(val key: String, val kind: Kind, val label: String, val name: String, val line: String) {
    enum class Kind { Saved, Loaded, Slot, Screenshot, Reset, Problem }
}

/**
 * The top-left queue: one pop-up at a time, each for [stayMs] (Settings › Pop-ups stay for, 5 s by
 * default as approved), then the next. Times are passed in, so it's tested without a clock.
 * (The bottom-right stack is for achievement badges, Phase 7.)
 */
class PopupQueue(private val stayMs: Long = 5_000) {
    var current: InGamePopup? = null
        private set
    /** When [current] goes. */
    var until = 0L
        private set
    private val waiting = ArrayDeque<InGamePopup>()

    fun add(p: InGamePopup, now: Long) {
        val showing = current
        when {
            showing == null || showing.key == p.key -> { current = p; until = now + stayMs }
            else -> { waiting.removeAll { it.key == p.key }; waiting.addLast(p) }
        }
    }

    /** Moves on once [current]'s time is up. True if what shows changed. */
    fun tick(now: Long): Boolean {
        if (current == null || now < until) return false
        current = waiting.removeFirstOrNull()
        until = now + stayMs
        return true
    }

    fun clear() { current = null; waiting.clear() }
}

@Composable
fun InGamePopupCard(p: InGamePopup) {
    val icon = when (p.kind) {
        InGamePopup.Kind.Saved, InGamePopup.Kind.Slot -> R.drawable.ic_state_save
        InGamePopup.Kind.Loaded -> R.drawable.ic_state_load
        InGamePopup.Kind.Screenshot -> R.drawable.ic_camera
        InGamePopup.Kind.Reset -> R.drawable.ic_reset
        InGamePopup.Kind.Problem -> R.drawable.ic_alert
    }
    JoeyToast(icon, p.label, p.name, p.line, iconTint = if (p.kind == InGamePopup.Kind.Problem) JoeyTheme.colors.error else null)
}
