package com.joeysretro.joey64.achievements

import com.joeysretro.joey64.launcher.ingame.SlotDates
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One achievement as rc_client lists it ([RaBridge.achievementList]), live or from the progress cache. */
data class RaAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val badge: String,
    val badgeLocked: String,
    /** RetroAchievements' measured progress, "3/16"; empty when it isn't tracked. */
    val progress: String,
    val percent: Float,
    /** Share of players who have it, 0-100. */
    val rarity: Float,
    /** Seconds since 1970; 0 when locked. */
    val unlockTime: Long,
    /** rc_client's bits: 1 softcore, 2 hardcore. */
    val unlocked: Int,
    /** rc_client's type: 1 is Missable. */
    val type: Int,
    /** rc_client's section (bucket) when the list was made. */
    val section: Int,
) {
    val isUnlocked get() = unlocked != 0
    val missable get() = type == TYPE_MISSABLE
    /** The approved "Almost there": a tracked one at 80% or more (rcheevos' own line). */
    val almostThere get() = !isUnlocked && progress.isNotEmpty() && percent >= 80f

    companion object {
        const val TYPE_MISSABLE = 1
        // rc_client's buckets (RC_CLIENT_ACHIEVEMENT_BUCKET_*).
        const val LOCKED = 1; const val UNLOCKED = 2; const val UNSUPPORTED = 3; const val UNOFFICIAL = 4
        const val RECENTLY_UNLOCKED = 5; const val ACTIVE_CHALLENGE = 6; const val ALMOST_THERE = 7; const val UNSYNCED = 8

        fun parse(o: JSONObject) = RaAchievement(
            o.optInt("id"), o.optString("title"), o.optString("description"), o.optInt("points"),
            o.optString("badge"), o.optString("badgeLocked"), o.optString("progress"), o.optDouble("percent", 0.0).toFloat(),
            o.optDouble("rarity", 0.0).toFloat(), o.optDouble("unlockTime", 0.0).toLong(), o.optInt("unlocked"),
            o.optInt("type"), o.optInt("sectionType"),
        )

        fun parseList(json: String?): List<RaAchievement> {
            if (json.isNullOrBlank()) return emptyList()
            val a = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
            return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parse) }
        }
    }
}

/** A game with achievements, for the Achievements page: its set and the user's progress in it. */
data class RaGame(
    /** The library's game id. */
    val gameId: Long,
    val raId: Int,
    val title: String,
    val badge: String,
    val achievements: Int,
    val unlocked: Int,
    val points: Int,
    val pointsUnlocked: Int,
    /** When the set was beaten / completed (seconds), 0 when it isn't. */
    val beaten: Long,
    val completed: Long,
    /** The library's last-played time (ms), 0 when never. */
    val lastPlayed: Long,
) {
    val mastered get() = achievements > 0 && unlocked >= achievements
    val fraction get() = if (achievements == 0) 0f else unlocked.toFloat() / achievements

    companion object {
        fun parse(gameId: Long, o: JSONObject, lastPlayed: Long) = RaGame(
            gameId, o.optInt("id"), o.optString("title"), o.optString("badge"), o.optInt("achievements"),
            o.optInt("unlocked"), o.optInt("points"), o.optInt("pointsUnlocked"),
            o.optDouble("beaten", 0.0).toLong(), o.optDouble("completed", 0.0).toLong(), lastPlayed,
        )
    }
}

/** The games page's Show popup (approved ach2.html): one filter and one sort. */
enum class GameFilter(val label: String) { All("All games"), InProgress("In progress"), Beaten("Beaten"), Mastered("Mastered"), NotStarted("Not started") }
enum class GameSort(val label: String) { RecentlyPlayed("Recently played"), Name("Name"), MostComplete("Most complete") }
/** One game's Show popup (approved ach2.html, Rarest first from ach3.html). */
enum class AchFilter(val label: String) { All("All"), Unlocked("Unlocked"), Locked("Locked"), Missable("Missable"), AlmostThere("Almost there") }
enum class AchSort(val label: String) { RaOrder("RetroAchievements order"), MostPoints("Most points"), RecentlyUnlocked("Recently unlocked"), Rarest("Rarest first") }

object RaLists {
    fun matches(g: RaGame, f: GameFilter) = when (f) {
        GameFilter.All -> true
        GameFilter.InProgress -> g.unlocked > 0 && !g.mastered
        GameFilter.Beaten -> g.beaten > 0
        GameFilter.Mastered -> g.mastered
        GameFilter.NotStarted -> g.unlocked == 0
    }

    fun games(list: List<RaGame>, f: GameFilter, s: GameSort): List<RaGame> {
        val kept = list.filter { matches(it, f) }
        return when (s) {
            GameSort.RecentlyPlayed -> kept.sortedWith(compareByDescending<RaGame> { it.lastPlayed }.thenBy { it.title.lowercase() })
            GameSort.Name -> kept.sortedBy { it.title.lowercase() }
            GameSort.MostComplete -> kept.sortedWith(compareByDescending<RaGame> { it.fraction }.thenBy { it.title.lowercase() })
        }
    }

