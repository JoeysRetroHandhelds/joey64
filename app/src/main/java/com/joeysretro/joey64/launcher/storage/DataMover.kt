package com.joeysretro.joey64.launcher.storage

/**
 * The data Joey64 keeps, one subfolder each in the data folder (plan: Storage). [hiddenFromGallery]
 * folders get a `.nomedia` file, so covers and texture PNGs stay out of the photo gallery;
 * screenshots stay visible there.
 */
enum class DataKind(val dir: String, val hiddenFromGallery: Boolean = false) {
    Saves("saves"),
    States("states"),
    Screenshots("screenshots"),
    Textures("textures", hiddenFromGallery = true),
    Cheats("cheats", hiddenFromGallery = true),
    BoxArt("boxart", hiddenFromGallery = true),
    /** Save RAM backups (Settings › Saves › Back up saves). */
    Backups("backups", hiddenFromGallery = true),
    /** BIOS files (the 64DD IPLs, BiosImport / BiosSync). */
    Bios("bios", hiddenFromGallery = true),
    ;

    companion object {
        const val NO_MEDIA = ".nomedia"
    }
}

/** What a move did. Nothing is lost either way: a file only leaves the old place once its copy reads back equal. */
data class MoveResult(
    /** Copied over, checked and removed from the old place. */
    val moved: Int,
    /** The same file was already in the new place: only the old copy was removed. */
    val alreadyThere: Int,
    /** A different file with the same name is in the new place: both kept, the old one left where it was. */
    val conflicts: List<String>,
    /** Couldn't be copied (no space, permission gone, ...): left where it was. */
    val failed: List<String>,
    /**
     * Saves of the same name already in the new place, kept in its `backups/` before Joey64's own took their place
     * ([DataMover.settleSaves]); counted in [moved] too.
     */
    val keptAside: Int = 0,
) {
    val complete get() = conflicts.isEmpty() && failed.isEmpty()
}

/**
 * Moves Joey64's data from one place to another (plan: "until a folder is picked, data stays in
 * Joey64's own storage; anything already there moves over when a folder is picked", and again
 * whenever the data folder changes).
 *
 * Each file is copied under a temporary name, read back and compared, then renamed into place,
 * and only then removed from the old place. So a move cut off at any point (app killed, card
 * pulled) leaves every file whole in at least one place, and the next move picks up where this
 * one stopped: a leftover temporary file is replaced, a file already moved is recognised.
 * A name in use in the new place by a different file is never overwritten.
 *
 * Must not run while a game is running: the core writes saves while it plays.
 */
object DataMover {
    const val PART_SUFFIX = FileCopy.PART_SUFFIX

    fun move(from: FileSpace, to: FileSpace, kinds: Collection<DataKind> = DataKind.entries): MoveResult {
        var moved = 0
        var already = 0
        val conflicts = ArrayList<String>()
        val failed = ArrayList<String>()

        fun walk(dir: List<String>) {
            for (e in from.list(dir)) {
                val path = dir + e.name
                val shown = path.joinToString("/")
                when {
                    e.isDir -> {
                        walk(path)
                        if (from.list(path).isEmpty()) from.delete(path)
                    }
                    // A copy this move left half done in the old place is junk.
                    e.name.endsWith(PART_SUFFIX) -> from.delete(path)
                    to.exists(path) -> {
                        if (FileCopy.sameContent(from, path, to, path)) {
                            if (from.delete(path)) already++ else failed += shown
                        } else conflicts += shown
                    }
                    else -> if (FileCopy.copyChecked(from, path, to, path)) {
                        if (from.delete(path)) moved++ else failed += shown
                    } else failed += shown
                }
            }
        }

        for (kind in kinds) {
            val root = listOf(kind.dir)
            walk(root)
            if (from.list(root).isEmpty()) from.delete(root)
        }
        return MoveResult(moved, already, conflicts, failed)
    }

    /**
     * After [move]: a save in `saves/` that the new place already had under the same name, with other content (code
     * audit 3, H1). Left as a conflict, Joey64's copy stayed in the old place, which for Joey64's own storage is the
     * core's working copy, and the game's next start pulled the folder's copy over it (the data folder's switch forgets
     * what was in step), so everything played since was lost. Now the new place's copy is kept in its `backups/`
     * ([com.joeysretro.joey64.saves.SaveBackups.keepAside]: Restore a backup lists it), Joey64's goes in its place, checked,
     * and leaves the old place. Joey64's copy is the one in use: the old place is the data folder it plays from (its
     * working copies were pushed there first).
     *
     * One that can't be settled stays a conflict. When [from] is Joey64's own storage ([fromIsWorkingCopy]), its copy
     * then goes to the old place's `backups/` instead, out of the working copies the next start would overwrite.
     */
    fun settleSaves(from: FileSpace, to: FileSpace, res: MoveResult, fromIsWorkingCopy: Boolean, now: Long = System.currentTimeMillis()): MoveResult {
        var kept = 0
        val left = ArrayList<String>()
        for (shown in res.conflicts) {
            val path = shown.split("/")
            val save = path.size == 2 && path[0] == DataKind.Saves.dir && com.joeysretro.joey64.saves.SaveBackups.isSaveFile(path[1])
            if (save && com.joeysretro.joey64.saves.SaveBackups.keepAside(to, path[1], now) && FileCopy.replaceChecked(from, path, to, path)) {
                kept++
                from.delete(path)
                continue
            }
            left += shown
            if (save && fromIsWorkingCopy && com.joeysretro.joey64.saves.SaveBackups.keepAside(from, path[1], now)) from.delete(path)
        }
        if (kept == 0) return res
        val saves = listOf(DataKind.Saves.dir)
        if (from.list(saves).isEmpty()) from.delete(saves)
        return res.copy(moved = res.moved + kept, conflicts = left, keptAside = res.keptAside + kept)
    }
}
