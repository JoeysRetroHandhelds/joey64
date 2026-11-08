package com.joeysretro.joey64.launcher.storage

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A file or folder in a [FileSpace]. */
data class SpaceEntry(val name: String, val isDir: Boolean, val size: Long, val modified: Long)

/**
 * One place Joey64 keeps files, addressed by path segments from its root ("saves", "Mario.srm").
 * Two kinds: Joey64's own storage ([DirSpace]) until the user picks a folder, and a picked folder
 * through the Storage Access Framework ([TreeSpace]). Everything that reads, writes or moves data
 * goes through this, so the same code (and the same tests) covers both.
 */
interface FileSpace {
    /** The entries directly inside [dir], or an empty list when it doesn't exist. */
    fun list(dir: List<String>): List<SpaceEntry>
    fun exists(path: List<String>): Boolean
    fun openRead(path: List<String>): InputStream?
    /** Creates missing parent folders; replaces the file's contents. */
    fun openWrite(path: List<String>): OutputStream
    /** Renames a file in place (same folder). False if it couldn't. */
    fun rename(path: List<String>, newName: String): Boolean
    /** Deletes a file, or a folder when it's empty. True when it's gone. */
    fun delete(path: List<String>): Boolean

    /** Sets a file's modified time (Undo save state puts a slot's date back). False where it can't. */
    fun setModified(path: List<String>, time: Long): Boolean = false

    /**
     * A file descriptor for native code, as PPSSPP does on Play: Kotlin opens the file through the
     * folder the user picked, and the core reads and writes the descriptor directly. [mode] is
     * "r", "w" (truncate), or "rw". Missing parent folders are created for writes. The caller
     * either closes it, or calls detachFd() and hands the raw fd (and its ownership) to native code.
     */
    fun openFd(path: List<String>, mode: String): ParcelFileDescriptor?
}

/** Joey64's own storage: a plain folder (the app's files dir). */
class DirSpace(val root: File) : FileSpace {
    private fun file(path: List<String>): File = path.fold(root) { f, seg -> File(f, seg) }

    override fun list(dir: List<String>): List<SpaceEntry> =
        file(dir).listFiles()?.map { SpaceEntry(it.name, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
            ?: emptyList()

    override fun exists(path: List<String>) = file(path).exists()

    override fun openRead(path: List<String>): InputStream? = file(path).takeIf { it.isFile }?.inputStream()

    override fun openWrite(path: List<String>): OutputStream {
        val f = file(path)
        f.parentFile?.mkdirs()
        return f.outputStream()
    }

    override fun rename(path: List<String>, newName: String): Boolean {
        val f = file(path)
        val to = File(f.parentFile, newName)
        if (to.exists()) return false
        return f.renameTo(to)
    }

    override fun delete(path: List<String>): Boolean {
        val f = file(path)
        return !f.exists() || f.delete()
    }

    override fun setModified(path: List<String>, time: Long): Boolean = file(path).setLastModified(time)

    override fun openFd(path: List<String>, mode: String): ParcelFileDescriptor? {
        val f = file(path)
        if (mode != "r") f.parentFile?.mkdirs() else if (!f.isFile) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
}