    fun counts(list: List<RaGame>) = GameFilter.entries.associateWith { f -> list.count { matches(it, f) } }

    fun matches(a: RaAchievement, f: AchFilter) = when (f) {
        AchFilter.All -> true
        AchFilter.Unlocked -> a.isUnlocked
        AchFilter.Locked -> !a.isUnlocked
        AchFilter.Missable -> a.missable
        AchFilter.AlmostThere -> a.almostThere
    }

    fun achCounts(list: List<RaAchievement>) = AchFilter.entries.associateWith { f -> list.count { matches(it, f) } }

    /**
     * RetroAchievements order (Joey 2026-11-04, RetroArch's rcheevos grouping): Active challenges,
     * Recently unlocked, Almost there, Unsynced, Locked, Unofficial, Unsupported, Unlocked. In a
     * running game rc_client has already put them so. Outside a game ([live] false) the cached
     * sections are from the last session: challenges and recent unlocks don't apply any more, so
     * those go back to Locked and Unlocked, and Almost there comes from the cached progress.
     */
    fun order(list: List<RaAchievement>, live: Boolean): List<RaAchievement> {
        if (live) return list
        fun rank(a: RaAchievement) = when {
            a.isUnlocked -> 5
            a.almostThere -> 0
            a.section == RaAchievement.UNSYNCED -> 1
            a.section == RaAchievement.UNOFFICIAL -> 3
            a.section == RaAchievement.UNSUPPORTED -> 4
            else -> 2
        }
        return list.withIndex().sortedWith(compareBy<IndexedValue<RaAchievement>> { rank(it.value) }.thenBy {
            if (it.value.isUnlocked) -it.value.unlockTime else it.index.toLong()
        }).map { it.value }
    }

    fun achievements(list: List<RaAchievement>, f: AchFilter, s: AchSort, live: Boolean): List<RaAchievement> {
        val ordered = order(list, live)
        val kept = ordered.filter { matches(it, f) }
        return when (s) {
            AchSort.RaOrder -> kept
            AchSort.MostPoints -> kept.sortedByDescending { it.points }
            AchSort.RecentlyUnlocked -> kept.sortedWith(compareBy<RaAchievement> { !it.isUnlocked }.thenByDescending { it.unlockTime })
            AchSort.Rarest -> kept.sortedBy { if (it.rarity > 0f) it.rarity else Float.MAX_VALUE }
        }
    }

    private fun n(v: Int) = String.format(Locale.US, "%,d", v)

    /** A game row's grey line: "18 of 40 unlocked · 164 points", "All 36 unlocked · 400 points". */
    fun gameLine(g: RaGame) = if (g.mastered) "All ${g.achievements} unlocked · ${n(g.points)} points"
        else "${g.unlocked} of ${g.achievements} unlocked · ${n(g.pointsUnlocked)} points"

    /** One game's header line: "18 of 40 unlocked · 164 of 400 points", or with a filter "Locked · 22 of 40". */
    fun gameSummary(g: RaGame, f: AchFilter, shown: Int) =
        if (f == AchFilter.All) "${g.unlocked} of ${g.achievements} unlocked · ${n(g.pointsUnlocked)} of ${n(g.points)} points"
        else "${f.label} · $shown of ${g.achievements}"

    /** The games page's header line: "JoeyRetro · 2,184 points", or with a filter "In progress · 5 of 7". */
    fun pageSummary(user: String, points: Int?, f: GameFilter, shown: Int, total: Int) =
        if (f == GameFilter.All) listOfNotNull(user, points?.let { "${n(it)} points" }).joinToString(" · ")
        else "${f.label} · $shown of $total"

    /** "8.4% of players"; null when RetroAchievements gave none. */
    fun rarity(a: RaAchievement): String? =
        if (a.rarity <= 0f) null else String.format(Locale.US, "%.1f%% of players", a.rarity)

    /** "3 / 16" from rcheevos' "3/16". */
    fun progress(a: RaAchievement): String = a.progress.replace("/", " / ")

    /** When an unlock was: "Just now" in the last minute, else the slot picker's words ("Today 9:12 pm", "Oct 12"). */
    fun unlockDate(a: RaAchievement, nowMs: Long): String {
        val at = a.unlockTime * 1000
        return if (nowMs - at in 0 until 60_000) "Just now" else SlotDates.label(at, nowMs)
    }

    /** The unlocked row's mode word (approved ach3): Hardcore (in yellow) or Softcore. */
    fun mode(a: RaAchievement) = if (a.unlocked and 2 != 0) "Hardcore" else "Softcore"
}
