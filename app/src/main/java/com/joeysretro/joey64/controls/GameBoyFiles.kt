package com.joeysretro.joey64.controls

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import com.joeysretro.joey64.JLog
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Transfer Pak (approved paks mockup 2026-11-08, scratchpad build_paks2.py / paks2_mock.png, screens 4-5): the Game Boy
 * games and saves Joey64 offers. They come from the ROM folders' gb and gbc folders (a picked ROMs folder's children,
 * or a picked folder that is itself one of them), or a file picked with Android's picker. At game start the game is
 * copied (unzipped) to cache and the save to Joey64's own folder (files/transferpak/), so the original isn't changed.
 */
object GameBoyFiles {
    private const val TAG = "Joey64GameBoy"
    val GAME_EXTENSIONS = setOf("gb", "gbc")
    val SAVE_EXTENSIONS = setOf("sav", "srm")
    /** Folder names read for Game Boy files (any case). */
    val FOLDER_NAMES = setOf("gb", "gbc", "gameboy", "game boy", "gameboy color", "game boy color", "gameboycolor")

    /** A Game Boy game or save: its document [uri] (as a string, how PlayerSetup keeps it) and file [name]. */
    data class Found(val uri: String, val name: String)

    /** "Pokémon Gold (USA)" from "Pokémon Gold (USA).gbc" or ".zip". */
    fun title(name: String) = name.substringBeforeLast('.')

    fun isGame(name: String): Boolean = name.substringAfterLast('.', "").lowercase().let { it in GAME_EXTENSIONS || it == "zip" }
    fun isSave(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in SAVE_EXTENSIONS

    /** True for the gb / gbc folders Joey64 reads. */
    fun isGameBoyFolder(name: String) = name.trim().lowercase() in FOLDER_NAMES

    /**
     * The save that goes with [game] by default: one in [saves] with the same title, .sav first, then .srm. Null: none
     * (the game starts with a new save).
     */
    fun saveFor(game: Found, saves: List<Found>): Found? {
        val t = title(game.name)
        return saves.filter { title(it.name).equals(t, ignoreCase = true) }.minByOrNull { if (it.name.endsWith(".sav", true)) 0 else 1 }
    }

    /** Games and saves in [trees]' gb and gbc folders, by name. A .zip counts as a game only when it holds a .gb/.gbc. */
    fun scan(context: Context, trees: List<Uri>): Pair<List<Found>, List<Found>> {
        val games = ArrayList<Found>(); val saves = ArrayList<Found>()
        for (tree in trees) {
            val root = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { continue }
            val rootName = nameOf(context, DocumentsContract.buildDocumentUriUsingTree(tree, root)) ?: ""
            val folders = if (isGameBoyFolder(rootName)) listOf(root)
                else list(context, tree, root).filter { it.isDir && isGameBoyFolder(it.name) }.map { it.docId }
            for (dir in folders) for (c in list(context, tree, dir)) {
                if (c.isDir) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.docId).toString()
                when {
                    isSave(c.name) -> saves += Found(uri, c.name)
                    isGame(c.name) && (!c.name.endsWith(".zip", true) || zipHasGame(context, Uri.parse(uri))) -> games += Found(uri, c.name)
                }
            }
        }
        val byName = compareBy<Found, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        return games.distinctBy { it.uri }.sortedWith(byName) to saves.distinctBy { it.uri }.sortedWith(byName)
    }

    /** The file name of a document (a picked file), or null. */
    fun nameOf(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    private class Child(val docId: String, val name: String, val isDir: Boolean)

    private fun list(context: Context, tree: Uri, docId: String): List<Child> = try {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        context.contentResolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Child(c.getString(0), c.getString(1) ?: continue, c.getString(2) == Document.MIME_TYPE_DIR)) }
        } ?: emptyList()
    } catch (e: Exception) { JLog.w(TAG, "can't list $docId in $tree: $e"); emptyList() }

    private fun zipHasGame(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { s ->
            ZipInputStream(s.buffered()).use { z ->
                generateSequence { z.nextEntry }.any { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in GAME_EXTENSIONS }
            }
        } ?: false
    } catch (e: Exception) { false }

    /**
     * Copies the game at [uri] to [out] (the .gb/.gbc inside a .zip); returns the file written, named as the game
     * (the core names nothing after it, but the log does), or null when it can't be read.
     */
    fun copyGame(context: Context, uri: String, name: String, outDir: File): File? = try {
        outDir.mkdirs(); outDir.listFiles()?.forEach { it.delete() }
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { s ->
            if (name.endsWith(".zip", true)) ZipInputStream(s.buffered()).use { z ->
                var e = z.nextEntry
                while (e != null && (e.isDirectory || e.name.substringAfterLast('.', "").lowercase() !in GAME_EXTENSIONS)) e = z.nextEntry
                e?.let { entry -> File(outDir, entry.name.substringAfterLast('/')).also { f -> f.outputStream().use { z.copyTo(it) } } }
            } else File(outDir, name).also { f -> f.outputStream().use { s.copyTo(it) } }
        }
    } catch (e: Exception) { JLog.e(TAG, "can't copy the Game Boy game $name: $e"); null }

    /**
     * Joey64's own copy of the Game Boy save for a play of [saveName] (the N64 game): files/transferpak/<saveName>.sav.
     * The picked save ([save], a document URI) is copied in when it isn't the one copied last ([SOURCE] beside it says
     * which); none picked keeps whatever copy there is, or the core starts a new one. Returns the copy's path.
     */
    fun saveCopy(context: Context, saveName: String, save: String?): File {
        val dir = File(context.filesDir, "transferpak").apply { mkdirs() }
        val copy = File(dir, "$saveName.sav")
        val source = File(dir, "$saveName.source")
        val from = save ?: "new"
        if (source.takeIf { it.exists() }?.readText() != from) {
            // The copy played until now isn't lost: it stays as <saveName>.prev.sav (one back).
            if (copy.exists()) copy.copyTo(File(dir, "$saveName.prev.sav"), true)
            if (save == null) copy.delete()
            else try {
                context.contentResolver.openInputStream(Uri.parse(save))?.use { s ->
                    val tmp = File(dir, "$saveName.sav.tmp")
                    tmp.outputStream().use { s.copyTo(it) }
                    if (!tmp.renameTo(copy)) { tmp.copyTo(copy, true); tmp.delete() }
                }
            } catch (e: Exception) { JLog.e(TAG, "can't copy the Game Boy save $save: $e") }
            source.writeText(from)
        }
        return copy
    }

    /** "Takes effect when the game next starts": a note for the log. */
    fun describe(game: String?, save: String?) = "Game Boy game ${game ?: "none"}, save ${save ?: "new"}"
}
