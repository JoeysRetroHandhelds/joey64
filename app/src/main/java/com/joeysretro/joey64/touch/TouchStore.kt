package com.joeysretro.joey64.touch

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.controls.ControlsStore
import org.json.JSONObject
import java.io.File

/**
 * Touch controls' settings: `files/controls/touch/all.json` and `games/<save name>.json` (a whole copy, made on a
 * game's first change; removed again when it matches All games). In `controls/`, so backups and Game settings'
 * Controls part carry it.
 */
class TouchStore(private val dir: File) {
    constructor(context: Context) : this(File(File(context.filesDir, "controls"), "touch"))

    private val allFile = File(dir, "all.json")
    private val games = File(dir, "games")

    fun all(): TouchSettings = read(allFile)?.let { TouchSettings.fromJson(it) } ?: TouchSettings()

    /**
     * Player 1's macros went from [old] to [new] (code audit 4, D-M3: Macro buttons find theirs by name, so a rename or
     * delete left a button that did nothing). In every touch file, a Macro button whose macro that file's game no longer
     * has ([macrosFor]: the names it sees, null = All games) follows a rename, or goes when its macro was deleted.
     */
    fun macrosChanged(old: List<String>, new: List<String>, macrosFor: (String?) -> List<String>) {
        val renames = if (old.size == new.size) old.zip(new).filter { (a, b) -> a != b }.toMap() else emptyMap()
        val removed = (old.toSet() - new.toSet()) - renames.keys
        if (renames.isEmpty() && removed.isEmpty()) return
        for (save in listOf<String?>(null) + gamesWithOwn()) {
            val t = (if (save == null) read(allFile) else read(gameFile(save)))?.let { TouchSettings.fromJson(it) } ?: continue
            val fixed = t.withMacros(renames, removed, macrosFor(save).toSet())
            if (fixed != t) { save(save, fixed); JLog.i(TAG, "touch controls: macro buttons follow a macro change (${save ?: "All games"})") }
        }
    }
    fun saveAll(t: TouchSettings) = write(allFile, t.toJson())

    fun game(saveName: String): TouchSettings? = read(gameFile(saveName))?.let { TouchSettings.fromJson(it) }
    fun forGame(saveName: String?): TouchSettings = saveName?.let { game(it) } ?: all()

    /** A game's settings: removed when they're All games' again. */
    fun saveGame(saveName: String, t: TouchSettings) {
        if (t.changedFrom(all()).isEmpty()) removeGame(saveName) else write(gameFile(saveName), t.toJson())
    }
    fun removeGame(saveName: String) { val f = gameFile(saveName); if (!f.delete() && f.exists()) JLog.e(TAG, "can't remove $saveName's touch controls") }

    fun gamesWithOwn(): List<String> = games.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()

    fun changeCount(saveName: String): Int = game(saveName)?.changedFrom(all())?.size ?: 0

    /** Save for [saveName] (null: All games). */
    fun save(saveName: String?, t: TouchSettings) = if (saveName == null) saveAll(t) else saveGame(saveName, t)

    private fun gameFile(saveName: String) = File(games, ControlsStore.safe(saveName) + ".json")

    private fun read(f: File): JSONObject? {
        if (!f.isFile) return null
        return try { JSONObject(f.readText()) } catch (e: Exception) { JLog.e(TAG, "can't read ${f.name}: $e"); null }
    }

    private fun write(f: File, o: JSONObject) {
        try {
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(o.toString(1))
            if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw java.io.IOException("can't rename ${part.name}") }
        } catch (e: Exception) {
            JLog.e(TAG, "can't write ${f.name}: $e")
        }
    }

    companion object { private const val TAG = "Joey64Touch" }
}
