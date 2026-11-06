package com.joeysretro.joey64.launcher.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** A ROM found in a picked folder. [uri] is its document URI, opened through the folder's permission. */
data class RomFile(val uri: Uri, val treeUri: Uri, val path: List<String>, val size: Long, val modified: Long) {
    val name get() = path.last()
}

/**
 * The rules for ROM folders, kept apart from Android so they are unit tested (RomFolderRulesTest).
 */
object RomFolderRules {
    /** Plan: inside a picked folder Joey64 looks for `n64`, `N64` or `Nintendo 64` by itself. */
    private val N64_NAMES = setOf("n64", "nintendo 64")

    /** ROMs and the archives the app unpacks to the cache (plan: Speed). */
    private val ROM_EXTENSIONS = setOf("z64", "n64", "v64", "zip", "7z")

    fun isN64FolderName(name: String) = name.trim().lowercase() in N64_NAMES

    fun isRomName(name: String): Boolean {
        if (name.startsWith(".")) return false   // macOS "._" resource forks, hidden files
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in ROM_EXTENSIONS
    }

    /**
     * Which folders to scan in a picked folder, as names of its subfolders; an empty name means
     * the picked folder itself. Picked the N64 folder itself: that one. Picked a parent (an ES-DE
     * `ROMs`): every N64-named folder in it (an `n64` and a `Nintendo 64` side by side both count).
     * Neither: the picked folder itself, since the user chose it for N64 games.
     */
    fun foldersToScan(pickedName: String, childFolders: List<String>): List<String> {
        if (isN64FolderName(pickedName)) return listOf("")
        val n64 = childFolders.filter(::isN64FolderName)
        return n64.ifEmpty { listOf("") }
    }
}

/**
 * The ROM folders the user picked. Each is a tree URI with a persisted permission, so access
 * survives restarts and reboots without asking again (plan: "the user picks a folder once, and
 * Joey64 keeps permanent access to it"). Which URIs are ROM folders is kept in settings
 * ([com.joeysretro.joey64.launcher.settings.SettingsStore]); the permissions live with Android.
 */
object RomFolders {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /**
     * Where the picker opens: `ROMs` on internal storage, so in an ES-DE layout it's one tap
     * (plan). Given as EXTRA_INITIAL_URI (ActivityResultContracts.OpenDocumentTree's input);
     * when the folder doesn't exist the picker opens where it would anyway.
     */
    fun initialPickerUri(): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:ROMs")

    /** Keeps the picker's grant across restarts. Call with the URI the picker returned. */
    fun keep(context: Context, treeUri: Uri, write: Boolean) {
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (write) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
    }

    /** Gives a folder's grant back when the user removes it (Android caps persisted grants per app). */
    fun release(context: Context, treeUri: Uri) {
        val held = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == treeUri } ?: return
        var flags = 0
        if (held.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (held.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.releasePersistableUriPermission(treeUri, flags)
    }

    /** False once the user took the permission away (system settings) or the folder's card is out. */
    fun stillReadable(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    /** Every ROM in a picked folder (its N64 folders found by [RomFolderRules.foldersToScan]). */
    fun scan(resolver: ContentResolver, treeUri: Uri): List<RomFile> {
        val tree = TreeSpace(resolver, treeUri)
        val pickedName = DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':').substringAfterLast('/')
        val dirs = tree.list(emptyList()).filter { it.isDir }.map { it.name }
        return RomFolderRules.foldersToScan(pickedName, dirs).flatMap { sub ->
            val start = if (sub.isEmpty()) emptyList() else listOf(sub)
            tree.walk(start).mapNotNull { (path, doc) ->
                val (docId, entry) = doc
                if (!RomFolderRules.isRomName(entry.name)) null
                else RomFile(tree.documentUri(docId), treeUri, path, entry.size, entry.modified)
            }
        }
    }
}
