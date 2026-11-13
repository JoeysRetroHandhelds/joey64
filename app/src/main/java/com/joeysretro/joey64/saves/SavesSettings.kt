package com.joeysretro.joey64.saves

import android.content.Context
import com.joeysretro.joey64.JLog
import org.json.JSONObject
import java.io.File

/** Settings › Saves › Continue on Play (approved Saves mockup, 2026-11-07: scratchpad build_saves.py). */
enum class ContinueOnPlay(val label: String, val detail: String) {
    Never("Never", "Starts the game from the beginning"),
    Ask("Ask", "Asks each time"),
    Always("Always", "Goes on from the auto save state"),
}

/** Settings › Saves › Back up saves: when a game's save RAM is copied into `backups/`. */
enum class BackupWhen(val label: String, val detail: String) {
    OnStart("On start", "When a game starts and its save changed"),
    Daily("Daily", "At most one copy a day per game"),
    Off("Off", ""),
}

/**
 * Settings › Saves, the same for every game. The defaults are how Joey64 worked before the tab,
 * except [backups] (new, On start).
 */
data class SavesOptions(
    /** Quit to library writes the auto slot (Continue's). Off: quitting writes no state. */
    val saveOnQuit: Boolean = true,
    /** Ask by default (approved E4 2026-11-11, contplay_mock.png; it was Never). */
    val continueOnPlay: ContinueOnPlay = ContinueOnPlay.Ask,
    /** How many quit saves each game keeps: Auto, Auto 2, ... ([QUIT_SAVES]). */
    val quitSaves: Int = 1,
    val backups: BackupWhen = BackupWhen.OnStart,
    /** Save RAM copies kept per game ([BACKUPS_KEEP]), oldest removed first. */
    val backupsKeep: Int = 5,
    /** Minutes between recovery states; 0 = Off ([RECOVERY_MINUTES]). */
    val recoveryMinutes: Int = 2,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("saveOnQuit", saveOnQuit); put("continueOnPlay", continueOnPlay.name); put("quitSaves", quitSaves)
        put("backups", backups.name); put("backupsKeep", backupsKeep); put("recoveryMinutes", recoveryMinutes)
    }

    companion object {
        val QUIT_SAVES = listOf(1, 3, 5, 10)
        val BACKUPS_KEEP = listOf(3, 5, 10, 20)
        val RECOVERY_MINUTES = listOf(0, 1, 2, 5)

        fun fromJson(o: JSONObject): SavesOptions {
            val d = SavesOptions()
            fun <E : Enum<E>> pick(entries: List<E>, key: String, def: E) = entries.firstOrNull { it.name == o.optString(key) } ?: def
            fun num(key: String, allowed: List<Int>, def: Int) = o.optInt(key, def).takeIf { it in allowed } ?: def
            return SavesOptions(
                saveOnQuit = o.optBoolean("saveOnQuit", d.saveOnQuit),
                continueOnPlay = pick(ContinueOnPlay.entries, "continueOnPlay", d.continueOnPlay),
                quitSaves = num("quitSaves", QUIT_SAVES, d.quitSaves),
                backups = pick(BackupWhen.entries, "backups", d.backups),
                backupsKeep = num("backupsKeep", BACKUPS_KEEP, d.backupsKeep),
                recoveryMinutes = num("recoveryMinutes", RECOVERY_MINUTES, d.recoveryMinutes),
            )
        }
    }
}

/** `files/savesettings/saves.json`, written whole (`.part`, then renamed). Not in `saves/`, which is the games' save RAM. */
class SavesStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, DIR))

    private val file = File(dir, "saves.json")

    fun read(): SavesOptions {
        if (!file.isFile) return SavesOptions()
        return try { SavesOptions.fromJson(JSONObject(file.readText())) } catch (e: Exception) { JLog.e(TAG, "can't read ${file.name}: $e"); SavesOptions() }
    }

    fun save(o: SavesOptions) {
        try {
            dir.mkdirs()
            val part = File(dir, file.name + ".part")
            part.writeText(o.toJson().toString(1))
            if (!part.renameTo(file)) { file.delete(); if (!part.renameTo(file)) throw java.io.IOException("can't rename ${part.name}") }
        } catch (e: Exception) {
            JLog.e(TAG, "can't write ${file.name}: $e")
        }
    }

    companion object {
        const val DIR = "savesettings"
        private const val TAG = "Joey64Saves"
    }
}
