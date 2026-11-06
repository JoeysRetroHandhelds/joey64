package com.joeysretro.joey64.launcher.library

/** A ROM file the scan found in a picked folder ([com.joeysretro.joey64.launcher.storage.RomFolders.scan]). */
data class FoundRom(val uri: String, val treeUri: String, val name: String, val size: Long, val modified: Long)

/** A file the library already knows (the `files` table). [gameId] is null when it couldn't be read as a ROM. */
data class KnownFile(val uri: String, val treeUri: String, val size: Long, val modified: Long, val gameId: Long?)

/**
 * What one scan has to do (plan, Phase 1: "cached by file size and date"):
 *  - [toRead]: new files, and known ones whose size or date changed, are read and hashed.
 *  - [unchanged]: known files with the same size and date keep their game; only `seen_at` moves.
 *  - [gone]: known files no longer in a folder that was scanned leave the library. A folder that
 *    couldn't be scanned (card out, permission taken away) loses nothing: its files stay, so
 *    pulling the SD card doesn't empty the library.
 */
data class ScanPlan(val toRead: List<FoundRom>, val unchanged: List<FoundRom>, val gone: List<String>)

object ScanPlanner {
    fun plan(found: List<FoundRom>, known: List<KnownFile>, scannedTrees: Set<String>): ScanPlan {
        val byUri = known.associateBy { it.uri }
        val toRead = ArrayList<FoundRom>()
        val unchanged = ArrayList<FoundRom>()
        for (f in found) {
            val k = byUri[f.uri]
            if (k != null && k.size == f.size && k.modified == f.modified) unchanged += f else toRead += f
        }
        val foundUris = found.mapTo(HashSet()) { it.uri }
        val gone = known.filter { it.treeUri in scannedTrees && it.uri !in foundUris }.map { it.uri }
        return ScanPlan(toRead, unchanged, gone)
    }

    /**
     * The name a game is listed under until box art brings a better one (Phase 2): the file's
     * name without its extension, so "Super Mario 64 (USA).zip" is "Super Mario 64 (USA)", as in
     * RetroArch's playlists; the header's name is upper-case and cut at 20 characters.
     */
    fun titleOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }
}
