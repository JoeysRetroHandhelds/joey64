package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.data.LibraryGame

/*
 * Settings › Library's Show and Covers rows (approved mockup 2026-11-08, scratchpad build_libtab.py,
 * "Approve as drawn"): the library's order, which regions it shows, one version per game, cover size
 * and names. Pure, so LibraryArrangeTest checks them.
 */

/** Sort by: Name (A to Z), Recently played (the default, Joey 2026-11-11; never-played games after, A to Z), Most played (play time), Recently added. */
enum class SortBy(val label: String, val detail: String) {
    Name("Name", "A to Z"),
    RecentlyPlayed("Recently played", "The last game played first"),
    MostPlayed("Most played", "The most play time first"),
    RecentlyAdded("Recently added", "New in the ROM folders first"),
}

/** The Regions popup's ticks, from the ROM header's destination code (no file names needed). */
enum class RegionGroup(val label: String, val detail: String?) {
    USA("USA", "North America"),
    Europe("Europe", "Includes Australia"),
    Japan("Japan", null),
    Other("Other", "Brazil, China, Korea, and the rest"),
}

enum class CoverSize(val label: String, val delta: Int) { Small("Small", 1), Medium("Medium", 0), Large("Large", -1) }

/** Names: Off (the cover alone), Under the cover, or Only without box art (a game with no cover shows its name). */
enum class NamesMode(val label: String, val detail: String) {
    Off("Off", "The cover alone"),
    Under("Under the cover", "Every game's name under its cover"),
    NoArt("Only without box art", "A game with no cover shows its name");

    companion object {
        /** Under the cover (Joey 2026-11-08, after a mockup with names on every cover; the mockup had drawn Off). */
        val DEFAULT = Under
    }
}

/** Name style: Short "Conker's Bad Fur Day", Full "Conker's Bad Fur Day (Europe)" (the file's name). */
enum class NameStyle(val label: String) { Short("Short"), Full("Full") }

/**
 * Betas and demos, under One version per game (approved mockup 1A, Joey 2026-11-11: "1A + 2A + 3"; scratchpad
 * regional/build_versions.py): betas, protos, demos, kiosk and sample copies go in their game's Versions (With the
 * game, the default), get a tile each (On their own), or are left out of the library (Hidden: Search still has them).
 */
enum class BetaMode(val label: String, val detail: String) {
    WithGame("With the game", "In the game's Versions, not a tile of their own"),
    OwnTile("On their own", "Each one has its own tile"),
    Hidden("Hidden", "Only in Search"),
}

/** Look for new games: when Joey64 starts (a quick check of sizes and dates, as before), once a day, or only on Look now. */
enum class LookFor(val label: String, val detail: String) {
    Start("When Joey64 starts", "A quick check each time; new and changed files are read"),
    Daily("Once a day", "The first start each day"),
    Asked("Only when I ask", "Look now on this page"),
}

object LibraryArrange {

    /** The header's destination code to the Regions tick it falls under; "USA and Japan" (A) is both. */
    fun regionsOf(code: Char?): Set<RegionGroup> = when (code) {
        'E', 'N' -> setOf(RegionGroup.USA)
        'A' -> setOf(RegionGroup.USA, RegionGroup.Japan)
        'J' -> setOf(RegionGroup.Japan)
        'P', 'X', 'Y', 'Z', 'D', 'F', 'I', 'S', 'H', 'W', 'U' -> setOf(RegionGroup.Europe)
        else -> setOf(RegionGroup.Other)
    }

    /** The Regions row: "All regions" with every tick, else the ticked ones ("USA", "USA, Japan"). */
    fun regionsLabel(on: Set<RegionGroup>): String =
        if (on.containsAll(RegionGroup.entries)) "All regions" else RegionGroup.entries.filter { it in on }.joinToString(", ") { it.label }

