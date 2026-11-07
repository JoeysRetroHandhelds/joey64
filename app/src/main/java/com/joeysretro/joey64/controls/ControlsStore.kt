package com.joeysretro.joey64.controls

import android.content.Context
import com.joeysretro.joey64.JLog
import org.json.JSONObject
import java.io.File

/**
 * Where Controls lives: Joey64's own `files/controls/` (both the launcher and the game screen read
 * it; the game screen runs while the launcher is paused, so they never write at once).
 *  - `all.json`: All games, the four players.
 *  - `games/<save name>.json`: a game's own controls, a whole copy made from All games on its first
 *    change (approved mockups 8 and 9). Which controller each player has is always All games'.
 *  - `hotkeys.json`: the hotkey button and the hotkeys.
 * Written whole (`.part`, then renamed). A file that can't be read is logged and the defaults used.
 */
class ControlsStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "controls"))

    private val all = File(dir, "all.json")
    private val games = File(dir, "games")
    private val hotkeysFile = File(dir, "hotkeys.json")

    fun all(): ControlsSet = read(all)?.let { ControlsSet.fromJson(it) } ?: ControlsSet.defaults()
    fun saveAll(set: ControlsSet) = write(all, set.toJson())

    /** A game's own controls, or null when it uses All games. */
    fun game(saveName: String): ControlsSet? = read(gameFile(saveName))?.let { ControlsSet.fromJson(it) }
    fun saveGame(saveName: String, set: ControlsSet) = write(gameFile(saveName), set.toJson())
    fun removeGame(saveName: String) { if (!gameFile(saveName).delete() && gameFile(saveName).exists()) JLog.e(TAG, "can't remove $saveName's controls") }

    /** The games with their own controls, by save name. */
    fun gamesWithOwn(): List<String> = games.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()

    /**
     * What a game plays with: its own buttons, sticks and paks when it has them, with All games'
     * controller for each player.
     */
    fun forGame(saveName: String?): ControlsSet {
        val a = all()
        val g = saveName?.let { game(it) } ?: return a
        return ControlsSet(List(4) { i -> g.players[i].copy(device = a.players[i].device) })
    }

    fun hotkeys(): Hotkeys = read(hotkeysFile)?.let { Hotkeys.fromJson(it) } ?: Hotkeys()
    fun saveHotkeys(h: Hotkeys) = write(hotkeysFile, h.toJson())

    private fun gameFile(saveName: String) = File(games, safe(saveName) + ".json")

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

    companion object {
        private const val TAG = "Joey64Controls"
        /** A save name as a file name: path separators and other unsafe characters become '_'. */
        fun safe(name: String) = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").take(150)
    }
}

/**
 * Auto pak (plan, Phase 4): the Rumble Pak for the games the core's list says use one (and don't
 * save only to a Controller Pak), the Controller Pak for the rest. Built by devtools/make-pakdb.py.
 */
object PakDb {
    @Volatile private var rumble: Set<String>? = null

    fun load(context: Context): Set<String> = rumble ?: try {
        context.assets.open("pakdb.tsv").bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase() }.filter { it.length == 32 && !it.startsWith("#") }.toHashSet()
        }
    } catch (e: Exception) {
        JLog.e("Joey64Controls", "can't read pakdb.tsv: $e"); emptySet()
    }.also { rumble = it }

    /** The pak Auto picks for the game with [md5] (null: unknown, the Controller Pak). */
    fun auto(rumbleGames: Set<String>, md5: String?): Pak =
        if (md5 != null && md5.lowercase() in rumbleGames) Pak.Rumble else Pak.Controller
}
