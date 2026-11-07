package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FileCopy
import com.joeysretro.joey64.launcher.storage.FileCopy.readNBytesCompat
import com.joeysretro.joey64.launcher.storage.FileSpace
import com.joeysretro.joey64.launcher.storage.ReadOnlySpace
import com.joeysretro.joey64.launcher.storage.SpaceEntry
import java.io.InputStream
import java.util.zip.InflaterInputStream

/** A RetroArch save or state Joey64 can use. [thumbnail]: a state's `.png` beside it, copied with it. */
data class RaFile(val kind: DataKind, val path: List<String>, val size: Long, val modified: Long, val thumbnail: RaFile? = null) {
    val name get() = path.last()
}

data class RaFound(
    val saves: List<RaFile>,
    val states: List<RaFile>,
    /** The same name in two of RetroArch's folders (sorted by core and not, say): the newest is used, these are left out. */
    val olderCopies: List<RaFile>,
    /** N64 saves in another emulator's format (`.eep`, `.sra`, `.fla`, `.mpk`): not copied (waiting on Joey's call). */
    val otherFormatSaves: List<RaFile>,
)

/** Joey64 already has a different file under the same name: the user chooses (plan: clashes ask, one at a time). */
data class RaClash(val file: RaFile, val ours: SpaceEntry) {
    val theirsNewer get() = file.modified > ours.modified
}

data class RaImportPlan(val toCopy: List<RaFile>, val alreadyThere: List<RaFile>, val clashes: List<RaClash>)

data class RaImportResult(val copied: Int, val failed: List<String>)

/**
 * Import from RetroArch (plan, Phase 1, approved 2026-11-05). Copies only: RetroArch's folder is
 * opened through [ReadOnlySpace], so it is read and never changed, moved or deleted. Every N64
 * save and state is copied, not only the games in the ROM folders, so a game added later already
 * has its save. Names stay as they are (RetroArch names them after the ROM file, as Joey64 does).
 *
 * Which files are N64: a `.srm` of exactly 296960 bytes (the save RAM of the mupen64plus-based
 * cores, always the same size: EEPROM 0x800, four controller paks 4 x 0x8000, SRAM 0x8000 and
 * FlashRAM 0x20000), and a state whose
 * data is the core's (`M64+SAVE`, inside RetroArch's RZIP and RASTATE wrapping). Folder names are
 * not trusted, so every way RetroArch sorts its folders (by core, by content folder, both, or
 * flat) is found the same way.
 */
object RetroArchImport {
    const val N64_SRM_SIZE = 296960L
    private val STATE = Regex("""^.+\.state(\d+|\.auto)?$""")
    private val OTHER_FORMAT_SAVES = setOf("eep", "sra", "fla", "mpk")
    /** saves/<core>/<content folder>/file: two levels below saves/ and states/. */
    private const val MAX_DEPTH = 2

    /**
     * Finds the N64 files in RetroArch's folder [ra] (its root, with `saves/` and `states/`; or
     * a saves or states folder picked by itself). Reads only states, and only their first bytes.
     */
    fun find(ra: FileSpace, threads: Int = 1): RaFound {
        val space = ra as? ReadOnlySpace ?: ReadOnlySpace(ra)
        val top = space.list(emptyList()).filter { it.isDir }.map { it.name }
        val roots = listOf("saves", "states").filter { it in top }.map { listOf(it) }.ifEmpty { listOf(emptyList()) }

        val saves = ArrayList<RaFile>()
        val states = ArrayList<RaFile>()
        val other = ArrayList<RaFile>()
        for (root in roots) walk(space, root, 0) { dir, entries ->
            val names = entries.associateBy { it.name }
            for (e in entries) {
                // Hidden files, and Syncthing's conflict copies (`name.sync-conflict-<date>-<id>.srm`),
                // which RetroArch never loads; Syncthing's own folders (.stversions) start with a dot.
                if (e.isDir || e.name.startsWith(".") || ".sync-conflict-" in e.name) continue
                val path = dir + e.name
                val ext = e.name.substringAfterLast('.', "").lowercase()
                when {
                    ext == "srm" && e.size == N64_SRM_SIZE -> saves += RaFile(DataKind.Saves, path, e.size, e.modified)
                    ext in OTHER_FORMAT_SAVES -> other += RaFile(DataKind.Saves, path, e.size, e.modified)
                    // A state; whether it's N64 is read from its first bytes below.
                    STATE.matches(e.name) -> {
                        val png = names[e.name + ".png"]?.takeIf { !it.isDir }?.let { RaFile(DataKind.States, dir + it.name, it.size, it.modified) }
                        states += RaFile(DataKind.States, path, e.size, e.modified, png)
                    }
                }
            }
        }
        val n64States = inParallel(states, threads) { isN64State(space, it.path) }
        val (newSaves, oldSaves) = newestByName(saves)
        val (newStates, oldStates) = newestByName(n64States)
        return RaFound(newSaves, newStates, oldSaves + oldStates, other)
    }

