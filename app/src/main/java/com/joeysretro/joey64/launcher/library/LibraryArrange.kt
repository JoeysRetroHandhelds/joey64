package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.data.LibraryGame

/*
 * Settings › Library's Show and Covers rows (approved mockup 2026-11-08, scratchpad build_libtab.py,
 * "Approve as drawn"): the library's order, which regions it shows, one version per game, cover size
 * and names. Pure, so LibraryArrangeTest checks them.
 */

/** Sort by: Name (A to Z, the default), Recently played, Most played (play time), Recently added. */
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
        /**
         * Only without box art: the library as it was (a game with no cover always showed its name on a plain
         * tile). The mockup drew Off as the default on the belief that such tiles were blank; asked again.
         */
        val DEFAULT = NoArt
    }
}

/** Name style: Short "Conker's Bad Fur Day", Full "Conker's Bad Fur Day (Europe)" (the file's name). */
enum class NameStyle(val label: String) { Short("Short"), Full("Full") }

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
     * What the grid shows of [games]: only the ticked [regions]; with [oneVersion], a game there in several
     * regions or revisions shows once, the first ticked region's newest. Versions are told by the header's
     * game code without its region letter, only among games No-Intro lists ([listed] MD5s): hacks keep the
     * original's code, so they always show on their own.
     */
    fun visible(games: List<LibraryGame>, regions: Set<RegionGroup>, oneVersion: Boolean, listed: Set<String>): List<LibraryGame> {
        val inRegion = games.filter { g -> regionsOf(g.region).any { it in regions } }
        if (!oneVersion) return inRegion
        val keep = HashMap<String, LibraryGame>()
        for (g in inRegion) {
            val key = versionKey(g, listed) ?: continue
            val had = keep[key]
            if (had == null || better(g, had, regions)) keep[key] = g
        }
        return inRegion.filter { g -> versionKey(g, listed)?.let { keep[it]?.id == g.id } ?: true }
    }

    private fun versionKey(g: LibraryGame, listed: Set<String>): String? =
        g.headerId?.takeIf { it.length == 4 && g.md5 in listed }?.take(3)

    /** [a] before [b]: an earlier ticked region, then not a beta or prototype, then the newer revision, then the older entry. */
    private fun better(a: LibraryGame, b: LibraryGame, regions: Set<RegionGroup>): Boolean {
        fun rank(g: LibraryGame) = RegionGroup.entries.indexOfFirst { it in regions && it in regionsOf(g.region) }.let { if (it < 0) 99 else it }
        fun early(g: LibraryGame) = EARLY.containsMatchIn(g.title)
        return when {
            rank(a) != rank(b) -> rank(a) < rank(b)
            early(a) != early(b) -> !early(a)
            (a.revision ?: 0) != (b.revision ?: 0) -> (a.revision ?: 0) > (b.revision ?: 0)
            else -> a.id < b.id
        }
    }

    private val EARLY = Regex("""\((Beta|Proto|Prototype|Demo|Sample|Kiosk)[^)]*\)""", RegexOption.IGNORE_CASE)

    /** Name style Short: the name before its first " (" or " [" tag ("Conker's Bad Fur Day (Europe)" -> "Conker's Bad Fur Day"). */
    fun shortTitle(title: String): String {
        val cut = listOf(title.indexOf(" ("), title.indexOf(" [")).filter { it > 0 }.minOrNull() ?: return title
        return title.substring(0, cut).trim().ifEmpty { title }
    }

    fun title(game: LibraryGame, style: NameStyle) = if (style == NameStyle.Short) shortTitle(game.title) else game.title

    /** Covers across: the screen's own count ([base], as before) with Small one more, Large one fewer, never under 2. */
    fun columns(base: Int, size: CoverSize) = (base + size.delta).coerceAtLeast(2)

    /** Look for new games: whether this start reads the folders. [lastDay] is the last day (epoch day) it read for Once a day. */
    fun lookNow(mode: LookFor, lastDay: Long?, today: Long): Boolean = when (mode) {
        LookFor.Start -> true
        LookFor.Daily -> lastDay != today
        LookFor.Asked -> false
    }
}
