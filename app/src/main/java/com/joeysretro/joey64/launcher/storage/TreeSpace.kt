package com.joeysretro.joey64.launcher.storage

import com.joeysretro.joey64.JLog
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.InputStream
import java.io.OutputStream

/**
 * A folder the user picked, through the Storage Access Framework. [treeUri] is the tree URI the
 * picker returned (its permission taken with takePersistableUriPermission), [rootDocId] the
 * document the space starts at: the picked folder itself, or a folder inside it (the `n64`
 * folder in a picked `ROMs`).
 *
 * Listing uses one query per folder with only the columns we read
 * (buildChildDocumentsUriUsingTree + a projection), not DocumentFile.listFiles(), which makes a
 * query per child for each property. Paths resolve folder by folder, and each folder's listing
 * is cached until something in it changes here (create, write, rename, delete), so opening many
 * files in one folder costs one listing: without it every open listed its folder again, and
 * Import from RetroArch spent ~25 s listing 30 folders over a thousand times on the Thor.
 * Safe to read from several threads.
 */
class TreeSpace(
    private val resolver: ContentResolver,
    val treeUri: Uri,
    val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri),
) : FileSpace {

    private data class Child(val docId: String, val entry: SpaceEntry)

    /** Folder path → its document id. Cleared on anything that changes the tree's shape. */
    private val dirIds = HashMap<List<String>, String>().apply { put(emptyList(), rootDocId) }
    /** Folder document id → its listing. */
    private val listings = HashMap<String, List<Child>>()
    private val lock = Any()

    fun documentUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun children(docId: String): List<Child> = childrenOrNull(docId) ?: emptyList()

    /**
     * The folder's listing, or null when it couldn't be read. A failed listing isn't kept: kept as empty, the next
     * write took the file for missing and created "name (1)" beside it, which then got the save (code audit 2026-11-07).
     */
    private fun childrenOrNull(docId: String): List<Child>? {
        val seen = synchronized(lock) { listings[docId]?.let { return it }; generation(docId) }
        val out = query(docId) ?: return null
        // Kept only when nothing in the folder changed while it was read. A listing begun before another thread
        // created its file, and finished after, left that file out of the cache for good: its rename found nothing
        // and its clean-up delete took it for gone, so the copy stayed a .joey64-part (Thor 2026-11-13, Import from
        // RetroArch on 4 threads into an SD card folder: 41 of 222 files).
        synchronized(lock) { if (generation(docId) == seen) listings[docId] = out }
        return out
    }

    /** Folder document id → how many times something in it changed here; [forgetDirs] bumps [epoch] for all. */
    private val generations = HashMap<String, Long>()
    private var epoch = 0L
    private fun generation(docId: String) = (generations[docId] ?: 0L) + (epoch shl 32)

    /** Drops the cached listing of the folder [docId], after something in it changed. */
    private fun changed(docId: String) = synchronized(lock) {
        listings.remove(docId)
        generations[docId] = (generations[docId] ?: 0L) + 1
    }

    private fun query(docId: String): List<Child>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = ArrayList<Child>()
        try {
            val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: run { JLog.w(TAG, "listing $docId: no answer"); return null }
            cursor.use { c ->
                while (c.moveToNext()) {
                    val isDir = c.getString(2) == Document.MIME_TYPE_DIR
                    out += Child(
                        docId = c.getString(0),
                        entry = SpaceEntry(
                            name = c.getString(1) ?: continue,
                            isDir = isDir,
                            size = if (c.isNull(3)) 0 else c.getLong(3),
                            modified = if (c.isNull(4)) 0 else c.getLong(4),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            // A folder deleted under us, or the permission was revoked in system settings.
            JLog.w(TAG, "listing $docId failed: $e")
            return null
        }
        return out
    }

    /**
     * Forgets the cached ids of [dir] and every folder inside it, after it was deleted or
     * renamed. Never the root: clearing the whole cache once dropped the root's own entry, and
     * the next lookup recursed until the stack overflowed (found on the Thor, moving data back).
     */
    private fun forgetDirs(dir: List<String>) {
        if (dir.isEmpty()) return
        synchronized(lock) {
            dirIds.keys.removeAll { it.size >= dir.size && it.subList(0, dir.size) == dir }
            // Folder ids below it may be gone too; listings are cheap to read again.
            listings.clear()
            epoch++
        }
    }

    private fun dirId(dir: List<String>, create: Boolean): String? {
        if (dir.isEmpty()) return rootDocId
        synchronized(lock) { dirIds[dir] }?.let { return it }
        val parent = dirId(dir.dropLast(1), create) ?: return null
        val name = dir.last()
        val listing = childrenOrNull(parent) ?: return null
        val found = listing.firstOrNull { it.entry.isDir && it.entry.name == name }?.docId
            ?: if (create) createDocument(parent, Document.MIME_TYPE_DIR, name) else null
        if (found != null) synchronized(lock) { dirIds[dir] = found }
        return found
    }

    /** The document at [path], or null when there's none (a screenshot to share). */
    fun uriOf(path: List<String>): Uri? = childId(path)?.let { documentUri(it.docId) }

    private fun childId(path: List<String>): Child? {
        if (path.isEmpty()) return null
        val parent = dirId(path.dropLast(1), create = false) ?: return null
        return children(parent).firstOrNull { it.entry.name == path.last() }
    }

    /** [childId], with the folder read again once when it isn't there: for a rename or delete, which must not miss it. */
    private fun childIdFresh(path: List<String>): Child? {
        childId(path)?.let { return it }
        val parent = dirId(path.dropLast(1), create = false) ?: return null
        changed(parent)
        return children(parent).firstOrNull { it.entry.name == path.last() }
    }

    private fun createDocument(parentId: String, mime: String, name: String): String? = try {
        DocumentsContract.createDocument(resolver, documentUri(parentId), mime, name)
            ?.let { DocumentsContract.getDocumentId(it) }
    } catch (e: Exception) {
        JLog.w(TAG, "creating $name failed: $e")
        null
    } finally {
        changed(parentId)
    }

    /**
     * Finds the file, or creates it. Created as application/octet-stream, which the external
     * storage provider keeps as named (a typed MIME would let it change the extension); PNGs as
     * image/png. Its name is checked first, since createDocument adds " (1)" to a name in use.
     */
    private fun fileIdForWrite(path: List<String>): String? {
        val parent = dirId(path.dropLast(1), create = true) ?: return null
        // The write changes the file's size and date in the folder's listing.
        changed(parent)
        val name = path.last()
        (childrenOrNull(parent) ?: return null).firstOrNull { it.entry.name == name }?.let { return if (it.entry.isDir) null else it.docId }
        val mime = if (name.endsWith(".png", ignoreCase = true)) "image/png" else "application/octet-stream"
        return createDocument(parent, mime, name)
    }

    /**
     * Whether the picked folder itself can be read now. Android keeps a folder's permission when its
     * SD card is taken out, and a listing that fails reads as empty, so without this a scan with the
     * card out took every game on it for gone.
     */
    fun readable(): Boolean = try {
        resolver.query(documentUri(rootDocId), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        JLog.w(TAG, "can't read the picked folder $rootDocId: $e")
        false
    }

    override fun list(dir: List<String>): List<SpaceEntry> =
        dirId(dir, create = false)?.let { id -> children(id).map { it.entry } } ?: emptyList()

    override fun exists(path: List<String>) = path.isEmpty() || childId(path) != null

    override fun openRead(path: List<String>): InputStream? {
        val child = childId(path)?.takeIf { !it.entry.isDir } ?: return null
        return resolver.openInputStream(documentUri(child.docId))
    }

    override fun openWrite(path: List<String>): OutputStream {
        val id = fileIdForWrite(path) ?: throw java.io.IOException("can't create ${path.joinToString("/")}")
        // "wt": plain "w" doesn't truncate on some providers (Android 10), which left old bytes
        // at the end of a shorter file.
        return resolver.openOutputStream(documentUri(id), "wt") ?: throw java.io.IOException("can't open ${path.joinToString("/")}")
    }

    override fun rename(path: List<String>, newName: String): Boolean {
        val child = childIdFresh(path) ?: return false
        val parent = dirId(path.dropLast(1), create = false)
        if (exists(path.dropLast(1) + newName)) return false
        return try {
            DocumentsContract.renameDocument(resolver, documentUri(child.docId), newName) != null
        } catch (e: Exception) {
            JLog.w(TAG, "renaming ${child.entry.name} failed: $e")
            false
        } finally {
            parent?.let(::changed)
            if (child.entry.isDir) forgetDirs(path)
        }
    }

    override fun delete(path: List<String>): Boolean {
        val child = childIdFresh(path) ?: return true
        val parent = dirId(path.dropLast(1), create = false)
        return try {
            DocumentsContract.deleteDocument(resolver, documentUri(child.docId))
        } catch (e: Exception) {
            JLog.w(TAG, "deleting ${child.entry.name} failed: $e")
            false
        } finally {
            parent?.let(::changed)
            if (child.entry.isDir) forgetDirs(path)
        }
    }

    override fun openFd(path: List<String>, mode: String): ParcelFileDescriptor? {
        val id = if (mode == "r") childId(path)?.takeIf { !it.entry.isDir }?.docId else fileIdForWrite(path)
        id ?: return null
        // "w" through SAF is truncating only as "wt"; "rw" keeps the contents.
        val safMode = if (mode == "w") "wt" else mode
        return resolver.openFileDescriptor(documentUri(id), safMode)
    }

    /** Every file below [dir] (folders walked to [maxDepth]), with its document id, for the ROM scan. */
    fun walk(dir: List<String> = emptyList(), maxDepth: Int = 4): List<Pair<List<String>, Pair<String, SpaceEntry>>> {
        val out = ArrayList<Pair<List<String>, Pair<String, SpaceEntry>>>()
        fun go(id: String, at: List<String>, depth: Int) {
            for (c in children(id)) {
                if (c.entry.isDir) { if (depth < maxDepth) go(c.docId, at + c.entry.name, depth + 1) }
                else out += (at + c.entry.name) to (c.docId to c.entry)
            }
        }
        dirId(dir, create = false)?.let { go(it, dir, 1) }
        return out
    }

    private companion object {
        const val TAG = "joey64storage"
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
