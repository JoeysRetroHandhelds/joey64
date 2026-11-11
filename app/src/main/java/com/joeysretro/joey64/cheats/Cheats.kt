package com.joeysretro.joey64.cheats

import com.joeysretro.joey64.launcher.library.RomHeader
import org.json.JSONArray
import org.json.JSONObject

/** A value a cheat can be set to ("0B" Ghost); [value] replaces the `?` digits of its codes. */
data class CheatOption(val value: String, val label: String)

/**
 * One cheat from a `joey64-data` list (plan, Phase 6). [name] is its folders and its own name,
 * split by `\` ("Infinite Items\2 player Mode\Player 1"). [codes] are GameShark codes,
 * "8016609D 00??". [flags]: "gs_button", "boot", "needs_value" (the repo's README).
 */
data class Cheat(
    val name: String,
    val codes: List<String>,
    val options: List<CheatOption> = emptyList(),
    val note: String? = null,
    val flags: Set<String> = emptySet(),
    val source: String = "",
) {
    val path: List<String> get() = name.split('\\').map { it.trim() }.filter { it.isNotEmpty() }
    val label: String get() = path.lastOrNull() ?: name
    /** A boot-time code: it works from the game's start, so turning it on mid-game needs a reset. */
    val boot get() = "boot" in flags
    /** Its codes run only while the GameShark's button is held (hotkey, or the pause menu's Cheats). */
    val gsButton get() = "gs_button" in flags
}

/** A folder of cheats, or one cheat, as the Cheats page lists them. */
sealed interface CheatNode {
    val label: String

    data class Folder(override val label: String, val children: List<CheatNode>) : CheatNode {
        /** Every cheat inside, folders within folders included ("6 cheats"). */
        val count: Int get() = children.sumOf { if (it is Folder) it.count else 1 }
    }

    data class Item(val cheat: Cheat) : CheatNode {
        override val label get() = cheat.label
    }
}

object CheatLogic {
    /** The order lists are merged in: our own fixes first, then Project64's (the fullest checked list), libretro's last. */
    val SOURCES = listOf("joey64", "project64", "rmg-custom", "fz-retroben", "libretro")

    /** Flags whose codes live outside Cheats, in their own menu spots (Joey, 2026-11-06). */
    val OWN_SPOTS = setOf("widescreen", "60fps")

    /**
     * libretro's cheats go in a folder of their own at the bottom (Joey, 2026-11-06): its lists
     * are long and messy (headings typed as cheats, repeats of Project64's codes per player). A
     * game only libretro has cheats for shows them without the folder.
     */
    const val LIBRETRO_FOLDER = "More from libretro"

    /** The keys a game's lists can be under: "CRC1-CRC2-CC", then "CRC1-CRC2" (a source that gives no country). */
    fun keys(fullKey: String?): List<String> =
        if (fullKey.isNullOrEmpty()) emptyList() else listOf(fullKey, fullKey.substringBeforeLast('-')).distinct()

    /** The header's key: its two checksums and its country byte. */
    fun keyOf(header: RomHeader): String =
        "%08X-%08X-%02X".format(header.crc1, header.crc2, header.region.code.code and 0xFF)

    /** A source is a folder in files/cheatlists/: only a plain name, so an index can't put a list outside it (code audit 3, p1 L2). */
    private val SOURCE_NAME = Regex("[A-Za-z0-9_-]+")

    fun parseIndex(text: String): Map<String, Map<String, Int>> {
        val games = JSONObject(text).getJSONObject("games")
        val out = HashMap<String, Map<String, Int>>(games.length() * 2)
        for (k in games.keys()) {
            val src = games.getJSONObject(k).getJSONObject("sources")
            out[k] = src.keys().asSequence().filter { SOURCE_NAME.matches(it) }.associateWith { src.getInt(it) }
        }
        return out
    }

    fun parseList(text: String): List<Cheat> {
        val o = JSONObject(text)
        val source = o.optString("source")
        val arr = o.getJSONArray("cheats")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            Cheat(
                name = c.getString("name"),
                codes = c.getJSONArray("codes").strings(),
                options = c.optJSONArray("options")?.let { a ->
                    (0 until a.length()).map { j -> a.getJSONArray(j).let { p -> CheatOption(p.getString(0), p.getString(1)) } }
                } ?: emptyList(),
                note = c.optString("note").takeIf { it.isNotEmpty() },
                flags = c.optJSONArray("flags")?.strings()?.toSet() ?: emptySet(),
                source = source,
            )
        }
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    /**
     * A cheat as the page shows it, or null when Joey64 can't run it: a value with nothing to
     * pick from. (Codes that need the GameShark's button stay since 2026-11-07: it can be pressed.) A source's
     * own "Off" option goes (the page's Off leaves the game alone); when one option is left, its
     * value goes into the codes and the cheat is a plain On / Off.
     */
    fun tidy(c: Cheat): Cheat? {
        if ("needs_value" in c.flags) return null
        // Widescreen and 60 fps get their own menu spots, not Cheats (Joey, 2026-11-06).
        if (c.flags.any { it in OWN_SPOTS }) return null
        val opts = c.options.filter { !it.label.equals("Off", ignoreCase = true) }
        if (c.options.isNotEmpty() && opts.isEmpty()) return null
        if (c.options.isNotEmpty() && opts.size == 1) return c.copy(codes = resolve(c, opts[0].value), options = emptyList())
        return c.copy(options = opts)
    }

