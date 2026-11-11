package com.joeysretro.joey64.controls

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import com.joeysretro.joey64.Capped
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.ZipNames
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Transfer Pak (approved paks mockup 2026-11-08, scratchpad build_paks2.py / paks2_mock.png, screens 4-5): the Game Boy
 * games and saves Joey64 offers. They come from the ROM folders' gb and gbc folders (a picked ROMs folder's children,
 * or a picked folder that is itself one of them), the Game Boy folders picked for them (approved Game Boy folder
 * mockup 2026-11-08, build_gbfolder.py: a ROMs folder, a gb folder, or a saves folder like RetroArch's), or a file
 * picked with Android's picker. At game start the game is
 * copied (unzipped) to cache and the save to Joey64's own folder (files/transferpak/), so the original isn't changed.
 */
object GameBoyFiles {
    private const val TAG = "Joey64GameBoy"
    val GAME_EXTENSIONS = setOf("gb", "gbc")
    val SAVE_EXTENSIONS = setOf("sav", "srm")
    /** RetroArch's folder names (the core's library name) for cores that play Game Boy games, lower case. */
    val GAME_BOY_CORES = setOf("gambatte", "mgba", "sameboy", "gearboy", "tgb dual", "vba-m", "vba next", "doublecherrygb", "emux gb")
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

    /**
     * Which folders of a picked folder are read for Game Boy files, by name ("" is the picked folder itself): itself when
     * it's a gb or gbc folder; else its gb and gbc folders. A Game Boy folder ([gbFolder]: picked for Game Boy games or
     * saves, approved Game Boy folder mockup 2026-11-08, scratchpad build_gbfolder.py) with neither, like RetroArch's
     * saves folder: itself and its Game Boy cores' folders (RetroArch sorts saves by core: saves/Gambatte). Other cores'
     * folders are left out: their games can share a Game Boy game's name (Mario Golf (USA) on the N64, Thor 2026-11-08).
     */
    fun foldersToRead(pickedName: String, childFolders: List<String>, gbFolder: Boolean): List<String> {
        if (isGameBoyFolder(pickedName)) return listOf("")
        val gb = childFolders.filter(::isGameBoyFolder)
        return when {
            gb.isNotEmpty() -> gb
            gbFolder -> listOf("") + childFolders.filter { it.trim().lowercase() in GAME_BOY_CORES }
            else -> emptyList()
        }
    }

    /**
     * True when [save] is named like the game titled [gameTitle]: the same name, or the same name and more (RetroArch's
     * "(1)" copies, Syncthing's ".sync-conflict-..." ones). Any case.
     */
    fun namedLike(save: String, gameTitle: String): Boolean {
        if (!save.startsWith(gameTitle, ignoreCase = true)) return false
        return save.length > gameTitle.length && save[gameTitle.length] in ". "
    }

    /** Where a found file is: the picked folder ([tree], a tree URI) and the folder in it ([sub], "" for the folder itself). */
    data class From(val tree: String, val sub: String)

    /** A Game Boy folder's share of a scan, for the Game Boy folders page. [readable] false: it can't be read now. */
    data class Place(val games: Int, val gameFolders: List<String>, val saves: Int, val saveFolders: List<String>, val readable: Boolean)

    /** What [scan] found: games and saves by name, where each one is (by URI) and each picked folder's share. */
    data class Scan(val games: List<Found>, val saves: List<Found>, val from: Map<String, From>, val places: Map<String, Place>)

    /**
     * Games and saves in [trees] (the ROM folders) and [gbTrees] (the Game Boy folders), read as [foldersToRead] says.
     * A .zip counts as a game only when it holds a .gb/.gbc. Saves from a folder that isn't a gb or gbc folder (a saves
     * folder holds every system's saves) count only when named like a game found ([namedLike]).
     */
    fun scan(context: Context, trees: List<Uri>, gbTrees: List<Uri> = emptyList()): Scan {
        class Hit(val f: Found, val from: From, val gbDir: Boolean)
        val games = ArrayList<Hit>(); val saves = ArrayList<Hit>()
        val unreadable = HashSet<String>()
        for ((tree, gbFolder) in trees.map { it to false } + gbTrees.map { it to true }) {
            val root = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { unreadable += tree.toString(); continue }
            val rootName = root.substringAfter(':', "").substringAfterLast('/')
            val children = list(context, tree, root)
            if (children == null) { unreadable += tree.toString(); continue }
            val dirs = children.filter { it.isDir }
            for (sub in foldersToRead(rootName, dirs.map { it.name }, gbFolder)) {
                val dirId = if (sub.isEmpty()) root else dirs.first { it.name == sub }.docId
                val gbDir = isGameBoyFolder(sub.ifEmpty { rootName })
                for (c in (if (sub.isEmpty()) children else list(context, tree, dirId).orEmpty())) {
                    if (c.isDir) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.docId).toString()
                    val from = From(tree.toString(), sub)
                    when {
                        isSave(c.name) -> saves += Hit(Found(uri, c.name), from, gbDir)
                        // Zips only in gb folders: anywhere else they're other systems' games, and each is opened to look.
                        isGame(c.name) && (!c.name.endsWith(".zip", true) || (gbDir && zipHasGame(context, Uri.parse(uri)))) ->
                            games += Hit(Found(uri, c.name), from, gbDir)
                    }
                }
            }
        }
        val titles = games.map { title(it.f.name) }
        val keptSaves = saves.filter { h -> h.gbDir || titles.any { namedLike(h.f.name, it) } }
        val byName = compareBy<Found, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val from = (games + keptSaves).associate { it.f.uri to it.from }
        val places = (trees + gbTrees).map { it.toString() }.distinct().associateWith { t ->
            val g = games.filter { it.from.tree == t }; val sv = keptSaves.filter { it.from.tree == t }
            Place(g.distinctBy { it.f.uri }.size, g.map { it.from.sub }.filter { it.isNotEmpty() }.distinct(),
                sv.distinctBy { it.f.uri }.size, sv.map { it.from.sub }.filter { it.isNotEmpty() }.distinct(), t !in unreadable)
        }
        return Scan(games.map { it.f }.distinctBy { it.uri }.sortedWith(byName), keptSaves.map { it.f }.distinctBy { it.uri }.sortedWith(byName),
            from, places)
    }

    /** The file name of a document (a picked file), or null. */
    fun nameOf(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    private class Child(val docId: String, val name: String, val isDir: Boolean)

    private fun list(context: Context, tree: Uri, docId: String): List<Child>? = try {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        context.contentResolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Child(c.getString(0), c.getString(1) ?: continue, c.getString(2) == Document.MIME_TYPE_DIR)) }
        }
    } catch (e: Exception) { JLog.w(TAG, "can't list $docId in $tree: $e"); null }

    private fun zipHasGame(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { s ->
            ZipInputStream(s.buffered(), ZipNames).use { z ->
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
            if (name.endsWith(".zip", true)) ZipInputStream(s.buffered(), ZipNames).use { z ->
                var e = z.nextEntry
                while (e != null && (e.isDirectory || e.name.substringAfterLast('.', "").lowercase() !in GAME_EXTENSIONS)) e = z.nextEntry
                e?.let { entry -> File(outDir, leaf(entry.name)).also { f -> f.outputStream().use { Capped.copy(z, it, Capped.PICKED_FILE, "The Game Boy game") } } }
            } else File(outDir, leaf(name)).also { f -> f.outputStream().use { Capped.copy(s, it, Capped.PICKED_FILE, "The Game Boy game") } }
        }
    } catch (e: Exception) { JLog.e(TAG, "can't copy the Game Boy game $name: $e"); null }

    /**
     * The last part of [name] (from the controls file, or a zip entry's), so the copy stays in its folder: a hand-edited
     * or restored controls file can hold any name (code audit 3, p4 L6).
     */
    private fun leaf(name: String) = name.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "game.gb"

    /** The Game Boy save copy's name in `saves/` for a play of [saveName]. */
    fun saveFileName(saveName: String) = "$saveName.gb.sav"

    /**
     * Joey64's own copy of the Game Boy save for a play of [saveName] (the N64 game): files/saves/<saveName>.gb.sav, with
     * the save RAM, so it goes with the data folder (SrmSync), its moves and backups. The picked save ([save], a document
     * URI) is copied in when it isn't the one copied last (files/transferpak/<saveName>.source says which); none picked
     * keeps whatever copy there is, or the core starts a new one. Returns the copy's path, or null when the picked save
     * couldn't be read or the copy written (code audit 3, M8: it was marked copied anyway, and the old copy played, another
     * game's save maybe, with no word): then nothing changed, and the next start tries again.
     */
    fun saveCopy(context: Context, saveName: String, save: String?): File? = try {
        val dir = File(context.filesDir, "transferpak").apply { mkdirs() }
        val copy = File(File(context.filesDir, "saves").apply { mkdirs() }, saveFileName(saveName))
        // Built 2026-11-08 with the copy in files/transferpak/<saveName>.sav: moved once.
        File(dir, "$saveName.sav").let { old -> if (old.exists() && !copy.exists() && !old.renameTo(copy)) { old.copyTo(copy); old.delete() } }
        val source = File(dir, "$saveName.source")
        val from = save ?: "new"
        if (source.takeIf { it.exists() }?.readText() != from) {
            // The picked save is read whole first, so one that can't be read changes nothing.
            val tmp = File(dir, "$saveName.sav.tmp")
            if (save != null) {
                val input = context.contentResolver.openInputStream(Uri.parse(save)) ?: throw java.io.IOException("no stream")
                input.use { s -> tmp.outputStream().use { s.copyTo(it) } }
            }
            // The copy played until now isn't lost: it stays as <saveName>.prev.sav (one back).
            if (copy.exists()) copy.copyTo(File(dir, "$saveName.prev.sav"), true)
            if (save == null) copy.delete()
            else if (!tmp.renameTo(copy)) { tmp.copyTo(copy, true); tmp.delete() }
            source.writeText(from)
        }
        copy
    } catch (e: Exception) {
        JLog.e(TAG, "can't copy the Game Boy save $save: $e")
        File(File(context.filesDir, "transferpak"), "$saveName.sav.tmp").delete()
        null
    }

    /** "Takes effect when the game next starts": a note for the log. */
    fun describe(game: String?, save: String?) = "Game Boy game ${game ?: "none"}, save ${save ?: "new"}"
}
