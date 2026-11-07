package com.joeysretro.joey64.achievements

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where an over-the-game pop-up sits (approved Pop-up place picker, 2026-11-05). */
enum class PopupSpot(val label: String) {
    TopLeft("Top left"), TopMiddle("Top middle"), TopRight("Top right"),
    BottomLeft("Bottom left"), BottomMiddle("Bottom middle"), BottomRight("Bottom right");

    companion object {
        fun of(name: String?, default: PopupSpot) = entries.firstOrNull { it.name == name } ?: default
    }
}

/**
 * Settings › RetroAchievements (approved tab, 2026-11-05: scratchpad build_ratab.py and
 * build_rapop.py). Defaults as approved. Hardcore and Leaderboards aren't here: locked until
 * RetroAchievements approves Joey64.
 */
data class RaOptions(
    val achievements: Boolean = true,
    val encore: Boolean = false,
    val spectator: Boolean = false,
    val unofficial: Boolean = false,
    val popUnlocks: Boolean = true,
    val popProgress: Boolean = true,
    val popChallenges: Boolean = true,
    val popSummary: Boolean = true,
    val popPlace: PopupSpot = PopupSpot.TopLeft,
    val badgePlace: PopupSpot = PopupSpot.BottomRight,
    val popSeconds: Int = 5,
    val screenshotOnUnlock: Boolean = false,
    val saveBadges: Boolean = true,
    val richPresence: Boolean = true,
) {
    companion object {
        val STAY_SECONDS = listOf(3, 5, 8, 10, 15)
    }
}

/**
 * Kept in app-private settings ("ra_settings"), read without waiting: the game reads them when it
 * starts, the same process as the launcher, and both see one [options] flow.
 */
object RaSettings {
    private const val PREFS = "ra_settings"
    private val state = MutableStateFlow<RaOptions?>(null)

    fun options(context: Context): StateFlow<RaOptions?> {
        if (state.value == null) state.value = read(context)
        return state
    }

    fun current(context: Context): RaOptions = options(context).value ?: RaOptions()

    /** Reads them again (a restored backup wrote the prefs). */
    fun reload(context: Context) {
        val next = read(context)
        state.value = next
        RaBridge.optionsChanged(next)
    }

    fun update(context: Context, change: (RaOptions) -> RaOptions) {
        val next = change(current(context))
        write(context, next)
        state.value = next
        RaBridge.optionsChanged(next)
    }

    private fun read(context: Context): RaOptions {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = RaOptions()
        return RaOptions(
            achievements = p.getBoolean("achievements", d.achievements),
            encore = p.getBoolean("encore", d.encore),
            spectator = p.getBoolean("spectator", d.spectator),
            unofficial = p.getBoolean("unofficial", d.unofficial),
            popUnlocks = p.getBoolean("popUnlocks", d.popUnlocks),
            popProgress = p.getBoolean("popProgress", d.popProgress),
            popChallenges = p.getBoolean("popChallenges", d.popChallenges),
            popSummary = p.getBoolean("popSummary", d.popSummary),
            popPlace = PopupSpot.of(p.getString("popPlace", null), d.popPlace),
            badgePlace = PopupSpot.of(p.getString("badgePlace", null), d.badgePlace),
            popSeconds = p.getInt("popSeconds", d.popSeconds).takeIf { it in RaOptions.STAY_SECONDS } ?: d.popSeconds,
            screenshotOnUnlock = p.getBoolean("screenshotOnUnlock", d.screenshotOnUnlock),
            saveBadges = p.getBoolean("saveBadges", d.saveBadges),
            richPresence = p.getBoolean("richPresence", d.richPresence),
        )
    }

    private fun write(context: Context, o: RaOptions) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("achievements", o.achievements)
            .putBoolean("encore", o.encore)
            .putBoolean("spectator", o.spectator)
            .putBoolean("unofficial", o.unofficial)
            .putBoolean("popUnlocks", o.popUnlocks)
            .putBoolean("popProgress", o.popProgress)
            .putBoolean("popChallenges", o.popChallenges)
            .putBoolean("popSummary", o.popSummary)
            .putString("popPlace", o.popPlace.name)
            .putString("badgePlace", o.badgePlace.name)
            .putInt("popSeconds", o.popSeconds)
            .putBoolean("screenshotOnUnlock", o.screenshotOnUnlock)
            .putBoolean("saveBadges", o.saveBadges)
            .putBoolean("richPresence", o.richPresence)
            .apply()
    }
}
