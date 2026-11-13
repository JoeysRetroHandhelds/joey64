package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FileCopy
import com.joeysretro.joey64.launcher.storage.FileSpace
import com.joeysretro.joey64.launcher.storage.ReadOnlySpace
import com.joeysretro.joey64.launcher.storage.SpaceEntry
import com.joeysretro.joey64.states.StateFormat
import com.joeysretro.joey64.states.StateSlots
import java.io.ByteArrayInputStream

/** An FZ save file of one part ([FzSaves.Part]) in a game's `SramData`. */
data class FzPart(val part: FzSaves.Part, val path: List<String>, val size: Long, val modified: Long)

/** An FZ state: [slot] is the Joey64 slot it goes to (FZ's `.st0` is Slot 1 ... `.st9` Slot 10; its auto save the Auto save state). */
data class FzState(val slot: Int, val path: List<String>, val size: Long, val modified: Long)

/** One game folder in FZ's GameData, `<header name> <country> <ROM MD5>`. */
data class FzGame(val md5: String, val folder: String, val parts: List<FzPart>, val states: List<FzState>) {
    val saveModified get() = parts.maxOfOrNull { it.modified } ?: 0L
}

/** [root]: the folder the game folders were in, inside the picked one (`GameData`, or the picked folder itself). */
data class FzFound(val root: List<String>, val games: List<FzGame>)

/** What one FZ game gives Joey64: its save ([slot] null) or a state. [saveName]: the game's save name in this library. */
data class FzItem(val game: FzGame, val saveName: String, val kind: DataKind, val slot: Int?) {
    val name get() = if (kind == DataKind.Saves) "$saveName.srm" else StateSlots.fileName(saveName, slot!!)
    val target get() = listOf(kind.dir, name)
    val modified get() = if (kind == DataKind.Saves) game.saveModified else game.states.first { it.slot == slot }.modified
}

data class FzClash(val item: FzItem, val ours: SpaceEntry)

/** [notInLibrary]: FZ games whose MD5 no library game has, named on the Found page and left out. */
data class FzPlan(val toCopy: List<FzItem>, val alreadyThere: List<FzItem>, val clashes: List<FzClash>, val notInLibrary: List<FzGame>) {
    val all get() = toCopy + alreadyThere + clashes.map { it.item }
    val saves get() = all.count { it.kind == DataKind.Saves }
    val states get() = all.count { it.kind == DataKind.States }
}

/**
 * Import from M64Plus FZ (approved mockup 2026-11-13, scratchpad build_fzimport.py / fzimport_all.png). Reads FZ's game
 * data folder: the one set in FZ's Settings › Data › External game data location, or a copy made with Tools › Import/
 * Export Data › Export Game Data (FZ's CopyToSdService copies its GameData folder, as a folder, into the one picked).
 * Either the GameData folder or the one holding it can be picked. Games are matched to the library by the MD5 in each
 * game folder's name; their saves are converted ([FzSaves.toSrm]) and their states unzipped ([FzSaves.stateForCore])
 * and written in RetroArch's state format. Copies only: FZ's folder is read through [ReadOnlySpace].
 */
object FzImport {
    const val GAME_DATA = "GameData"
    private val SLOT = Regex("""\.st(\d)$""", RegexOption.IGNORE_CASE)
    private const val AUTO = ".v2.sav"
    private const val COMPLETE = ".complete"

    fun find(fz: FileSpace): FzFound {
        val space = fz as? ReadOnlySpace ?: ReadOnlySpace(fz)
        val top = space.list(emptyList())
        val root = top.firstOrNull { it.isDir && it.name.equals(GAME_DATA, ignoreCase = true) }?.let { listOf(it.name) } ?: emptyList()
        val dirs = (if (root.isEmpty()) top else space.list(root)).filter { it.isDir }
        val games = dirs.mapNotNull { d ->
            val md5 = FzSaves.romMd5(d.name) ?: return@mapNotNull null
            val at = root + d.name
            val subs = space.list(at).filter { it.isDir }.associateBy { it.name.lowercase() }
            fun files(sub: String) = subs[sub.lowercase()]?.let { s -> space.list(at + s.name).filter { !it.isDir }.map { (at + s.name) to it } } ?: emptyList()
            // Two files of one part (FZ names them after the ROM's name in its database, which can change): the newest.
            val parts = files("SramData").mapNotNull { (dir, e) -> FzSaves.Part.of(e.name)?.takeIf { e.size > 0 }?.let { FzPart(it, dir + e.name, e.size, e.modified) } }
                .groupBy { it.part }.map { (_, l) -> l.maxBy { it.modified } }.sortedBy { it.part.at }
            val slots = files("SlotSaves").mapNotNull { (dir, e) ->
                SLOT.find(e.name)?.let { FzState(it.groupValues[1].toInt() + 1, dir + e.name, e.size, e.modified) }
            }.groupBy { it.slot }.map { (_, l) -> l.maxBy { it.modified } }
            // FZ marks a finished auto save with a .complete file beside it; the newest finished one.
            val autoFiles = files("AutoSaves")
            val names = autoFiles.map { it.second.name }.toSet()
            val auto = autoFiles.filter { (_, e) -> e.name.endsWith(AUTO, ignoreCase = true) && e.name + COMPLETE in names }
                .maxByOrNull { (_, e) -> e.name }?.let { (dir, e) -> FzState(StateSlots.AUTO, dir + e.name, e.size, e.modified) }
            val states = (slots + listOfNotNull(auto)).sortedBy { it.slot }
            if (parts.isEmpty() && states.isEmpty()) null else FzGame(md5, d.name, parts, states)
        }.sortedBy { it.folder.lowercase() }
        return FzFound(root, games)
    }

