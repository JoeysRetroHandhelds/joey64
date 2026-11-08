package com.joeysretro.joey64.saves

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FileCopy
import com.joeysretro.joey64.launcher.storage.FileSpace
import com.joeysretro.joey64.launcher.theme.PopupTheme
import com.joeysretro.joey64.launcher.theme.ThemeChoice
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a backup holds, or what a restore brings back (approved Saves mockup 2026-11-07, screens 7 and 8). */
enum class BackupPart { Saves, Screenshots, Settings }

/** Back up Joey64's popup: Everything, Saves only, Settings only. */
enum class BackupChoice(val label: String, val detail: String, val parts: Set<BackupPart>) {
    Everything("Everything", "Saves, states, screenshots, settings, library lists", BackupPart.entries.toSet()),
    SavesOnly("Saves only", "In-game saves and states", setOf(BackupPart.Saves)),
    SettingsOnly("Settings only", "Settings, favourites, collections, play time", setOf(BackupPart.Settings)),
}

/** A backup's `joey64-backup.json`: when it was made and what's in it. */
data class BackupInfo(val madeAt: Long, val parts: Set<BackupPart>, val saves: Int, val states: Int, val screenshots: Int) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1); put("made_at", madeAt); put("parts", JSONArray(parts.map { it.name }))
        put("saves", saves); put("states", states); put("screenshots", screenshots)
    }

    companion object {
        fun fromJson(o: JSONObject) = BackupInfo(
            madeAt = o.optLong("made_at"),
            parts = o.optJSONArray("parts")?.let { a -> (0 until a.length()).mapNotNull { i -> BackupPart.entries.firstOrNull { it.name == a.optString(i) } }.toSet() } ?: emptySet(),
            saves = o.optInt("saves"), states = o.optInt("states"), screenshots = o.optInt("screenshots"),
        )
    }
}

/**
 * Settings › Saves › Back up Joey64 and Restore a backup: one .zip in a folder the user picks.
 *  - Saves: `saves/` (save RAM), `states/` (with their pictures) and `backups/`, from the data folder.
 *  - Screenshots: `screenshots/` (Everything only).
 *  - Settings: Video, Audio, Controls (with hotkeys), On screen and Saves settings, the cheats turned
 *    on, RetroAchievements' settings, Appearance, and the library's lists (favourites, collections,
 *    play time) by MD5.
 * ROMs, box art, downloaded cheat lists and the RetroAchievements sign-in never go in. Restoring
 * replaces files with the same name and leaves the rest. Blocking: call it off the main thread.
 */
object AppBackup {
    private const val TAG = "Joey64Backup"
    const val MANIFEST = "joey64-backup.json"
    /** The 64DD BIOS goes with the saves: a restored disk save needs it to start. */
    private val SAVE_KINDS = listOf(DataKind.Saves, DataKind.States, DataKind.Backups, DataKind.Bios)
    /** Settings folders in Joey64's own storage. */
    private val SETTINGS_DIRS = listOf("video", "emulation", "audio", "controls", "onscreen", SavesStore.DIR,
        com.joeysretro.joey64.achievements.UnlockSound.DIR)
    /** SharedPreferences that are settings (not the RetroAchievements account: that's SignInStore's). */
    private val PREFS = listOf("ra_settings", "states")
    private val NAME_OK = Regex("""[^/\\]+""")