    /** The library's order; Favourites first moves favourites to the top, each part in that order. */
    fun sort(games: List<LibraryGame>, by: SortBy, favouritesFirst: Boolean): List<LibraryGame> {
        val name = compareBy<LibraryGame, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        val order = when (by) {
            SortBy.Name -> name
            SortBy.RecentlyPlayed -> compareByDescending<LibraryGame> { it.lastPlayedAt ?: Long.MIN_VALUE }.then(name)
            SortBy.MostPlayed -> compareByDescending<LibraryGame> { it.playSeconds }.then(name)
            SortBy.RecentlyAdded -> compareByDescending<LibraryGame> { it.addedAt }.then(name)
        }
        val sorted = games.sortedWith(order)
        return if (favouritesFirst) sorted.filter { it.favourite } + sorted.filterNot { it.favourite } else sorted
    }

    /**
     * What the grid shows of [games]: only the ticked [regions]; with [oneVersion], each game once ([arrange]).
     */
    fun visible(games: List<LibraryGame>, regions: Set<RegionGroup>, oneVersion: Boolean, listed: Set<String>,
                betas: BetaMode = BetaMode.WithGame): List<LibraryGame> = arrange(games, regions, oneVersion, listed, betas).shown

    /**
     * The grid's tiles ([shown], in [games]' order) and, for each tile that stands for several copies, those copies
     * ([versions], every copy's id to its game's; the Versions popup, approved mockup 3, 2026-11-11).
     */
    class Arranged(val shown: List<LibraryGame>, val versions: Map<Long, Versions>)

    /** One game's copies in the library: [copies] in the Versions popup's order, [plays] the one its tile plays. */
    class Versions(val plays: LibraryGame, val copies: List<LibraryGame>)

