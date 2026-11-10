package com.joeysretro.joey64.video

import com.joeysretro.joey64.cheats.Cheat
import com.joeysretro.joey64.cheats.CheatLogic

/**
 * Widescreen and 60 fps (approved mockup version A, Joey 2026-11-08: two rows in Settings › Video › Picture). Their
 * codes are in the game's cheat lists, flagged `widescreen` / `60fps` (joey64-data), and kept off the Cheats page.
 */
enum class ExtraKind(val flag: String) { Widescreen("widescreen"), Fps("60fps") }

/** One version of a game's widescreen or 60 fps code, as its popup lists it. [name] is the cheat's own name (stored). */
data class ExtraVersion(val name: String, val label: String, val detail: String?, val wip: Boolean, val codes: List<String>) {
    /** The picture's shape this widescreen version is made for. */
    val shape: Pair<Int, Int> get() = if ("21:9" in name) 21 to 9 else 16 to 9
}

/** A game's versions of each, from the first list (in [CheatLogic.SOURCES] order) that has that kind. */
data class GameExtras(val widescreen: List<ExtraVersion>, val fps: List<ExtraVersion>, val source: Map<ExtraKind, String>, val pal: Boolean) {
    fun of(k: ExtraKind) = if (k == ExtraKind.Widescreen) widescreen else fps

    /** The version a stored value picks: "off" none, "auto" (All games' "When a game has one") the first one not marked WIP. */
    fun pick(k: ExtraKind, value: String): ExtraVersion? {
        val list = of(k)
        return when (value) {
            "off", "" -> null
            ALL -> list.firstOrNull { !it.wip } ?: list.firstOrNull()
            // A name the lists no longer have (an update renamed it): the first, as "When a game has one" would.
            else -> list.firstOrNull { it.name == value } ?: list.firstOrNull { !it.wip } ?: list.firstOrNull()
        }
    }

    companion object {
        /** All games' "When a game has one". */
        const val ALL = "auto"
        val NONE = GameExtras(emptyList(), emptyList(), emptyMap(), pal = false)

        /** [lists] by source (as downloaded, before [CheatLogic.tidy]); [key] the header key "CRC1-CRC2-CC", for PAL. */
        fun from(lists: Map<String, List<Cheat>>, key: String?): GameExtras {
            val found = HashMap<ExtraKind, Pair<String, List<ExtraVersion>>>()
            for (src in CheatLogic.SOURCES + (lists.keys - CheatLogic.SOURCES.toSet())) {
                for (k in ExtraKind.entries) {
                    if (k in found) continue
                    val vs = lists[src].orEmpty().filter { k.flag in it.flags }.mapNotNull { version(k, it) }
                        .distinctBy { it.codes }
                    if (vs.isNotEmpty()) found[k] = src to vs
                }
            }
            val ws = found[ExtraKind.Widescreen]?.second.orEmpty()
            val fps = found[ExtraKind.Fps]?.second.orEmpty()
            return GameExtras(named(ws), named(fps), found.mapValues { it.value.first }, isPal(key))
        }

        /** One version alone reads On, as a plain switch. */
        private fun named(vs: List<ExtraVersion>) = if (vs.size == 1 && !vs[0].wip) listOf(vs[0].copy(label = "On")) else vs

        /** A code Joey64 can run as it is: no value to pick (a single option is filled in), no GameShark button. */
        private fun version(k: ExtraKind, c: Cheat): ExtraVersion? {
            if ("needs_value" in c.flags || c.gsButton) return null
            val opts = c.options.filter { !it.label.equals("Off", ignoreCase = true) }
            val codes = when {
                c.options.isEmpty() -> c.codes
                opts.size == 1 -> CheatLogic.resolve(c, opts[0].value)
                else -> return null
            }
            if (codes.isEmpty()) return null
            val wip = Regex("\\bWIP\\b", RegexOption.IGNORE_CASE).containsMatchIn(c.label)
            val note = tidyNote(c.note)
            return if (k == ExtraKind.Widescreen) {
                when {
                    "21:9" in c.label -> ExtraVersion(c.name, "21:9", join("For extra-wide screens; black bars at the top and bottom on 16:9", note), wip, codes)
                    "16:9" in c.label -> ExtraVersion(c.name, "16:9", join("For most phones and handhelds", note), wip, codes)
                    else -> ExtraVersion(c.name, fpsLabel(c.label), note, wip, codes)
                }
            } else ExtraVersion(c.name, fpsLabel(c.label), if (wip) join("May glitch", note) else note, wip, codes)
        }

        /** "Sixty Frames (1P Mode)" -> "60 fps (1 player)", "WIP 60 FPS hack" -> "60 fps hack" (the WIP tag says the rest). */
        internal fun fpsLabel(name: String): String {
            var s = name.replace(Regex("\\bWIP\\b", RegexOption.IGNORE_CASE), "")
            s = s.replace(Regex("Sixty (Frames|Hack)", RegexOption.IGNORE_CASE), "60 fps")
                .replace(Regex("Fifty (Frames|Hack)", RegexOption.IGNORE_CASE), "50 fps")
                .replace(Regex("\\b(\\d\\d) ?FPS\\b", RegexOption.IGNORE_CASE), "$1 fps")
                .replace(Regex("\\(1P Mode\\)", RegexOption.IGNORE_CASE), "(1 player)")
            return s.replace(Regex("\\s+"), " ").trim().ifEmpty { name }
        }

        /** The list's note without what the popup already says (one version at a time; the shape is the label). */
        internal fun tidyNote(note: String?): String? {
            var s = note ?: return null
            s = s.replace(Regex("Do not use with other widescreen (enhancements|hacks)( or hacks)?\\.?", RegexOption.IGNORE_CASE), "")
                .replace(Regex("For (16|21):9 aspect ratios\\.?", RegexOption.IGNORE_CASE), "")
            s = s.replace(Regex(",(?=\\S)"), ", ").replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ')
            return s.ifEmpty { null }
        }

        private fun join(a: String, b: String?) = if (b == null) a else "$a. $b"

        /** The header's country byte: Europe, Germany, France, Italy, Spain, Australia, X/Y (other PAL), Netherlands, Scandinavia. */
        internal fun isPal(key: String?): Boolean {
            val cc = key?.split('-')?.getOrNull(2)?.toIntOrNull(16) ?: return false
            return cc.toChar() in "PDFISUXYHLW"
        }
    }
}

