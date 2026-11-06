package com.joeysretro.joey64.launcher.boxart

/**
 * Picks a game's libretro cover (plan, Phase 2; decided 2026-11-05: hash match first, then the
 * name). Pure, so it's tested on the JVM (BoxArtNamesTest).
 *
 * libretro names each cover after the game's No-Intro name, with each of the characters
 * & * / : ` < > ? \ | " turned into `_`.
 * In order:
 *  1. the No-Intro name of the ROM's MD5 ([BoxArtTable]), when that cover exists;
 *  2. the file's own name (a ROM missing from the table but named as No-Intro names it);
 *  3. the title alone, the text before the first " (", without punctuation: the LodgeNet,
 *     Limited Run and Switch Online releases, protos, translations named after the Japanese game,
 *     and DAT entries named apart from their covers ("Rat Attack! (USA)"). Of the covers with that
 *     title, the one sharing the most bracketed tags with the name, then not a beta, proto or demo,
 *     then the shortest name.
 *
 * Checked 2026-11-06 against the cover list then (1,115 covers) and the table (1,147 ROMs):
 * 981 by MD5, 62 by title, 104 none (homebrew, test carts, some protos). Joey's 82 on the Thor:
 * 63 by MD5, Resident Evil 2 by its file name, Tsumi to Batsu (a patched translation) by title;
 * the 17 hacks get none, none wrongly.
 */
object BoxArtNames {

    /** The cover's name as libretro stores it (without ".png"). */
    fun fileName(gameName: String): String = gameName.replace(UNSAFE, "_")

    /** The title alone, for the fallback: "Rat Attack! (USA)" -> "ratattack". */
    fun titleKey(name: String): String = name.substringBefore(" (").lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private fun tags(name: String): Set<String> = TAG.findAll(name).map { it.groupValues[1] }.toSet()

    /** The cover list, ready for [pick]. */
    class Covers(names: Collection<String>) {
        val names: Set<String> = names.toHashSet()
        internal val byTitle: Map<String, List<String>> = names.groupBy { titleKey(it) }
        val size get() = names.size
    }

    enum class How { Hash, FileName, Title }

    data class Match(val cover: String, val how: How)

    /** [tableName]: the ROM's No-Intro name, null when its MD5 isn't in the table. [fileName]: without extension. */
    fun pick(tableName: String?, fileName: String, covers: Covers): Match? {
        if (tableName != null && fileName(tableName) in covers.names) return Match(fileName(tableName), How.Hash)
        if (fileName(fileName) in covers.names) return Match(fileName(fileName), How.FileName)
        val source = tableName ?: fileName
        val same = covers.byTitle[titleKey(source)] ?: return null
        if (titleKey(source).isEmpty()) return null
        val wanted = tags(source)
        val best = same.minWithOrNull(
            compareByDescending<String> { (tags(it) intersect wanted).size }
                .thenBy { n -> PRERELEASE.any { n.contains(it) } }
                .thenBy { it.length }
                .thenBy { it },
        ) ?: return null
        return Match(best, How.Title)
    }

    /** The cover names in thumbnails.libretro.com's folder listing (an Apache index page). */
    fun parseListing(html: String): List<String> =
        HREF.findAll(html).map { java.net.URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8").removeSuffix(".png") }.toList()

    /** A cover's address under the folder [base] ("https://thumbnails.libretro.com/Nintendo - Nintendo 64/Named_Boxarts/"). */
    fun url(base: String, cover: String): String =
        base + java.net.URLEncoder.encode("$cover.png", "UTF-8").replace("+", "%20")

    private val HREF = Regex("href=\"([^\"?/]+\\.png)\"")
    private val UNSAFE = Regex("[&*/:`<>?\\\\|\"]")
    private val TAG = Regex("\\(([^)]*)\\)")
    private val PRERELEASE = listOf("(Beta", "(Proto", "(Demo", "(Debug")
}