    /**
     * Only the ticked [regions]; with [oneVersion], a game you have in several regions, revisions, ports or early
     * copies shows once (measured on the Odin 3's 1,231-game library 2026-11-11: README; the rule Joey's mockup used):
     *  - Games No-Intro lists ([listed] MD5s) are one game by the header's game code without its region letter, except
     *    that two names under one code with a copy for the same destination are two games (Ocarina of Time and its
     *    Master Quest, homebrew that took NED or another game's code); names for other destinations are the game's
     *    regional names (Lylat Wars, Star Fox 64). The same name under two codes is one game (OoT's CZL and NZL).
     *  - Aftermarket, Unl and Pirate copies, and with [betas] With the game the betas, protos, demos, kiosk and sample
     *    copies, go with the game of the same short name only: their header codes are often another game's (007's v2
     *    beta has Mortal Kombat 4's). Hidden leaves the early copies out; On their own gives each its own tile.
     *  - Everything else (hacks, unlisted homebrew, 64DD disks) has its own tile: hacks keep the original's code.
     * The tile plays the copy played or picked (Versions) last; never played, the first in [order].
     */
    fun arrange(games: List<LibraryGame>, regions: Set<RegionGroup>, oneVersion: Boolean, listed: Set<String>,
                betas: BetaMode = BetaMode.WithGame): Arranged {
        val inRegion = games.filter { g -> regionsOf(g.region).any { it in regions } }
        if (!oneVersion) return Arranged(inRegion, emptyMap())
        val pool = if (betas == BetaMode.Hidden) inRegion.filterNot { early(it) } else inRegion
        val key = HashMap<Long, String>()
        // Licensed, listed, released copies by game code, split into games by name and destination.
        val byCode = pool.filter { codeOf(it, listed) != null && !unofficial(it) && !early(it) }.groupBy { codeOf(it, listed)!! }
        for ((code, copies) in byCode) {
            // Each game here: its destinations and names. A name goes to the game whose names it is most like (the
            // longest shared start, then the first); if that game already has a copy for one of its destinations it
            // is a game of its own, even when another game here has room: Toki no Ocarina GC (Japan) is like Ocarina
            // of Time's Japanese name, and joining Master Quest, which had no Japanese copy yet, folded it wrong.
            val here = ArrayList<Pair<MutableSet<Char>, MutableList<String>>>()
            for ((name, named) in copies.groupBy { nameKey(it) }.entries.sortedWith(compareByDescending<Map.Entry<String, List<LibraryGame>>> { it.value.size }.thenBy { it.key })) {
                val letters = named.flatMapTo(HashSet()) { if (it.region == 'A') listOf('E', 'J') else listOf(it.region ?: '?') }
                val like = here.indices.maxByOrNull { j -> here[j].second.maxOf { name.commonPrefixWith(it).length } * here.size - j }
                var i = like?.takeIf { (here[it].first intersect letters).isEmpty() } ?: -1
                if (i < 0) { here.add(HashSet<Char>() to ArrayList()); i = here.size - 1 }
                here[i].first.addAll(letters); here[i].second.add(name)
                for (g in named) key[g.id] = "$code#$i"
            }
        }
        // The same name under two codes is one game.
        val joined = HashMap<String, String>()
        fun root(k: String): String { var r = k; while (true) { r = joined[r] ?: return r } }
        val firstOfName = HashMap<String, String>()
        for (g in pool.filter { it.id in key }.sortedBy { it.id }) {
            val k = root(key.getValue(g.id))
            val had = firstOfName[nameKey(g)]
            if (had == null) firstOfName[nameKey(g)] = k else if (root(had) != k) joined[k] = root(had)
        }
        for (id in key.keys.toList()) key[id] = root(key.getValue(id))
        // Unofficial and early copies by name: with the licensed game of that name, else with each other.
        for (g in pool) {
            if (g.id in key) continue
            // An early copy follows Betas and demos even when it's also aftermarket (a game jam demo).
            val byName = if (early(g)) betas == BetaMode.WithGame else unofficial(g)
            if (byName) key[g.id] = firstOfName[nameKey(g)]?.let { root(it) } ?: ("name:" + nameKey(g))
        }
        val groups = pool.filter { it.id in key }.groupBy { key.getValue(it.id) }.values
        val versions = HashMap<Long, Versions>()
        val drop = HashSet<Long>()
        val order = order(regions)
        for (copies in groups) {
            if (copies.size < 2) continue
            val sorted = copies.sortedWith(order)
            val last = copies.filter { latest(it) != null }.maxWithOrNull(compareBy<LibraryGame> { latest(it) }.thenComparator { a, b -> order.compare(b, a) })
            val v = Versions(last ?: sorted.first(), sorted)
            for (g in copies) { versions[g.id] = v; if (g.id != v.plays.id) drop += g.id }
        }
        return Arranged(pool.filter { it.id !in drop }, versions)
    }

    /**
     * The Versions popup's order, and which copy a tile plays before any is played: releases before early copies,
     * then the first ticked region, then the cartridge before a port or special copy (GameCube, Virtual Console, iQue,
     * debug, LodgeNet, aftermarket), then the newest version (the name's "v1.1" or "Rev 2", then the header's
     * revision: GameCube ports say 15), then the older entry.
     */
    fun order(regions: Set<RegionGroup>): Comparator<LibraryGame> =
        compareBy<LibraryGame> { early(it) }
            .thenBy { g -> RegionGroup.entries.indexOfFirst { it in regions && it in regionsOf(g.region) }.let { if (it < 0) 99 else it } }
            .thenBy { port(it) }
            .thenComparator { a, b -> compareVersions(b, a) }
            .thenBy { it.id }

    /** When the copy was last played or picked in Versions; null: neither. */
    private fun latest(g: LibraryGame): Long? = listOfNotNull(g.lastPlayedAt, g.pickedAt).maxOrNull()

    private fun codeOf(g: LibraryGame, listed: Set<String>): String? =
        g.headerId?.takeIf { it.length == 4 && g.md5 in listed }?.take(3)