/**
 * What a game starts with: the codes to give the core, the widescreen shape for Aspect ratio's Auto, and whether the CPU
 * runs faster for a 60 fps code. [log] says what was picked.
 */
data class ExtrasOn(val codeLines: List<String>, val shape: Pair<Int, Int>?, val fps: Boolean, val log: String,
                    val widescreenName: String? = null, val fpsName: String? = null) {
    companion object {
        val NONE = ExtrasOn(emptyList(), null, false, "none")

        fun of(x: GameExtras, v: VideoSettings, hardcore: Boolean): ExtrasOn {
            if (hardcore) return NONE.copy(log = "off in Hardcore")
            val ws = x.pick(ExtraKind.Widescreen, v[VideoKey.Widescreen])
            val fps = x.pick(ExtraKind.Fps, v[VideoKey.Fps])
            val lines = listOfNotNull(ws, fps).map { it.codes.joinToString(",") }
            val log = "widescreen ${ws?.name ?: "off"} (${x.widescreen.size} here), fps ${fps?.name ?: "off"} (${x.fps.size} here)"
            return ExtrasOn(lines, ws?.shape, fps != null, log, ws?.name, fps?.name)
        }

        /**
         * Widescreen's "When a game has one" for a game with a 16:9 mode of its own ([OwnWidescreen]) and no code picked:
         * no code, only the 16:9 picture for Aspect ratio's Auto (Joey 2026-11-10: "Yes, use it"). The game's own option
         * is still the player's to set in the game. Not in Hardcore, which locks Widescreen.
         */
        fun withOwnMode(on: ExtrasOn, gameCode: String?, v: VideoSettings, hardcore: Boolean): ExtrasOn {
            if (hardcore || on.shape != null || v[VideoKey.Widescreen] != GameExtras.ALL) return on
            val where = OwnWidescreen.where(gameCode) ?: return on
            return on.copy(shape = 16 to 9, log = on.log + ", the game's own 16:9 mode: 16:9 picture (set in the game: $where)")
        }
    }
}

/**
 * Games with a 16:9 mode of their own, drawn squeezed for a widescreen TV, so they need only the picture stretched to 16:9:
 * the LaunchBox N64 Widescreen Project's first group (read 2026-11-09) plus Conker's Bad Fur Day. By the header's game
 * code without the region letter; codes from our ROMs' headers (compat set) or, for the six we don't have, ares'
 * and cen64's game lists.
 */
object OwnWidescreen {
    private val GAMES = mapOf(
        "NO7" to "007: The World Is Not Enough, options",
        "NAR" to "Armorines, options",
        "NB7" to "Banjo-Tooie, options",
        "NDO" to "Donkey Kong 64, options",
        "NGE" to "GoldenEye 007, Ratio 16:9 on the watch",
        "NJF" to "Jet Force Gemini, options",
        "NML" to "Mickey's Speedway USA, options",
        "NMI" to "Mission: Impossible, options",
        "NPD" to "Perfect Dark, Options › Video › Ratio 16:9, per agent file",
        "NDT" to "South Park, options",
        "NRC" to "Top Gear Overdrive, options",
        "NSC" to "Starshot: Space Circus Fever, options",
        "NSP" to "Superman, options",
        "NT2" to "Turok 2, options",
        "NTK" to "Turok 3, options",
        "NFU" to "Conker's Bad Fur Day, options",
    )

    /** Where [gameCode]'s ("NPDE" or "NPD") own 16:9 option is, or null when it has none on the list. */
    fun where(gameCode: String?): String? = gameCode?.takeIf { it.length >= 3 }?.let { GAMES[it.take(3).uppercase()] }
}
