package com.joeysretro.joey64.launcher.library

import android.content.Context
import com.joeysretro.joey64.launcher.data.GameCollection
import com.joeysretro.joey64.launcher.data.LibraryGame

/** The filters that fill themselves (approved Show popup, 2026-11-04). */
enum class AutoFilter(val label: String) {
    /** Games that take four players or more, from libretro-database's player counts. */
    FourPlayers("4 players"),
    /** Games not in No-Intro's list (the box-art table): hacks, translations, homebrew. */
    Hacks("Hacks and translations"),
    NeverPlayed("Never played"),
    /** Games with a RetroAchievements set ([com.joeysretro.joey64.achievements.RaGameList]); listed while signed in. */
    HasAchievements("Has achievements"),
}

/** What the library shows: one at a time, named on the Filter button (blue when it isn't All games). */
sealed interface LibraryView {
    val label: String
    data object All : LibraryView { override val label = "All games" }
    data object Favourites : LibraryView { override val label = "Favourites" }
    data class Collection(val id: Long, val name: String) : LibraryView { override val label get() = name }
    data class Filter(val filter: AutoFilter) : LibraryView { override val label get() = filter.label }
}

/** What the filters need beyond the games themselves. */
class LibraryFacts(
    /** CRC32 to the most players a game takes; "@" + a game code's first 3 characters for any version of it. */
    val players: Map<String, Int>,
    /** MD5s No-Intro lists. */
    val listed: Set<String>,
    /** Collection id to its games. */
    val members: Map<Long, Set<Long>>,
    /** RetroAchievements hashes (lower case) of games with achievements; empty until the list is fetched. */
    val withSets: Set<String> = emptySet(),
)

object LibraryViews {
    /** [view] as text for the launcher's saved state ([fromKey] reads it back): the view stays after a game. */
    fun key(view: LibraryView): String = when (view) {
        LibraryView.All -> "all"
        LibraryView.Favourites -> "favourites"
        is LibraryView.Collection -> "collection:${view.id}:${view.name}"
        is LibraryView.Filter -> "filter:${view.filter.name}"
    }

    /** The view [key] names; All games for none, or for one that doesn't read (a filter an update took out). */
    fun fromKey(key: String?): LibraryView {
        val k = key ?: return LibraryView.All
        return when {
            k == "favourites" -> LibraryView.Favourites
            k.startsWith("collection:") -> k.removePrefix("collection:").split(':', limit = 2).let { p ->
                p[0].toLongOrNull()?.let { LibraryView.Collection(it, p.getOrElse(1) { "" }) }
            } ?: LibraryView.All
            k.startsWith("filter:") -> AutoFilter.entries.firstOrNull { it.name == k.removePrefix("filter:") }
                ?.let { LibraryView.Filter(it) } ?: LibraryView.All
            else -> LibraryView.All
        }
    }

    fun matches(game: LibraryGame, view: LibraryView, facts: LibraryFacts): Boolean = when (view) {
        LibraryView.All -> true
        LibraryView.Favourites -> game.favourite
        is LibraryView.Collection -> game.id in facts.members[view.id].orEmpty()
        is LibraryView.Filter -> when (view.filter) {
            AutoFilter.FourPlayers -> (playersOf(game, facts) ?: 0) >= 4
            AutoFilter.Hacks -> game.md5 !in facts.listed
            AutoFilter.NeverPlayed -> !game.played
            AutoFilter.HasAchievements -> game.md5.lowercase() in facts.withSets
        }
    }

    /**
     * The most players [game] takes: by its CRC, else by its header's game code (a hack, translation
     * or randomizer: Smash Remix has Super Smash Bros.' NALE), else null.
     */
    fun playersOf(game: LibraryGame, facts: LibraryFacts): Int? =
        facts.players[game.crc32] ?: game.headerId?.takeIf { it.length == 4 }?.let { facts.players["@" + it.take(3)] }

    fun show(games: List<LibraryGame>, view: LibraryView, facts: LibraryFacts) = games.filter { matches(it, view, facts) }

    /** Every view the Show popup lists, Favourites and the collections first, with how many games each shows. */
    fun counts(games: List<LibraryGame>, collections: List<GameCollection>, facts: LibraryFacts): Map<LibraryView, Int> {
        val views = listOf(LibraryView.All, LibraryView.Favourites) +
            collections.map { LibraryView.Collection(it.id, it.name) } + AutoFilter.entries.map { LibraryView.Filter(it) }
        return views.associateWith { v -> games.count { matches(it, v, facts) } }
    }

    /** Search (approved 2026-11-04): every word typed appears in the title, any case, accents ignored ("pokemon" finds Pokémon Snap). */
    fun search(games: List<LibraryGame>, query: String): List<LibraryGame> {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return games.filter { g -> val t = folded.getOrPut(g.title) { fold(g.title) }; words.all { it in t } }
    }

    // Each key typed searches every title: with 1,231 games (2026-11-11) folding them all again, with a new Regex for
    // each, made about 30% of frames late while typing on the Odin 3. The pattern is made once, and a title's folded
    // form kept (a title changes only with a rename, which is a new key).
    private val MARKS = Regex("\\p{M}+")
    private val folded = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(MARKS, "").lowercase()

    /** assets/n64-players.tsv (devtools/make-players.py): CRC32 (or "@" + a game code's first 3 characters), a tab, the most players. */
    fun parsePlayers(text: String): Map<String, Int> {
        val m = HashMap<String, Int>(2048)
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line[0] == '#') continue
            val tab = line.indexOf('\t')
            if (tab == 8 || (tab == 4 && line[0] == '@')) line.substring(tab + 1).toIntOrNull()?.let { m[line.substring(0, tab)] = it }
        }
        return m
    }

    @Volatile private var players: Map<String, Int>? = null

    fun players(context: Context): Map<String, Int> =
        players ?: synchronized(this) {
            players ?: parsePlayers(context.assets.open("n64-players.tsv").bufferedReader().use { it.readText() }).also { players = it }
        }
}
