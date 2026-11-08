package com.joeysretro.joey64.launcher.boxart

import android.content.Context

/**
 * Per-game box-art fixes and known games (Joey 2026-11-07): `assets/boxart/fixes.tsv`, one line per
 * ROM MD5 with the ScreenScraper game whose cover it gets, or "none", and optionally the cover's
 * region. Checked before any lookup, so a listed game gets the same cover on every device; with a
 * region its cover is fetched straight away, with no lookup (about 1.3 s a game instead of 3-6).
 * Hand-checked lines come first and win; generated ones (devtools/boxart/known-games.py) follow.
 */
object BoxArtFixes {
    sealed interface Fix {
        /** [region]: the cover's, e.g. "us" (ScreenScraper's media name box-2D(us)); null = look the game up for it. */
        data class Game(val id: String, val region: String? = null) : Fix
        data object None : Fix
    }

    fun parse(text: String): Map<String, Fix> {
        val m = HashMap<String, Fix>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line[0] == '#') continue
            val cols = line.split('\t')
            if (cols.size < 2 || !MD5.matches(cols[0])) continue
            val what = cols[1].trim()
            val region = cols.getOrNull(2)?.trim()?.takeIf { REGION.matches(it) }
            val key = cols[0].lowercase()
            if (key in m) continue // the first line wins: hand-checked ones come before generated ones
            m[key] = when {
                what == "none" -> Fix.None
                what.isNotEmpty() && what.all { it.isDigit() } -> Fix.Game(what, region)
                else -> continue
            }
        }
        return m
    }

    @Volatile private var fixes: Map<String, Fix>? = null

    fun get(context: Context): Map<String, Fix> =
        fixes ?: synchronized(this) {
            fixes ?: parse(context.assets.open("boxart/fixes.tsv").bufferedReader().use { it.readText() }).also { fixes = it }
        }

    private val MD5 = Regex("[0-9a-fA-F]{32}")
    private val REGION = Regex("[a-z]{2,3}")
}
