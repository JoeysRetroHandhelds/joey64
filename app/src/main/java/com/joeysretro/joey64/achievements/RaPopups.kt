package com.joeysretro.joey64.achievements

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.ingame.InGamePopup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The words on each achievement pop-up (approved 2026-11-04: toast.html, toast2.html, ach3.html
 * "Challenge failed"). Plain functions of rcheevos' event JSON, so they're tested without a device.
 * Softcore until RetroAchievements approves Joey64 (then "Hardcore" where it applies).
 */
object RaPopupText {
    /** How long the small ones stay at most (rcheevos hides a progress one itself, about 2 s after its last change). */
    const val SHORT_MS = 3_000L

    private fun points(n: Int) = if (n == 1) "1 point" else "$n points"

    /** "Achievement unlocked · *10 points* · 19 of 40", the name, its description; the offline line when the server didn't answer. */
    fun unlock(a: JSONObject, game: JSONObject?, offline: Boolean, badge: File?): InGamePopup {
        val of = game?.takeIf { it.optInt("achievements") > 0 }?.let { " · ${it.optInt("unlocked")} of ${it.optInt("achievements")}" } ?: ""
        return InGamePopup("unlock-${a.optInt("id")}", InGamePopup.Kind.Achievement,
            "Achievement unlocked · *${points(a.optInt("points"))}*$of", a.optString("title"), a.optString("description"),
            badge = badge, extra = if (offline) "Offline · sends when you're back online" else null)
    }

    /** "Time Trialist / 4 / 16": one at a time, each new count in place of the last. */
    fun progress(a: JSONObject, badge: File?) = InGamePopup("progress", InGamePopup.Kind.Progress,
        a.optString("title"), a.optString("progress"), "", badge = badge, compact = true, stayMs = SHORT_MS + 1_000)

    fun challengeFailed(a: JSONObject, badge: File?) = InGamePopup("failed-${a.optInt("id")}", InGamePopup.Kind.ChallengeFailed,
        "*Challenge failed*", a.optString("title"), "", badge = badge, compact = true, stayMs = SHORT_MS)

    /**
     * When a game starts (Settings › Game start summary; the card shape of the approved toasts,
     * the words drafted here): the game's icon, "RetroAchievements · Softcore", its name, and how
     * much of the set is unlocked.
     */
    fun summary(game: JSONObject, badge: File?): InGamePopup {
        val n = game.optInt("achievements")
        val got = game.optInt("unlocked")
        val line = when {
            n == 0 -> "No achievements for this game yet"
            got >= n -> "All $n achievements · ${game.optInt("points")} points"
            else -> "$got of $n achievements · ${game.optInt("pointsUnlocked")} of ${game.optInt("points")} points"
        }
        return InGamePopup("summary", InGamePopup.Kind.Game, "*RetroAchievements* · Softcore", game.optString("title"), line, badge = badge)
    }

    /** "*Beaten* · Softcore / Mario Tennis / Finished the game · 26 of 40 achievements" (after the unlock that beats it). */
    fun beaten(game: JSONObject, badge: File?) = InGamePopup("beaten", InGamePopup.Kind.Game, "*Beaten* · Softcore",
        game.optString("title"), "Finished the game · ${game.optInt("unlocked")} of ${game.optInt("achievements")} achievements",
        badge = badge, framed = true)

    fun mastered(game: JSONObject, badge: File?) = InGamePopup("mastered", InGamePopup.Kind.Game, "*Mastered* · Softcore",
        game.optString("title"), "All ${game.optInt("achievements")} achievements · ${game.optInt("points")} points",
        badge = badge, framed = true)

