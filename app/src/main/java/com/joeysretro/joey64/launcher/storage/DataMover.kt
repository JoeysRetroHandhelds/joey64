package com.joeysretro.joey64.launcher.storage

import java.io.InputStream

/** The data Joey64 keeps, one subfolder each (plan: default subfolders saves/, states/, screenshots/). */
enum class DataKind(val dir: String) {
    Saves("saves"),
    States("states"),
    Screenshots("screenshots"),
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
    const val PART_SUFFIX = ".joey64-part"

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
                        if (sameContent(from, to, path, path)) {
                            if (from.delete(path)) already++ else failed += shown
                        } else conflicts += shown
                    }
                    else -> if (copyChecked(from, to, path)) {
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

    private fun copyChecked(from: FileSpace, to: FileSpace, path: List<String>): Boolean {
        val part = path.dropLast(1) + (path.last() + PART_SUFFIX)
        return try {
            to.delete(part)
            val input = from.openRead(path) ?: return false
            input.use { i -> to.openWrite(part).use { o -> i.copyTo(o) } }
            if (!sameContent(from, to, path, part)) {
                to.delete(part)
                return false
            }
            to.rename(part, path.last()) || run { to.delete(part); false }
        } catch (e: Exception) {
            runCatching { to.delete(part) }
            false
        }
    }

    private fun sameContent(a: FileSpace, b: FileSpace, pathA: List<String>, pathB: List<String>): Boolean {
        val ia = a.openRead(pathA) ?: return false
        val ib = b.openRead(pathB) ?: run { ia.close(); return false }
        ia.use { x -> ib.use { y -> return streamsEqual(x, y) } }
    }

    private fun streamsEqual(a: InputStream, b: InputStream): Boolean {
        val ba = ByteArray(64 * 1024)
        val bb = ByteArray(64 * 1024)
        while (true) {
            val na = a.readNBytesCompat(ba)
            val nb = b.readNBytesCompat(bb)
            if (na != nb) return false
            if (na == 0) return true
            for (i in 0 until na) if (ba[i] != bb[i]) return false
        }
    }

    /** Fills [buf] unless the stream ends first (InputStream.readNBytes is API 33). */
    private fun InputStream.readNBytesCompat(buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}