    /** The files of [files] that pass [test], in their order; [threads] at a time (opening a file through a picked folder is slow, the bytes are few). */
    private fun inParallel(files: List<RaFile>, threads: Int, test: (RaFile) -> Boolean): List<RaFile> {
        if (threads <= 1 || files.size < 2) return files.filter(test)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val results = files.map { f -> pool.submit<Boolean> { test(f) } }
            return files.filterIndexed { i, _ -> results[i].get() }
        } finally {
            pool.shutdown()
        }
    }

    /**
     * Walks [dir] and the folders below it to [MAX_DEPTH]. Leaves out RetroArch's folders for
     * other cores ("Beetle PSX", "SwanStation"; [isOtherCore]): they hold no N64 files, and
     * checking their states was most of the time (Joey's Thor: 1,127 states, 108 of them N64).
     */
    private fun walk(space: FileSpace, dir: List<String>, depth: Int, visit: (List<String>, List<SpaceEntry>) -> Unit) {
        val entries = space.list(dir)
        visit(dir, entries)
        if (depth < MAX_DEPTH) for (e in entries) {
            if (!e.isDir || e.name.startsWith(".") || isOtherCore(e.name)) continue
            walk(space, dir + e.name, depth + 1, visit)
        }
    }

    /**
     * A folder RetroArch made for a core other than the N64 ones ("Sort saves/states into folders
     * by core name" names them after the core). Never Mupen64Plus-Next, Mupen64Plus or ParaLLEl
     * N64. A folder named after a game or content folder is never one of these, so it's walked.
     */
    fun isOtherCore(name: String): Boolean {
        val n = name.trim().lowercase()
        if (n in N64_CORES) return false
        return n in OTHER_CORES || OTHER_CORE_PREFIXES.any { n.startsWith(it) }
    }

    private val N64_CORES = setOf("mupen64plus-next", "mupen64plus", "mupen64plus-next gles3", "mupen64plus-next gles2", "parallel n64")
    /** Families whose every core folder starts the same way ("Beetle PSX HW", "MAME 2003-Plus", "DOSBox-pure"). */
    private val OTHER_CORE_PREFIXES = listOf("beetle ", "mame", "dosbox", "finalburn", "fb alpha", "mednafen ")
    /** RetroArch's folder names (the core's library name) for other systems. Whole names only, so a game's folder never matches. */
    private val OTHER_CORES = setOf(
        "swanstation", "duckstation", "pcsx-rearmed", "pcsx rearmed", "pcsx2", "lrps2", "ppsspp", "ppsspp-ext", "play!",
        "snes9x", "snes9x 2010", "snes9x 2005", "bsnes", "bsnes-hd beta", "bsnes-mercury", "mesen", "mesen-s",
        "fceumm", "nestopia", "quicknes", "genesis plus gx", "genesis plus gx wide", "picodrive", "blastem",
        "gambatte", "sameboy", "gearboy", "tgb dual", "mgba", "gpsp", "vba-m", "vba next", "vbam",
        "melonds", "melonds ds", "desmume", "desmume 2015", "citra", "dolphin", "flycast", "reicast", "redream",
        "stella", "stella 2014", "prosystem", "virtual jaguar", "handy", "atari800", "hatari", "puae", "vice x64",
        "opera", "yabause", "yaba sanshiro", "kronos", "neocd", "geolith", "openbor", "openbor-ext", "scummvm",
        "gooseStation", "smsplus", "sms plus gx", "gearsystem", "pokemini", "o2em", "freeintv", "vecx", "potator",
        "retropad remote", "tic-80", "ecwolf", "prboom", "tyrquake", "nxengine", "bluemsx", "fmsx", "cap32", "fuse",
    ).map { it.lowercase() }.toSet()

    private fun newestByName(files: List<RaFile>): Pair<List<RaFile>, List<RaFile>> {
        val keep = ArrayList<RaFile>()
        val left = ArrayList<RaFile>()
        for ((_, same) in files.groupBy { it.name }) {
            val sorted = same.sortedByDescending { it.modified }
            keep += sorted.first()
            left += sorted.drop(1)
        }
        return keep.sortedBy { it.name } to left
    }

    /**
     * When the folder picked ([folders], its path) is RetroArch's saves or states folder, or one
     * inside it: that kind, since only it can be found there. Null for RetroArch's own folder.
     */
    fun onlyKindOf(folders: List<String>): DataKind? {
        val names = folders.map { it.lowercase() }
        val at = names.indexOfLast { it == "saves" || it == "states" }
        // saves/ itself, or one level in (saves/Mupen64Plus-Next): RetroArch's folders go no deeper.
        if (at < 0 || at < names.size - 3) return null
        return if (names[at] == "saves") DataKind.Saves else DataKind.States
    }

    /** Where a file goes in Joey64's data folder: `saves/<name>` or `states/<name>`. */
    fun target(file: RaFile) = listOf(file.kind.dir, file.name)

    /** Sorts the found files against Joey64's data folder [joey]: new, the same file already there, or a clash. */
    fun plan(ra: FileSpace, found: RaFound, joey: FileSpace): RaImportPlan {
        val space = ra as? ReadOnlySpace ?: ReadOnlySpace(ra)
        val toCopy = ArrayList<RaFile>()
        val already = ArrayList<RaFile>()
        val clashes = ArrayList<RaClash>()
        val listed = HashMap<String, Map<String, SpaceEntry>>()
        for (f in found.saves + found.states) {
            val t = target(f)
            val ours = listed.getOrPut(t[0]) { joey.list(listOf(t[0])).associateBy { it.name } }[t[1]]
            when {
                ours == null -> toCopy += f
                !ours.isDir && ours.size == f.size && FileCopy.sameContent(space, f.path, joey, t) -> already += f
                else -> clashes += RaClash(f, ours)
            }
        }
        return RaImportPlan(toCopy, already, clashes)
    }

    /**
     * Copies [files] into [joey]. [replace]: for clashes the user answered "Use RetroArch's";
     * the old file is swapped out only once the copy reads back whole ([FileCopy.replaceChecked]).
     * Without it a file whose name is taken by then is left out, never overwritten.
     */
    fun copy(
        ra: FileSpace, joey: FileSpace, files: List<RaFile>, replace: Boolean,
        threads: Int = 1,
        /** After each file (and its picture), from whichever thread copied it. */
        onFile: (RaFile) -> Unit = {},
    ): RaImportResult {
        val space = ra as? ReadOnlySpace ?: ReadOnlySpace(ra)
        val copied = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())
        fun one(f: RaFile) {
            if (put(space, joey, f, replace)) {
                copied.incrementAndGet()
                // A state's picture follows its state (a missing one is no failure). The picture already
                // in Joey64 under that name was of another moment: RetroArch's replaces it, or it goes.
                if (f.kind == DataKind.States) {
                    val t = f.thumbnail
                    if (t != null) put(space, joey, t, replace = true)
                    else runCatching { joey.delete(target(f).dropLast(1) + (f.name + ".png")) }
                }
            } else failed += f.path.joinToString("/")
            onFile(f)
        }
        if (threads <= 1 || files.size < 2) files.forEach(::one)
        else {
            // The first file of each kind alone, so its folder (saves/, states/) is made once:
            // in a picked folder two threads making it at once would give "saves (1)".
            val firsts = files.distinctBy { it.kind }
            firsts.forEach(::one)
            val rest = files - firsts.toSet()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
            try {
                rest.map { f -> pool.submit { one(f) } }.forEach { it.get() }
            } finally {
                pool.shutdown()
            }
        }
        return RaImportResult(copied.get(), failed.toList())
    }

    private fun put(ra: FileSpace, joey: FileSpace, f: RaFile, replace: Boolean): Boolean {
        val t = target(f)
        return if (replace) FileCopy.replaceChecked(ra, f.path, joey, t)
        else !joey.exists(t) && FileCopy.copyChecked(ra, f.path, joey, t)
    }

    // States

    private const val RZIP_MAGIC = "#RZIPv\u0001#"
    private const val RZIP_HEADER = 20   // magic, u32 chunk size, u64 total size
    private const val RASTATE_MAGIC = "RASTATE"
    private const val CORE_MAGIC = "M64+SAVE"

    private fun isN64State(space: FileSpace, path: List<String>): Boolean =
        runCatching { space.openRead(path)?.buffered()?.use(::isN64State) ?: false }.getOrDefault(false)

    /**
     * True when [input] is a RetroArch state of the N64 core: RZIP-compressed or not, with the
     * RASTATE container (RetroArch 1.15+) or the core's data alone (older RetroArch).
     */
    fun isN64State(input: InputStream): Boolean {
        val head = ByteArray(8)
        if (input.readNBytesCompat(head) < 8) return false
        val data: InputStream = if (String(head, Charsets.ISO_8859_1) == RZIP_MAGIC) {
            if (input.skipFully(RZIP_HEADER - 8L)) RzipStream(input) else return false
        } else java.io.SequenceInputStream(head.inputStream(), input)

        val magic = ByteArray(8)
        if (data.readNBytesCompat(magic) < 8) return false
        val m = String(magic, Charsets.ISO_8859_1)
        if (m == CORE_MAGIC) return true
        if (!m.startsWith(RASTATE_MAGIC)) return false
        val chunk = ByteArray(8)
        repeat(16) {
            if (data.readNBytesCompat(chunk) < 8) return false
            val tag = String(chunk, 0, 4, Charsets.ISO_8859_1)
            val size = le32(chunk, 4)
            when (tag) {
                "MEM " -> return data.readNBytesCompat(magic) == 8 && String(magic, Charsets.ISO_8859_1) == CORE_MAGIC
                "END " -> return false
                else -> if (!data.skipFully((size + 7) and 7L.inv())) return false
            }
        }
        return false
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun InputStream.skipFully(n: Long): Boolean {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) return false
            left -= r
        }
        return true
    }

    /** RetroArch's RZIP chunks after the header, as one stream: each is a u32 compressed size, then zlib data. */
    private class RzipStream(private val src: InputStream) : InputStream() {
        private var chunk: Bounded? = null
        private var inflater: InputStream? = null

        private fun nextChunk(): Boolean {
            chunk?.drain()
            val size = ByteArray(4)
            if (src.readNBytesCompat(size) < 4) return false
            val n = le32(size, 0)
            if (n == 0L) return false
            chunk = Bounded(src, n).also { inflater = InflaterInputStream(it) }
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val i = inflater ?: if (nextChunk()) inflater!! else return -1
                val r = i.read(b, off, len)
                if (r >= 0) return r
                inflater = null
            }
        }
    }

    /** At most [left] bytes of [src]. */
    private class Bounded(private val src: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else src.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
        fun drain() { src.skipFully(left); left = 0 }
    }
}