    /** Every badge picture of the loaded game's list ([RaBridge.achievementList]), unlocked and locked, for Save badges for offline. */
    fun badgeUrls(list: JSONArray): List<String> {
        val out = LinkedHashSet<String>()
        for (i in 0 until list.length()) {
            val a = list.optJSONObject(i) ?: continue
            a.optString("badge").takeIf { it.isNotEmpty() }?.let(out::add)
            a.optString("badgeLocked").takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out.toList()
    }
}

/**
 * The game screen's achievement pop-ups: rcheevos' events (on the emulation thread or an HTTP
 * thread) are handled one at a time on a thread of their own, which waits a moment for a badge
 * picture that isn't kept yet, then hands the pop-up to [show]. Challenge badges go to [badges]
 * (the bottom-right stack). Every callback is called on that thread: the caller posts to the main
 * thread. Settings › RetroAchievements is read at each event, so a change applies at once.
 */
class RaPopups(
    context: Context,
    private val show: (InGamePopup) -> Unit,
    private val hide: (key: String) -> Unit,
    private val badges: (List<File?>) -> Unit,
    private val screenshot: () -> Unit,
) : RaBridge.Listener {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ra-popups").apply { isDaemon = true } }
    private val challenges = LinkedHashMap<Int, File?>()
    private val unlocked = HashSet<Int>()
    private var offline = false
    /** Whether the game was beaten when it was loaded (or by an unlock since): Beaten shows once, when that changes. */
    private var beaten = true

    override fun onEvent(event: JSONObject) {
        worker.execute { try { handle(event) } catch (e: Exception) { JLog.e(TAG, "pop-up for ${event.optString("event")} failed: $e") } }
    }

    fun stop() { RaBridge.listeners.remove(this); worker.shutdownNow() }

    private fun badge(url: String?): File? = url?.takeIf { it.isNotEmpty() }?.let { RaBadges.await(app, it, BADGE_WAIT_MS) }

    private fun handle(ev: JSONObject) {
        val o = RaSettings.current(app)
        val a = ev.optJSONObject("achievement")
        val game = ev.optJSONObject("game")
        when (ev.optString("event")) {
            "gameLoaded" -> {
                if (game == null) return
                beaten = game.optDouble("beaten", 0.0) > 0
                if (o.saveBadges) worker.execute {
                    val list = try { JSONArray(RaBridge.achievementList()) } catch (e: Exception) { JSONArray() }
                    val urls = RaPopupText.badgeUrls(list) + listOfNotNull(game.optString("badge").takeIf { it.isNotEmpty() })
                    for (u in urls) RaBadges.fetch(app, u)
                    JLog.i(TAG, "badges for offline: ${urls.size} asked for")
                }
                if (o.popSummary) show(RaPopupText.summary(game, badge(game.optString("badge"))))
            }
            "unlocked" -> {
                if (a == null) return
                unlocked += a.optInt("id")
                if (o.screenshotOnUnlock) screenshot()
                if (o.popUnlocks) show(RaPopupText.unlock(a, game, offline, badge(a.optString("badge"))))
                // The unlock that beats the game (a progression or win one): the Beaten card after it.
                val nowBeaten = game != null && game.optDouble("beaten", 0.0) > 0
                if (nowBeaten && !beaten && o.popUnlocks) show(RaPopupText.beaten(game!!, badge(game.optString("badge"))))
                if (nowBeaten) beaten = true
            }
            "gameCompleted" -> if (game != null && o.popUnlocks) show(RaPopupText.mastered(game, badge(game.optString("badge"))))
            "progressShow", "progressUpdate" -> if (a != null && o.popProgress) show(RaPopupText.progress(a, badge(a.optString("badge"))))
            "progressHide" -> hide("progress")
            "challengeShow" -> if (a != null) {
                challenges[a.optInt("id")] = badge(a.optString("badge"))
                if (o.popChallenges) badges(challenges.values.toList())
            }
            "challengeHide" -> if (a != null) {
                val id = a.optInt("id")
                challenges.remove(id)
                badges(if (o.popChallenges) challenges.values.toList() else emptyList())
                // rcheevos hides the badge both when the challenge is won and when it's lost; an unlock
                // comes in the same frame when it's won, so it's lost if none came by now.
                worker.schedule({
                    if (id !in unlocked && RaSettings.current(app).popChallenges) show(RaPopupText.challengeFailed(a, RaBadges.kept(app, a.optString("badge"))))
                }, FAILED_WAIT_MS, TimeUnit.MILLISECONDS)
            }
            "disconnected" -> offline = true
            "reconnected" -> offline = false
            "reset" -> { challenges.clear(); badges(emptyList()) }
        }
    }

    private companion object {
        const val TAG = "joey64ra"
        const val BADGE_WAIT_MS = 1_500L
        const val FAILED_WAIT_MS = 400L
    }
}