    /** "Joey64 backup 2026-11-07 1530.zip". */
    fun fileName(now: Long = System.currentTimeMillis()) =
        "Joey64 backup " + java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.US).format(java.util.Date(now)) + ".zip"

    /**
     * Writes the backup to [out]; [onFile] counts files done. The number of files written. Every
     * entry carries [madeAt] as its time ([SettingsMirror] fixes it, so the same settings give the
     * same bytes); [withCheats] false leaves the cheats turned on out of the Settings part.
     */
    suspend fun write(context: Context, data: FileSpace, parts: Set<BackupPart>, out: OutputStream,
                      withCheats: Boolean = true, madeAt: Long = System.currentTimeMillis(), onFile: (Int) -> Unit = {}): Int {
        var n = 0
        fun kindFiles(k: DataKind) = data.list(listOf(k.dir)).filter {
            !it.isDir && it.name != DataKind.NO_MEDIA && !it.name.endsWith(".part") &&
                !it.name.endsWith(FileCopy.PART_SUFFIX) && !it.name.endsWith(FileCopy.OLD_SUFFIX)
        }
        val saveFiles = if (BackupPart.Saves in parts) SAVE_KINDS.associateWith { kindFiles(it) } else emptyMap()
        val shots = if (BackupPart.Screenshots in parts) kindFiles(DataKind.Screenshots) else emptyList()
        val info = BackupInfo(madeAt, parts,
            saves = saveFiles[DataKind.Saves]?.count { it.name.endsWith(".srm") } ?: 0,
            states = saveFiles[DataKind.States]?.count { !it.name.endsWith(".png") && !it.name.endsWith(".old") } ?: 0,
            screenshots = shots.size)
        ZipOutputStream(out.buffered()).use { zip ->
            zip.setLevel(1)
            fun entry(name: String) = ZipEntry(name).apply { time = madeAt }
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(entry(name)); zip.write(bytes); zip.closeEntry(); onFile(++n) }
            fun putStream(name: String, input: InputStream?) {
                input ?: return
                zip.putNextEntry(entry(name)); input.use { it.copyTo(zip) }; zip.closeEntry(); onFile(++n)
            }
            put(MANIFEST, info.toJson().toString(1).toByteArray())
            for ((k, files) in saveFiles) for (f in files) putStream("data/${k.dir}/${f.name}", data.openRead(listOf(k.dir, f.name)))
            for (f in shots) putStream("data/${DataKind.Screenshots.dir}/${f.name}", data.openRead(listOf(DataKind.Screenshots.dir, f.name)))
            if (BackupPart.Settings in parts) {
                for (dir in SETTINGS_DIRS) {
                    val root = File(context.filesDir, dir)
                    root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.forEach { f ->
                        putStream("files/$dir/" + f.relativeTo(root).invariantSeparatorsPath, f.inputStream())
                    }
                }
                if (withCheats) for (f in kindFiles(DataKind.Cheats)) putStream("data/${DataKind.Cheats.dir}/${f.name}", data.openRead(listOf(DataKind.Cheats.dir, f.name)))
                for (p in PREFS) put("prefs/$p.json", prefsToJson(context.getSharedPreferences(p, Context.MODE_PRIVATE).all).toString(1).toByteArray())
                val s = SettingsStore(context).settings.first()
                put("launcher.json", JSONObject().apply {
                    put("theme", s.theme.name); put("popup_theme", s.popupTheme.name)
                    put("download_box_art", s.downloadBoxArt); put("download_cheats", s.downloadCheats)
                }.toString(1).toByteArray())
                put("library.json", LibraryDb.get(context).exportLists().toString().toByteArray())
            }
        }
        if (withCheats) JLog.i(TAG, "backup written: $n files, parts $parts")
        return n
    }

    /**
     * True when the zip in [bytes] has the manifest and every entry reads to its end with the right CRC, so a copy
     * cut short isn't brought in half (SettingsMirror).
     */
    fun readsWhole(bytes: ByteArray): Boolean = try {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var manifest = false
            val buf = ByteArray(16 * 1024)
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == MANIFEST) manifest = true
                while (zip.read(buf) >= 0) {}
                e = zip.nextEntry
            }
            manifest
        }
    } catch (e: Exception) { false }

    /** The backup's manifest (the first entry), or null when [input] isn't a Joey64 backup. */
    fun info(input: InputStream): BackupInfo? = try {
        ZipInputStream(input.buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null && e.name != MANIFEST) e = zip.nextEntry
            if (e == null) null else BackupInfo.fromJson(JSONObject(zip.readBytes().decodeToString()))
        }
    } catch (e: Exception) { JLog.e(TAG, "not a backup: $e"); null }

    /** Brings back [parts] of the backup in [input]. The number of files written. */
    suspend fun restore(context: Context, data: FileSpace, parts: Set<BackupPart>, input: InputStream, onFile: (Int) -> Unit = {}): Int {
        var n = 0
        var launcher: JSONObject? = null
        var library: JSONObject? = null
        ZipInputStream(input.buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = e.name
                val seg = name.split('/')
                if (!e.isDirectory && seg.all { NAME_OK.matches(it) && it != ".." && it != "." }) {
                    when {
                        seg.size == 3 && seg[0] == "data" -> {
                            val kind = DataKind.entries.firstOrNull { it.dir == seg[1] }
                            val part = when (kind) {
                                DataKind.Saves, DataKind.States, DataKind.Backups, DataKind.Bios -> BackupPart.Saves
                                DataKind.Screenshots -> BackupPart.Screenshots
                                DataKind.Cheats -> BackupPart.Settings
                                else -> null
                            }
                            // Through a checked temporary file: a damaged backup or a restore cut off keeps the old save whole.
                            if (kind != null && part in parts) {
                                if (!FileCopy.writeChecked(zip, data, listOf(kind.dir, seg[2]))) throw IOException("can't restore $name")
                                onFile(++n)
                            }
                        }
                        BackupPart.Settings !in parts -> {}
                        seg.size >= 3 && seg[0] == "files" && seg[1] in SETTINGS_DIRS -> {
                            val f = seg.drop(1).fold(context.filesDir) { d, s -> File(d, s) }
                            f.parentFile?.mkdirs()
                            val part = File(f.parentFile, f.name + ".part")
                            part.outputStream().use { zip.copyTo(it) }
                            if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw IOException("can't put $name in place") }
                            onFile(++n)
                        }
                        seg.size == 2 && seg[0] == "prefs" && seg[1].removeSuffix(".json") in PREFS -> {
                            jsonToPrefs(JSONObject(zip.readBytes().decodeToString()), context.getSharedPreferences(seg[1].removeSuffix(".json"), Context.MODE_PRIVATE))
                            onFile(++n)
                        }
                        name == "launcher.json" -> { launcher = JSONObject(zip.readBytes().decodeToString()); onFile(++n) }
                        name == "library.json" -> { library = JSONObject(zip.readBytes().decodeToString()); onFile(++n) }
                    }
                }
                e = zip.nextEntry
            }
        }
        if (BackupPart.Settings in parts) {
            com.joeysretro.joey64.achievements.RaSettings.reload(context)
            launcher?.let { l ->
                val store = SettingsStore(context)
                ThemeChoice.entries.firstOrNull { it.name == l.optString("theme") }?.let { store.setTheme(it) }
                PopupTheme.entries.firstOrNull { it.name == l.optString("popup_theme") }?.let { store.setPopupTheme(it) }
                if (l.has("download_box_art")) store.setDownloadBoxArt(l.optBoolean("download_box_art"))
                if (l.has("download_cheats")) store.setDownloadCheats(l.optBoolean("download_cheats"))
            }
            library?.let { LibraryDb.get(context).importLists(it) }
        }
        JLog.i(TAG, "backup restored: $n files, parts $parts")
        return n
    }

    internal fun prefsToJson(all: Map<String, *>): JSONObject = JSONObject().apply {
        for ((k, v) in all) {
            val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; is Set<*> -> "ss"; else -> null } ?: continue
            put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
        }
    }

    private fun jsonToPrefs(o: JSONObject, prefs: android.content.SharedPreferences) {
        val ed = prefs.edit().clear()
        for (k in o.keys()) {
            val e = o.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.optBoolean("v"))
                "i" -> ed.putInt(k, e.optInt("v"))
                "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                "s" -> ed.putString(k, e.optString("v"))
                "ss" -> ed.putStringSet(k, e.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
            }
        }
        ed.commit()
    }
}
