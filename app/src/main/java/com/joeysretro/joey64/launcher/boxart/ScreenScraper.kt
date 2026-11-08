package com.joeysretro.joey64.launcher.boxart

import com.joeysretro.joey64.BuildConfig
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ScreenScraper's API (api.screenscraper.fr/api2, decided by Joey 2026-11-07: the only box-art
 * source). Joey's developer login comes from local.properties at build time, scrambled as ES-DE
 * does its own; no user account is needed. Its rules: free apps only; without a user account one
 * request at a time, 10,000 a day, and the API can close to accountless callers when the server is
 * busy. Its media addresses carry the login, so they are never logged.
 */
object ScreenScraper {
    const val N64 = 14
    private const val BASE = "https://api.screenscraper.fr/api2/"
    private const val SOFT = "joey64"
    private const val KEY = "Joey64-box-art-key" // as in app/build.gradle.kts

    private fun unscramble(hex: String): String =
        String(ByteArray(hex.length / 2) { i -> (hex.substring(i * 2, i * 2 + 2).toInt(16) xor KEY[i % KEY.length].code).toByte() })

    private val devId by lazy { unscramble(BuildConfig.SS_DEV_ID) }
    private val devPassword by lazy { unscramble(BuildConfig.SS_DEV_PASSWORD) }

    /** False in a build made without the login: no box art can be looked up. */
    val usable: Boolean get() = BuildConfig.SS_DEV_ID.isNotEmpty() && BuildConfig.SS_DEV_PASSWORD.isNotEmpty()

    /** Not looked up for now: the server is busy, closed to accountless callers, or today's quota is used. Try later. */
    class Busy(val code: Int) : IOException("ScreenScraper answered $code")

    /** A lookup's answer: the game, or null when ScreenScraper doesn't know it. */
    fun jeuInfos(base: String, params: Map<String, String>): JSONObject? = call(base, "jeuInfos.php", params)?.optJSONObject("response")?.optJSONObject("jeu")

