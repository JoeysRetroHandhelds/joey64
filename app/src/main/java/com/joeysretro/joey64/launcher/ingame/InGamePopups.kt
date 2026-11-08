package com.joeysretro.joey64.launcher.ingame

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.components.JoeyToast
import com.joeysretro.joey64.launcher.components.rememberBadge
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import java.io.File

/**
 * A pop-up over the running game (plan, Phase 3: the pop-up system; Phase 7: achievements): the
 * approved toast's shape at Settings › Pop-up place, with nothing to press. [key] names what it's
 * about: a newer pop-up with the same key takes the place of the one showing or waiting (pressing
 * the slot hotkey five times shows the last slot, not five pop-ups in a row).
 *
 * [label] may mark a part in the accent colour with asterisks ("Achievement unlocked · *10 points*").
 * Achievement pop-ups carry their [badge] picture (approved toast.html / toast2.html): an unlock has
 * a 40 dp badge and three lines, plus [extra] when it was earned offline; [compact] ones (progress,
 * challenge failed) a 30 dp badge and two lines; [framed] ones (Beaten, Mastered) a yellow border.
 * [stayMs] overrides how long it stays (the short ones), else Settings › Pop-ups stay for;
 * [PopupQueue.UNTIL_HIDDEN] stays until it's hidden (Paused), letting newer ones show over it meanwhile.
 * [bar] (0 to 1) draws a bar under the lines (the volume).
 */
data class InGamePopup(
    val key: String,
    val kind: Kind,
    val label: String,
    val name: String,
    val line: String,
    val badge: File? = null,
    val compact: Boolean = false,
    val framed: Boolean = false,
    val extra: String? = null,
    val stayMs: Long? = null,
    val bar: Float? = null,
) {
    enum class Kind { Saved, Loaded, Slot, Screenshot, Reset, Problem, Account, Achievement, Progress, ChallengeFailed, Game, Stats,
        /** The hotkeys' (approved hotkeys mockup 2026-11-07). */
        Volume, Mute, Resolution, Paused, NoAchievements,
        /** Approved Slow motion and Rewind mockup (2026-11-08). */
        SlowMotion, Rewind }
}

/**
 * The pop-up queue: one at a time, each for [stayMs] (Settings › Pop-ups stay for, 5 s by default
 * as approved) or its own, then the next. Times are passed in, so it's tested without a clock.
 */
class PopupQueue(var stayMs: Long = 5_000) {
    var current: InGamePopup? = null
        private set
    /** When [current] goes. */
    var until = 0L
        private set
    private val waiting = ArrayDeque<InGamePopup>()

    private fun stay(p: InGamePopup) = p.stayMs ?: stayMs

    fun add(p: InGamePopup, now: Long) {
        val showing = current
        when {
            showing == null || showing.key == p.key -> { current = p; until = now + stay(p) }
            // One that stays until hidden lets the new one show now, and comes back after it.
            showing.stayMs == UNTIL_HIDDEN -> { waiting.removeAll { it.key == p.key }; waiting.addFirst(showing); current = p; until = now + stay(p) }
            else -> { waiting.removeAll { it.key == p.key }; waiting.addLast(p) }
        }
    }

    /** Moves on once [current]'s time is up. True if what shows changed. */
    fun tick(now: Long): Boolean {
        if (current == null || now < until) return false
        current = waiting.removeFirstOrNull()
        current?.let { until = now + stay(it) }
        return true
    }

    /** Takes a pop-up away before its time (a progress pop-up rcheevos says to hide). */
    fun remove(key: String, now: Long) {
        waiting.removeAll { it.key == key }
        if (current?.key == key) { current = waiting.removeFirstOrNull(); current?.let { until = now + stay(it) } }
    }

    fun clear() { current = null; waiting.clear() }

    companion object {
        /** A pop-up's [InGamePopup.stayMs] for one that stays until it's hidden. */
        const val UNTIL_HIDDEN = Long.MAX_VALUE / 4
    }
}

@Composable
fun InGamePopupCard(p: InGamePopup) {
    val icon = when (p.kind) {
        InGamePopup.Kind.Saved, InGamePopup.Kind.Slot -> R.drawable.ic_state_save
        InGamePopup.Kind.Loaded -> R.drawable.ic_state_load
        InGamePopup.Kind.Screenshot -> R.drawable.ic_camera
        InGamePopup.Kind.Reset -> R.drawable.ic_reset
        InGamePopup.Kind.Problem -> R.drawable.ic_alert
        InGamePopup.Kind.Account -> R.drawable.ic_user_alert
        InGamePopup.Kind.Achievement, InGamePopup.Kind.Progress, InGamePopup.Kind.Game -> R.drawable.ic_trophy
        InGamePopup.Kind.ChallengeFailed -> R.drawable.ic_target_off
        InGamePopup.Kind.Stats -> R.drawable.ic_sliders
        InGamePopup.Kind.Volume -> R.drawable.ic_volume
        InGamePopup.Kind.Mute -> if (p.name == "Sound off") R.drawable.ic_volume_off else R.drawable.ic_volume
        InGamePopup.Kind.Resolution -> R.drawable.ic_zoom_in
        InGamePopup.Kind.Paused -> R.drawable.ic_pause
        InGamePopup.Kind.NoAchievements -> R.drawable.ic_trophy
        InGamePopup.Kind.SlowMotion -> R.drawable.ic_clock
        InGamePopup.Kind.Rewind -> R.drawable.ic_history
    }
    JoeyToast(icon, p.label, p.name, p.line, iconTint = when (p.kind) {
        InGamePopup.Kind.Problem, InGamePopup.Kind.ChallengeFailed -> JoeyTheme.colors.error
        InGamePopup.Kind.Account, InGamePopup.Kind.Achievement, InGamePopup.Kind.Progress, InGamePopup.Kind.Game,
        InGamePopup.Kind.NoAchievements -> JoeyTheme.colors.gold
        else -> null
    }, badge = p.badge, compact = p.compact, framed = p.framed, extra = p.extra, bar = p.bar)
}

/**
 * The challenge badges (approved toast2.html): the achievements whose challenge is running right
 * now, 30 dp each in one box at Settings › Badge place, newest on the right. A badge that hasn't
 * come yet shows the trophy.
 */
@Composable
fun ChallengeBadges(files: List<File?>) {
    val c = JoeyTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.padding(12.dp).background(c.surface, shape).border(1.dp, c.border, shape).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (f in files) {
            val picture = rememberBadge(f)
            val badgeShape = RoundedCornerShape(5.dp)
            if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(30.dp).clip(badgeShape))
            else Box(Modifier.size(30.dp).background(c.chip, badgeShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_trophy), contentDescription = null, tint = c.gold, modifier = Modifier.size(17.dp))
            }
        }
    }
}