    private fun nameKey(g: LibraryGame) = shortTitle(g.title).lowercase()

    private fun early(g: LibraryGame) = EARLY.containsMatchIn(g.title)
    private fun unofficial(g: LibraryGame) = UNOFFICIAL.containsMatchIn(g.title)
    private fun port(g: LibraryGame) = PORT.containsMatchIn(g.title) || g.region == 'G'

    /** The name's version ("(v1.1)", "(Rev 2)", none = 0), then the header's revision. */
    private fun compareVersions(a: LibraryGame, b: LibraryGame): Int {
        fun tag(g: LibraryGame) = VERSION.find(g.title)?.groupValues?.get(1)?.split('.')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val ta = tag(a); val tb = tag(b)
        for (i in 0 until maxOf(ta.size, tb.size)) {
            val d = (ta.getOrNull(i) ?: 0).compareTo(tb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return (a.revision ?: 0).compareTo(b.revision ?: 0)
    }

    private val EARLY = Regex("""\((Beta|Proto|Prototype|Demo|Sample|Kiosk)[^)]*\)""", RegexOption.IGNORE_CASE)
    private val UNOFFICIAL = Regex("""\((Aftermarket|Unl|Pirate)\)""", RegexOption.IGNORE_CASE)
    private val PORT = Regex("""GameCube|Virtual Console|iQue|Debug|LodgeNet|Aftermarket|Unl\)|Pirate""", RegexOption.IGNORE_CASE)
    private val VERSION = Regex("""\((?:v|Version |Rev )([0-9][0-9.]*)[^)]*\)""", RegexOption.IGNORE_CASE)

    /** Name style Short: the name before its first " (" or " [" tag ("Conker's Bad Fur Day (Europe)" -> "Conker's Bad Fur Day"). */
    fun shortTitle(title: String): String {
        val cut = listOf(title.indexOf(" ("), title.indexOf(" [")).filter { it > 0 }.minOrNull() ?: return title
        return title.substring(0, cut).trim().ifEmpty { title }
    }

    fun title(game: LibraryGame, style: NameStyle, tidy: Boolean = false): String {
        val t = if (style == NameStyle.Short) shortTitle(game.title) else game.title
        return if (tidy) tidyTitle(t) else t
    }

    // No-Intro's names put an article at the end of the name or of a part of it: "Legend of Zelda, The - Ocarina of Time".
    private val TRAILING_ARTICLE = Regex("""^(.+), (The|A|An|Die|Der|Das|Le|La|Les|El|Los|Las|Il|Lo)$""")

    /**
     * Tidy names (Joey 2026-11-12): the article goes back in front, in each " - " part of the name before its tags:
     * "Legend of Zelda, The - Ocarina of Time (USA)" -> "The Legend of Zelda - Ocarina of Time (USA)",
     * "New Tetris, The" -> "The New Tetris". Only the name shown changes; sorting and matching use the file's name.
     */
    fun tidyTitle(title: String): String {
        val cut = listOf(title.indexOf(" ("), title.indexOf(" [")).filter { it > 0 }.minOrNull() ?: title.length
        val name = title.substring(0, cut)
        val tidy = name.split(" - ").joinToString(" - ") { part ->
            TRAILING_ARTICLE.matchEntire(part)?.let { m -> m.groupValues[2] + " " + m.groupValues[1] } ?: part
        }
        return tidy + title.substring(cut)
    }

    /** Covers across: the screen's own count ([base], as before) with Small one more, Large one fewer, never under 2. */
    fun columns(base: Int, size: CoverSize) = (base + size.delta).coerceAtLeast(2)

    /** Look for new games: whether this start reads the folders. [lastDay] is the last day (epoch day) it read for Once a day. */
    fun lookNow(mode: LookFor, lastDay: Long?, today: Long): Boolean = when (mode) {
        LookFor.Start -> true
        LookFor.Daily -> lastDay != today
        LookFor.Asked -> false
    }
}
