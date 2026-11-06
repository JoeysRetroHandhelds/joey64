package com.joeysretro.joey64.launcher.storage

import java.io.InputStream
import java.io.OutputStream

/**
 * Copies between [FileSpace]s that never leave a broken file behind (DataMover, RetroArch
 * import). The copy is written under a temporary name, read back and compared with the source,
 * and only then renamed into place. Cut off at any point (app killed, card pulled), the target
 * has either its old file or the new one, whole, plus at most a temporary file the next run
 * clears.
 */
object FileCopy {
    const val PART_SUFFIX = ".joey64-part"
    const val OLD_SUFFIX = ".joey64-old"

    /** Copies [fromPath] to [toPath], which must not exist yet. False (and nothing left behind) when it couldn't. */
    fun copyChecked(from: FileSpace, fromPath: List<String>, to: FileSpace, toPath: List<String>): Boolean {
        val part = writePart(from, fromPath, to, toPath) ?: return false
        return to.rename(part, toPath.last()) || run { to.delete(part); false }
    }

    /**
     * Puts a checked copy of [fromPath] in place of the file at [toPath]. The old file is
     * renamed aside first and removed only once the new one is in place; if that fails it's put
     * back. Used only after the user chose to replace that file.
     */
    fun replaceChecked(from: FileSpace, fromPath: List<String>, to: FileSpace, toPath: List<String>): Boolean {
        if (!to.exists(toPath)) return copyChecked(from, fromPath, to, toPath)
        val part = writePart(from, fromPath, to, toPath) ?: return false
        val old = sibling(toPath, OLD_SUFFIX)
        to.delete(old)
        if (!to.rename(toPath, old.last())) { to.delete(part); return false }
        if (!to.rename(part, toPath.last())) {
            to.rename(old, toPath.last())
            to.delete(part)
            return false
        }
        to.delete(old)
        return true
    }

    private fun writePart(from: FileSpace, fromPath: List<String>, to: FileSpace, toPath: List<String>): List<String>? {
        val part = sibling(toPath, PART_SUFFIX)
        return try {
            to.delete(part)
            val input = from.openRead(fromPath) ?: return null
            input.use { i -> to.openWrite(part).use { o -> i.copyTo(o) } }
            if (!sameContent(from, fromPath, to, part)) {
                to.delete(part)
                return null
            }
            part
        } catch (e: Exception) {
            runCatching { to.delete(part) }
            null
        }
    }

    private fun sibling(path: List<String>, suffix: String) = path.dropLast(1) + (path.last() + suffix)

    fun sameContent(a: FileSpace, pathA: List<String>, b: FileSpace, pathB: List<String>): Boolean {
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
    fun InputStream.readNBytesCompat(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int {
        var n = 0
        while (n < len) {
            val r = read(buf, off + n, len - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}

/**
 * Another app's folder (RetroArch's), seen read-only: Joey64 reads and copies from it and the
 * type makes any write, rename or delete fail, so a mistake can't touch the other app's files.
 */
class ReadOnlySpace(private val inner: FileSpace) : FileSpace by inner {
    override fun openWrite(path: List<String>): OutputStream = throw UnsupportedOperationException("read-only: ${path.joinToString("/")}")
    override fun rename(path: List<String>, newName: String): Boolean = throw UnsupportedOperationException("read-only: ${path.joinToString("/")}")
    override fun delete(path: List<String>): Boolean = throw UnsupportedOperationException("read-only: ${path.joinToString("/")}")
    override fun openFd(path: List<String>, mode: String) =
        if (mode == "r") inner.openFd(path, mode) else throw UnsupportedOperationException("read-only: ${path.joinToString("/")}")
}