    /** A name search's games, best first (empty when none). */
    fun jeuRecherche(base: String, term: String): List<JSONObject> {
        val a = call(base, "jeuRecherche.php", mapOf("systemeid" to "$N64", "recherche" to term))?.optJSONObject("response")?.optJSONArray("jeux")
            ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotEmpty() }
    }

    fun base(dev: String?) = dev?.takeIf { it.isNotEmpty() }?.trimEnd('/')?.plus("/") ?: BASE

    /**
     * A game's front cover for [region], fetched with no lookup: mediaJeu.php by game ID (checked
     * 2026-11-07: the same bytes as the address a lookup gives). A region or game it doesn't have
     * answers the text "NOMEDIA", which isn't an image, so the caller falls back to a lookup.
     */
    fun coverUrl(base: String, gameId: String, region: String, maxWidth: Int): String {
        val q = mapOf("devid" to devId, "devpassword" to devPassword, "softname" to SOFT, "systemeid" to "$N64", "jeuid" to gameId, "media" to "box-2D($region)")
            .entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        return mediaUrl(base + "mediaJeu.php?" + q, maxWidth)
    }

    /** [url] (a media address from an answer) at most [maxWidth] wide, as JPEG. */
    fun mediaUrl(url: String, maxWidth: Int) = "$url&maxwidth=$maxWidth&outputformat=jpg"

    private fun call(base: String, endpoint: String, params: Map<String, String>): JSONObject? {
        val q = (mapOf("devid" to devId, "devpassword" to devPassword, "softname" to SOFT, "output" to "json") + params)
            .entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val body = try { get(base + endpoint + "?" + q, endpoint) } catch (e: FileNotFoundException) { return null }
        return try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { throw IOException("$endpoint: not JSON") }
    }

    /** GET [url]; [what] names it in errors, since the address holds the login. 404 and 400: not found. */
    fun get(url: String, what: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Joey64/${BuildConfig.VERSION_NAME}")
        try {
            val code = c.responseCode
            if (code == 404 || code == 400) throw FileNotFoundException(what)
            if (code in BUSY) throw Busy(code)
            if (code != 200) throw IOException("HTTP $code from $what")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    // 401 closed to accountless callers, 403 login refused, 423 API closed, 426 this app refused,
    // 429 too many at once, 430 today's quota used, 431 too many unknown ROMs today.
    private val BUSY = setOf(401, 403, 423, 426, 429, 430, 431)
}

/** What a ScreenScraper answer means for a cover. Pure, so it's tested on the JVM (ScreenScraperPickTest). */
object ScreenScraperPick {

    data class Game(val id: String, val name: String, val covers: List<Pair<String, String>>, val names: List<String> = listOf(name)) {
        /** One of its names (each region's: Star Fox 64 is also Lylat Wars) [fits] [term]. */
        fun fits(term: String) = names.any { fits(term, it) }
    }

    /** [jeu] as a game with its front covers (region, address); null for ScreenScraper's "not a game" holders. */
    fun game(jeu: JSONObject?): Game? {
        if (jeu == null) return null
        val names = jeu.optJSONArray("noms")
        val all = (0 until (names?.length() ?: 0)).mapNotNull { names?.optJSONObject(it) }
        val name = (all.firstOrNull { it.optString("region") in listOf("us", "wor", "ss") } ?: all.firstOrNull())?.optString("text").orEmpty()
        if (name.startsWith("ZZZ")) return null
        val medias = jeu.optJSONArray("medias")
        val covers = (0 until (medias?.length() ?: 0)).mapNotNull { medias?.optJSONObject(it) }
            .filter { it.optString("type") == "box-2D" && it.optString("url").isNotEmpty() }
            .map { it.optString("region") to it.optString("url") }
        return Game(jeu.optString("id"), name, covers, all.map { it.optString("text") }.filter { it.isNotEmpty() }.ifEmpty { listOf(name) })
    }

    /** The cover for [fileName]'s region ("(USA)" -> us, "(Europe)" -> eu, "(Japan)" -> jp), else the usual order. */
    fun cover(game: Game, fileName: String): Pair<String, String>? {
        // The No-Intro name's first region: its own cover, then (a European country) Europe's.
        val want = REGIONS.firstOrNull { (tag, _) -> "($tag" in fileName }?.second.orEmpty()
        val order = want + listOf("us", "wor", "eu", "ss", "jp")
        return order.firstNotNullOfOrNull { r -> game.covers.firstOrNull { it.first == r } } ?: game.covers.firstOrNull()
    }

    /** No-Intro region tags and ScreenScraper's region names for them, best first. */
    private val REGIONS = listOf(
        "USA" to listOf("us"), "Europe" to listOf("eu"), "Japan" to listOf("jp"),
        "Germany" to listOf("de", "eu"), "France" to listOf("fr", "eu"), "Spain" to listOf("sp", "eu"),
        "Italy" to listOf("it", "eu"), "Netherlands" to listOf("nl", "eu"), "Australia" to listOf("au", "eu"),
        "Brazil" to listOf("br", "us"), "Korea" to listOf("kr", "jp"), "China" to listOf("cn", "jp"),
    )

    /** The name to search: without tags in brackets, "(USA)", "[Dan_GPTV]", "v1.1". */
    fun searchTerm(name: String): String =
        name.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""\s+v\d+(\.\d+)*\b""", RegexOption.IGNORE_CASE), "").trim()

    private val SMALL = setOf("the", "of", "a", "an", "and", "to", "no")

    private fun words(s: String) = s.lowercase().replace("&", " and ").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in SMALL }.toSet()

    /**
     * A name fits [term] when they have the same words, or the name has three or more and over 3/4
     * of them are in the term ("Super Mario 64 - Star Road" fits "Super Mario Star Road Final -
     * Deluxe Edition"), or the term's words, two or more, are all in the name ("Sealed Palace" fits
     * "Legend Of Zelda - The Sealed Palace"). "Mario Party" doesn't fit "Paper Mario", nor OoT's
     * "The Legend of Zelda" or "Legend Of Zelda - Ocarina Of Time, The" (3 of 4) "Legend of Zelda, The - Sands of Time".
     */
    fun fits(term: String, name: String): Boolean {
        val n = words(name); val t = words(term)
        if (n.isEmpty() || t.isEmpty()) return false
        val shared = (n intersect t).size
        return n == t || (n.size >= 3 && shared * 4 > n.size * 3) || (t.size >= 2 && shared == t.size)
    }
}
