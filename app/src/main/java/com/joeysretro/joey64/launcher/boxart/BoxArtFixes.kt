package com.joeysretro.joey64.launcher.boxart

import android.content.Context

/**
 * Per-game box-art fixes (Joey 2026-11-07): `assets/boxart/fixes.tsv`, one line per ROM MD5 with
 * the ScreenScraper game whose cover it gets, or "none". Checked before any lookup, so a fixed game
 * gets the same cover on every device.
 */
object BoxArtFixes {
    sealed interface Fix {
        data class Game(val id: String) : Fix
        data object None : Fix
    }

    fun parse(text: String): Map<String, Fix> {
        val m = HashMap<String, Fix>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line[0] == '#') continue
            val cols = line.split('\t')
            if (cols.size < 2 || !MD5.matches(cols[0])) continue
            val what = cols[1].trim()
            m[cols[0].lowercase()] = when {
                what == "none" -> Fix.None
                what.isNotEmpty() && what.all { it.isDigit() } -> Fix.Game(what)
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
}
