package com.joeysretro.joey64.achievements

/**
 * Settings › RetroAchievements › Offline unlocks (approved mockup 2026-11-08, scratchpad build_proxy2.py /
 * proxy2_mock.png): who keeps the unlocks earned offline. One at a time, never both.
 */
enum class OfflineBy(val label: String) {
    /** Joey64's own queue (RaUnlockQueue), the default; RAOfflineProxy's address is noted but not used. */
    Joey64("Joey64"),
    /** RAOfflineProxy: every request goes to it; with it not running, games start without achievements. */
    Proxy("RAOfflineProxy");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Joey64
    }
}

/**
 * How a game (or the launcher between games) talks to RetroAchievements, from the choice and
 * RAOfflineProxy's address (null: it isn't running). Fixed when a game starts: a change applies from
 * the next game.
 */
data class OfflineRoute(
    /** rc_client's server: the proxy's address, or null for RetroAchievements itself. */
    val host: String?,
    /** RAOfflineProxy picked but not running: no achievements this game ("Achievements off" pop-up). */
    val blocked: Boolean,
    /** Joey64's own queue keeps unlocks that get no answer. */
    val queue: Boolean,
) {
    companion object {
        fun of(by: OfflineBy, proxyHost: String?): OfflineRoute = when (by) {
            OfflineBy.Joey64 -> OfflineRoute(host = null, blocked = false, queue = true)
            OfflineBy.Proxy -> OfflineRoute(host = proxyHost, blocked = proxyHost == null, queue = false)
        }

        /** The row's value: "Joey64", "Joey64 · 2 waiting", "RAOfflineProxy", "RAOfflineProxy · not running". */
        fun rowValue(by: OfflineBy, proxyRunning: Boolean, waiting: Int): String = when (by) {
            OfflineBy.Joey64 -> if (waiting > 0) "Joey64 · $waiting waiting" else "Joey64"
            OfflineBy.Proxy -> if (proxyRunning) "RAOfflineProxy" else "RAOfflineProxy · not running"
        }

        /** The description under the tab for the Offline unlocks row. */
        fun describe(by: OfflineBy, proxyRunning: Boolean, waiting: Int): String = when {
            by == OfflineBy.Proxy && proxyRunning ->
                "RAOfflineProxy is running. It keeps the unlocks you earn offline and sends them once you're back online."
            by == OfflineBy.Proxy -> "RAOfflineProxy isn't running, so games start without achievements. Start RAOfflineProxy, or pick Joey64."
            waiting > 0 -> "$waiting " + (if (waiting == 1) "unlock" else "unlocks") +
                " earned offline " + (if (waiting == 1) "hasn't" else "haven't") + " reached RetroAchievements yet. Joey64 sends them once you're back online."
            else -> "Who keeps the unlocks you earn offline until they can be sent: Joey64, or the RAOfflineProxy app."
        }

        const val OFF_LINE = "RAOfflineProxy isn't running. Start it, or pick Joey64 in Settings › RetroAchievements."
    }
}
