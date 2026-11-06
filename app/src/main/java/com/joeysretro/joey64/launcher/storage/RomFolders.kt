package com.joeysretro.joey64.launcher.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

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

    /**
     * Which storage volume's `ROMs` the picker opens at, from [looks] in volume order (internal
     * storage first). The one whose N64 folder has something in it; else one with an N64 folder
     * (Android may hide other apps' file names, so an N64 folder can read empty when it isn't);
     * else one whose `ROMs` has anything; else internal storage. On the Thor the games are on
     * the SD card and internal `ROMs` is empty, which opened the picker at the empty one.
     */
    fun startVolume(looks: List<RomsLook>): String =
        (looks.firstOrNull { (it.n64Entries ?: 0) > 0 }
            ?: looks.firstOrNull { it.n64Entries != null }
            ?: looks.firstOrNull { (it.romsEntries ?: 0) > 0 })?.volumeId ?: PRIMARY

    const val PRIMARY = "primary"
}

/**
 * One storage volume's `ROMs` folder as Joey64 sees it before any folder is picked, by plain file
 * access. [volumeId] is the volume's part of a document id ("primary", or an SD card's "6439-6232");
 * [romsEntries] is null without a `ROMs` folder, [n64Entries] null without an N64 folder in it.
 */
data class RomsLook(val volumeId: String, val romsEntries: Int?, val n64Entries: Int?)

/**
 * The ROM folders the user picked. Each is a tree URI with a persisted permission, so access
 * survives restarts and reboots without asking again (plan: "the user picks a folder once, and
 * Joey64 keeps permanent access to it"). Which URIs are ROM folders is kept in settings
 * ([com.joeysretro.joey64.launcher.settings.SettingsStore]); the permissions live with Android.
 */
object RomFolders {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /**
     * Where the picker opens: the `ROMs` folder that has the games ([RomFolderRules.startVolume]),
     * on internal storage or a card, so in an ES-DE layout it's one tap (plan). Given as
     * EXTRA_INITIAL_URI (ActivityResultContracts.OpenDocumentTree's input); when the folder
     * doesn't exist the picker opens where it would anyway. Reads a few folders: call it off the
     * main thread where possible (a card can be slow to wake).
     */
    fun initialPickerUri(context: Context): Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, RomFolderRules.startVolume(looks(context)) + ":ROMs")

    /** Every mounted volume's `ROMs`, internal storage first (getExternalFilesDirs' order). */
    fun looks(context: Context): List<RomsLook> {
        val storage = context.getSystemService(StorageManager::class.java)
        return context.getExternalFilesDirs(null).filterNotNull().mapNotNull { appDir ->
            // The app's own folder on that volume is <volume root>/Android/data/<package>/files.
            val root = File(appDir.path.substringBefore("/Android/data/"))
            val volume = storage.getStorageVolume(root) ?: return@mapNotNull null
            val id = if (volume.isPrimary) RomFolderRules.PRIMARY else volume.uuid ?: return@mapNotNull null
            val roms = File(root, "ROMs")
            val names = roms.list()?.toList()
            val n64 = names?.firstOrNull(RomFolderRules::isN64FolderName)
            RomsLook(id, names?.size, n64?.let { File(roms, it).list()?.size ?: 0 })
        }
    }

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
