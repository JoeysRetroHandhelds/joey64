package com.joeysretro.joey64.launcher.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri

/**
 * Where saves, states and screenshots live: Joey64's own storage until the user picks a folder
 * ("Keep them in Joey64"), then that folder. Same subfolders either way, with RetroArch's file
 * names. Joey64's own storage is the app's files dir, so the saves the emulator already writes to
 * `files/saves/` are the data that moves when a folder is picked.
 *
 * Note for the emulator side: the core opens its saves by path today (`files/saves`). Once data
 * can live in a picked folder, it takes them as descriptors from [openFd] instead; until then
 * nothing calls [moveTo], so saves stay where the core looks for them.
 */
class DataFolder private constructor(val space: FileSpace, val treeUri: Uri?) {

    val isInternal get() = treeUri == null

    fun list(kind: DataKind) = space.list(listOf(kind.dir)).filter { !it.isDir && it.name != DataKind.NO_MEDIA }

    /** Puts the `.nomedia` file in every folder hidden from the gallery; called before writing into one, and after a move. */
    fun hideFromGallery() = DataFolderRules.hideFromGallery(space)

    /** A descriptor for native code; see [FileSpace.openFd]. */
    fun openFd(kind: DataKind, name: String, mode: String): ParcelFileDescriptor? =
        space.openFd(listOf(kind.dir, name), mode)

    /**
     * False when [target] is this folder, or one is inside the other (a move would walk into its
     * own copies). Joey64's own storage can't overlap a picked folder: the picker can't reach it.
     */
    fun canMoveTo(target: DataFolder): Boolean {
        val a = treeUri ?: return target.treeUri != null
        val b = target.treeUri ?: return true
        return !TreeRules.overlaps(a.authority, DocumentsContract.getTreeDocumentId(a), b.authority, DocumentsContract.getTreeDocumentId(b))
    }

    /** Moves everything here to [target]; the caller switches to [target] when the result is complete (or the user accepts what's left). */
    fun moveTo(target: DataFolder): MoveResult {
        require(canMoveTo(target)) { "data folders overlap" }
        return DataMover.move(space, target.space).also { target.hideFromGallery() }
    }

    companion object {
        fun internal(context: Context) = DataFolder(DirSpace(context.filesDir), null)

        fun picked(context: Context, treeUri: Uri) = DataFolder(TreeSpace(context.contentResolver, treeUri), treeUri)

        /** From the saved setting: a tree URI string, or null for Joey64's own storage. */
        fun of(context: Context, treeUri: String?) =
            if (treeUri == null) internal(context) else picked(context, treeUri.toUri())
    }
}

/** Rules about picked folders, apart from Android so they're unit tested (TreeRulesTest). */
object TreeRules {
    /**
     * Whether two picked folders are the same or one holds the other. External storage document
     * ids are "volume:path" ("primary:Joey64/saves"), so that's a path prefix check; other
     * providers' ids say nothing about nesting, so only the same id counts there.
     */
    fun overlaps(authorityA: String?, docIdA: String, authorityB: String?, docIdB: String): Boolean {
        if (authorityA != authorityB) return false
        if (docIdA == docIdB) return true
        if (authorityA != "com.android.externalstorage.documents") return false
        fun inside(child: String, parent: String): Boolean {
            val p = parent.trimEnd('/')
            return if (p.endsWith(':')) child.startsWith(p) else child.startsWith("$p/")
        }
        return inside(docIdA, docIdB) || inside(docIdB, docIdA)
    }
}

/** Data folder rules that only need a [FileSpace], so they're unit tested (DataMoverTest). */
object DataFolderRules {
    fun hideFromGallery(space: FileSpace) {
        for (kind in DataKind.entries.filter { it.hiddenFromGallery }) {
            val path = listOf(kind.dir, DataKind.NO_MEDIA)
            if (!space.exists(path)) runCatching { space.openWrite(path).close() }
        }
    }
}
