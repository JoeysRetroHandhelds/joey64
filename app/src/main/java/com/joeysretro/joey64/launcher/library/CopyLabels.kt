package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.data.LibraryGame

/**
 * Copies of a game that look alike in the grid and in Search (approved mockup A, Joey 2026-11-11: "A, as drawn";
 * scratchpad regional/build_regional.py). The 1,231-game test on the Odin 3 showed three identical
 * "007 - The World Is Not Enough" tiles: the Short name style cuts "(USA)", "(v2) (Beta)" and "(Rev 1)" off, and their
 * box art is the same. A game whose short name another game on the same screen also has gets a line under its name
 * saying which copy it is: "USA", "USA · v2 · Beta", "Japan · Rev 1".
 *
 * The line is the region (the name's region tag, else the header's destination code), then the name's tags that not
 * every copy has; tags they all share are left out ("Proto", "Aftermarket"), and the languages ("En,Fr,De") only
 * count when the copies would look alike without them. Pure, so CopyLabelsTest checks it.
 */
object CopyLabels {

    /** Each game in [games] that shares its short name with another one there, to its line. Games with no line aren't in it. */
    fun of(games: List<LibraryGame>): Map<Long, String> {
        val groups = games.groupBy { LibraryArrange.shortTitle(it.title).lowercase() }.values.filter { it.size > 1 }
        if (groups.isEmpty()) return emptyMap()
        val out = HashMap<Long, String>()
        for (group in groups) {
            val lines = lines(group.map { it.title to it.region })
            group.zip(lines).forEach { (g, line) -> if (line.isNotEmpty()) out[g.id] = line }
        }
        return out
    }

    /** The lines for one group of look-alike copies, given each one's name and header destination code, in order. */
    fun lines(copies: List<Pair<String, Char?>>): List<String> {
        if (copies.isEmpty()) return emptyList()
        val parts = copies.map { (title, code) -> partsOf(title, code) }
        val shared = parts.map { it.other.toSet() }.reduce { a, b -> a intersect b }
        val lines = parts.map { p -> (listOfNotNull(p.region) + p.other.filter { it !in shared }).joinToString(SEPARATOR) }
        if (lines.toSet().size == lines.size) return lines
        // Still alike: the languages are what's left to tell them apart.
        return parts.zip(lines).map { (p, line) -> listOf(line, p.languages.orEmpty()).filter { it.isNotEmpty() }.joinToString(SEPARATOR) }
    }

    const val SEPARATOR = " · "

    private class Parts(val region: String?, val languages: String?, val other: List<String>)

    /** The tags after a name's short part: its first region tag, its first language list, and the rest in order. */
    private fun partsOf(title: String, code: Char?): Parts {
        val tail = title.substring(LibraryArrange.shortTitle(title).length.coerceAtMost(title.length))
        var region: String? = null
        var languages: String? = null
        val other = ArrayList<String>()
        for (m in TAG.findAll(tail)) {
            val tag = (m.groups[1] ?: m.groups[2])?.value?.trim().orEmpty()
            when {
                tag.isEmpty() -> {}
                region == null && tag.split(',').all { it.trim() in REGIONS } -> region = tag
                languages == null && LANGUAGES.matches(tag.replace(" ", "")) -> languages = tag
                else -> (if (tag in NICE) NICE[tag] else tag)?.let { other += it }
            }
        }
        return Parts(region ?: fromHeader(code), languages, other)
    }

    /** The header's destination code as a region, for a name without a region tag (a file name, a hack). */
    private fun fromHeader(code: Char?): String? = when (code) {
        'E' -> "USA"
        'N' -> "Canada"
        'A' -> "Japan, USA"
        'J' -> "Japan"
        'P', 'X', 'Y', 'Z' -> "Europe"
        'D' -> "Germany"
        'F' -> "France"
        'I' -> "Italy"
        'S' -> "Spain"
        'H' -> "Netherlands"
        'W' -> "Scandinavia"
        'U' -> "Australia"
        'B' -> "Brazil"
        'C' -> "China"
        'K' -> "Korea"
        else -> null
    }

    private val TAG = Regex("""\(([^)]*)\)|\[([^\]]*)\]""")
    private val LANGUAGES = Regex("""[A-Z][a-z](,[A-Z][a-z])*""")
    /** No-Intro and GoodN64 names' region words. */
    private val REGIONS = setOf("USA", "Europe", "Japan", "World", "Germany", "France", "Spain", "Italy", "Brazil", "Australia",
        "Korea", "China", "Asia", "Canada", "Netherlands", "Sweden", "Scandinavia", "UK", "Taiwan", "Hong Kong", "Russia", "Unknown")
    /** Tags said in words ("[b]" is a bad dump), or left out (null: "[!]", a good dump, tells nothing apart). */
    private val NICE = mapOf("b" to "Bad dump", "!" to null)
}
