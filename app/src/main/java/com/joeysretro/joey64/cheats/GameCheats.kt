package com.joeysretro.joey64.cheats

import android.content.Context
import android.util.Log
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.storage.DataFolder
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FileSpace
import org.json.JSONArray
import org.json.JSONObject

/** A cheat that's on: the option picked ([value], null for a plain one) and the codes it runs, values filled in. */
data class CheatOn(val value: String?, val codes: List<String>)

/** A code the user added (Add code). */
data class OwnCode(val name: String, val codes: List<String>, val on: Boolean)

/** One game's cheats as the user left them. */
data class CheatChoices(val on: Map<String, CheatOn> = emptyMap(), val own: List<OwnCode> = emptyList()) {
    val count get() = on.size + own.count { it.on }

    /** What the core runs: one line per cheat, its codes joined (retro_cheat_set takes any separator). */
    fun codeLines(): List<String> = on.values.map { it.codes.joinToString(",") } + own.filter { it.on }.map { it.codes.joinToString(",") }

    fun toJson(): String = JSONObject().apply {
        put("format", 1)
        put("on", JSONArray().apply {
            for ((name, c) in on) put(JSONObject().apply {
                put("name", name)
                if (c.value != null) put("value", c.value)
                put("codes", JSONArray(c.codes))
            })
        })
        put("own", JSONArray().apply {
            for (o in own) put(JSONObject().apply { put("name", o.name); put("codes", JSONArray(o.codes)); put("on", o.on) })
        })
    }.toString(1)

    companion object {
        fun parse(text: String): CheatChoices {
            val o = JSONObject(text)
            fun JSONArray.strings() = (0 until length()).map { getString(it) }
            val on = LinkedHashMap<String, CheatOn>()
            o.optJSONArray("on")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    on[it.getString("name")] = CheatOn(it.optString("value").takeIf { v -> v.isNotEmpty() }, it.getJSONArray("codes").strings())
                }
            }
            val own = o.optJSONArray("own")?.let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { OwnCode(it.getString("name"), it.getJSONArray("codes").strings(), it.optBoolean("on")) } }
            } ?: emptyList()
            return CheatChoices(on, own)
        }
    }
}

/**
 * The cheats the user turned on for one game, and the codes they added, in the data folder's
 * `cheats/<save name>.json` (named like the game's save). The codes on are kept with their values
 * filled in, so the game starts with them without the downloaded lists. Blocks: call off the main thread.
 */
class GameCheats(
    context: Context,
    val saveName: String,
    private val space: FileSpace = com.joeysretro.joey64.launcher.storage.DataFolders.current(context).space,
) {
    private val path = listOf(DataKind.Cheats.dir, "$saveName.json")

    fun read(): CheatChoices = try {
        space.openRead(path)?.use { CheatChoices.parse(it.readBytes().toString(Charsets.UTF_8)) } ?: CheatChoices()
    } catch (e: Exception) {
        JLog.e(TAG, "can't read ${path.joinToString("/")}" + "\n" + Log.getStackTraceString(e))
        CheatChoices()
    }

    /**
     * Written whole or not at all, one write at a time in the process (code audit 3, H3: two writes at once shared the
     * .part name, and the second deleted the file the first had just put in place, so every cheat and typed code was
     * lost). A checked copy goes over the old file, which stays whole until the new one is in (FileCopy.writeChecked;
     * before, the old one was deleted first). Blocks: call off the main thread, or use [writeLater].
     */
    fun write(choices: CheatChoices) = synchronized(LOCK) {
        // cheats/ is kept out of the photo gallery (.nomedia), as every hidden folder before it's written.
        runCatching { com.joeysretro.joey64.launcher.storage.DataFolderRules.hideFromGallery(space) }
        val bytes = choices.toJson().toByteArray(Charsets.UTF_8)
        val ok = try {
            com.joeysretro.joey64.launcher.storage.FileCopy.writeChecked(bytes.inputStream(), space, path)
        } catch (e: Exception) {
            JLog.e(TAG, "can't write ${path.joinToString("/")}" + "\n" + Log.getStackTraceString(e)); false
        }
        if (!ok) JLog.e(TAG, "can't write ${path.joinToString("/")}")
    }

    /** [write] on Joey64's one cheats writer, in the order asked: the file ends as the last change left it. */
    fun writeLater(choices: CheatChoices) { WRITER.execute { write(choices) } }

    companion object {
        private const val TAG = "Joey64Cheats"
        private val LOCK = Any()
        private val WRITER = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "joey64-cheats") }

        /**
         * Waits, up to [ms], for the writes [writeLater] queued before now (code audit 3, M7: the in-game Cheats page
         * closed and the game read the file before its last change was written). Off the main thread.
         */
        fun awaitWrites(ms: Long = 5_000) {
            try { WRITER.submit {}.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch (e: Exception) { JLog.w(TAG, "cheats: still writing after $ms ms: $e") }
        }
    }
}