    /** Sorts what [found] has against the library ([saveNames]: ROM MD5 upper case to save name) and Joey64's data folder [joey]. */
    fun plan(fz: FileSpace, found: FzFound, saveNames: Map<String, String>, joey: FileSpace): FzPlan {
        val space = fz as? ReadOnlySpace ?: ReadOnlySpace(fz)
        val toCopy = ArrayList<FzItem>()
        val already = ArrayList<FzItem>()
        val clashes = ArrayList<FzClash>()
        val notIn = ArrayList<FzGame>()
        val there = DataKind.entries.filter { it == DataKind.Saves || it == DataKind.States }
            .associateWith { k -> runCatching { joey.list(listOf(k.dir)) }.getOrDefault(emptyList()).associateBy { it.name } }
        for (g in found.games) {
            val saveName = saveNames[g.md5] ?: run { notIn += g; null } ?: continue
            val items = (if (g.parts.isEmpty()) emptyList() else listOf(FzItem(g, saveName, DataKind.Saves, null))) +
                g.states.map { FzItem(g, saveName, DataKind.States, it.slot) }
            for (item in items) {
                val ours = there.getValue(item.kind)[item.name]
                when {
                    ours == null -> toCopy += item
                    same(space, joey, item) -> already += item
                    else -> clashes += FzClash(item, ours)
                }
            }
        }
        return FzPlan(toCopy, already, clashes, notIn)
    }

    /** Whether Joey64's file for [item] already holds what FZ's would give (an earlier import). */
    private fun same(fz: FileSpace, joey: FileSpace, item: FzItem): Boolean = runCatching {
        val ours = joey.openRead(item.target)?.use { it.readBytes() } ?: return false
        if (item.kind == DataKind.Saves) bytesFor(fz, item, ours).contentEquals(ours)
        else StateFormat.unwrap(ours)?.contentEquals(stateFor(fz, item)) == true
    }.getOrDefault(false)

    /** FZ's parts on top of [ours] (left as it is outside them) when it's a whole save, else on a blank one. */
    fun bytesFor(fz: FileSpace, item: FzItem, ours: ByteArray?): ByteArray {
        val parts = item.game.parts.associate { p -> p.part to (fz.openRead(p.path)?.use { it.readBytes() } ?: error("can't read ${p.path.last()}")) }
        return FzSaves.toSrm(parts, ours?.takeIf { it.size == FzSaves.SRM_SIZE })
    }

    private fun stateFor(fz: FileSpace, item: FzItem): ByteArray? {
        val s = item.game.states.first { it.slot == item.slot }
        return fz.openRead(s.path)?.use { FzSaves.stateForCore(it.buffered()) }
    }

    /**
     * Writes [item] into [joey], checked as RetroArch's import writes (a cut-off write leaves the old file whole). [replace]:
     * the user picked FZ's over the file Joey64 has; without it an existing file is left alone. False when FZ's file
     * couldn't be read or isn't one Joey64 can use (a state from a newer FZ, say).
     */
    fun write(fz: FileSpace, joey: FileSpace, item: FzItem, replace: Boolean): Boolean = runCatching {
        val space = fz as? ReadOnlySpace ?: ReadOnlySpace(fz)
        val exists = joey.exists(item.target)
        if (exists && !replace) return false
        val bytes = if (item.kind == DataKind.Saves) {
            val ours = if (exists) joey.openRead(item.target)?.use { it.readBytes() } else null
            bytesFor(space, item, ours)
        } else StateFormat.wrap(stateFor(space, item) ?: return false)
        val ok = FileCopy.writeChecked(ByteArrayInputStream(bytes), joey, item.target)
        if (ok) {
            joey.setModified(item.target, item.modified)
            // A state Joey64 had here had its own picture; it isn't of this moment.
            if (item.kind == DataKind.States) runCatching { joey.delete(listOf(item.kind.dir, item.name + ".png")) }
        }
        ok
    }.getOrDefault(false)
}