    /**
     * The lists of one game merged, in [SOURCES] order: a cheat whose name a list before it has
     * already given is left out, and so is one with the same codes and options under another name.
     */
    fun merge(lists: Map<String, List<Cheat>>): List<Cheat> {
        val names = HashSet<String>()
        val codes = HashSet<String>()
        val out = ArrayList<Cheat>()
        for (src in SOURCES + (lists.keys - SOURCES.toSet())) {
            for (raw in lists[src].orEmpty()) {
                val c = tidy(raw) ?: continue
                val sig = c.codes.joinToString(",") + "|" + c.options.joinToString(",") { it.value }
                if (!names.add(c.name.lowercase())) continue
                if (!codes.add(sig)) continue
                out += c
            }
        }
        if (out.any { it.source != "libretro" })
            return out.map { if (it.source == "libretro") it.copy(name = LIBRETRO_FOLDER + "\\" + it.name) else it }
        return out
    }

    /** [cheats] as folders, in the order they first appear. */
    fun tree(cheats: List<Cheat>): List<CheatNode> {
        class Dir(val label: String) { val items = ArrayList<Any>() ; val dirs = HashMap<String, Dir>() }
        val root = Dir("")
        for (c in cheats) {
            var d = root
            val path = c.path.ifEmpty { listOf(c.name) }
            for (seg in path.dropLast(1)) {
                d = d.dirs.getOrPut(seg) { Dir(seg).also { d.items += it } }
            }
            d.items += c
        }
        fun build(d: Dir): List<CheatNode> = d.items.map { if (it is Dir) CheatNode.Folder(it.label, build(it)) else CheatNode.Item(it as Cheat) }
        return build(root)
    }

    /** The folder at [path] in [nodes] (empty: the top), or null when it's gone. */
    fun at(nodes: List<CheatNode>, path: List<String>): List<CheatNode>? {
        var here = nodes
        for (seg in path) here = (here.firstOrNull { it is CheatNode.Folder && it.label == seg } as? CheatNode.Folder)?.children ?: return null
        return here
    }

    /** [c]'s codes with [value] in place of their `?` digits (its last digits when it's longer, zeros before when shorter). */
    fun resolve(c: Cheat, value: String?): List<String> = c.codes.map { code ->
        val q = code.count { it == '?' }
        if (q == 0 || value == null) code
        else {
            val v = value.takeLast(q).padStart(q, '0')
            var i = 0
            buildString { for (ch in code) append(if (ch == '?') v[i++] else ch) }
        }
    }

    /**
     * The grey line under a cheat's name (approved 2026-11-07): its list's note ("Press L to stop"),
     * after "Needs the GameShark button" for codes that do, and "Takes effect after a reset" for boot codes.
     */
    fun noteLine(c: Cheat): String? {
        val note = c.note?.trim()?.takeIf { it.isNotEmpty() }
        val main = if (c.gsButton) {
            if (note == null) "Needs the GameShark button" else "Needs the GameShark button: " + lowerFirst(note)
        } else note
        return listOfNotNull(if (c.boot) "Takes effect after a reset" else null, main).joinToString(" · ").ifEmpty { null }
    }

    /** "Press L to stop" -> "press L to stop"; "L to stop" and "GS Button" stay (a name or a button). */
    private fun lowerFirst(s: String) =
        if (s.length > 1 && s[0].isUpperCase() && s[1].isLowerCase()) s[0].lowercaseChar() + s.substring(1) else s

    private val PAIR = Regex("([0-9A-Fa-f]{8})[\\s:]*([0-9A-Fa-f]{4})")

    /**
     * Codes the user typed (Add code): "8018ED10 0001" pairs, one per line or run together. Null
     * when anything but codes is left, so a typo is shown, not dropped.
     */
    fun parseTyped(text: String): List<String>? {
        val found = PAIR.findAll(text).toList()
        if (found.isEmpty()) return null
        val rest = PAIR.replace(text, "").filter { !it.isWhitespace() && it != ',' && it != ';' && it != '+' }
        if (rest.isNotEmpty()) return null
        return found.map { "${it.groupValues[1].uppercase()} ${it.groupValues[2].uppercase()}" }
    }
}
