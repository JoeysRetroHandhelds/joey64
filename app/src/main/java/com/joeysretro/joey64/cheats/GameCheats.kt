package com.joeysretro.joey64.cheats

import android.content.Context
import android.util.Log
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.storage.DataFolder
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FileSpace
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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
    private val space: FileSpace = DataFolder.internal(context).space,
) {
    private val path = listOf(DataKind.Cheats.dir, "$saveName.json")

    fun read(): CheatChoices = try {
        space.openRead(path)?.use { CheatChoices.parse(it.readBytes().toString(Charsets.UTF_8)) } ?: CheatChoices()
    } catch (e: Exception) {
        JLog.e(TAG, "can't read ${path.joinToString("/")}" + "\n" + Log.getStackTraceString(e))
        CheatChoices()
    }

    /** Written whole or not at all: a .part file renamed over the old one. */
    fun write(choices: CheatChoices) {
        // cheats/ is kept out of the photo gallery (.nomedia), as every hidden folder before it's written.
        runCatching { com.joeysretro.joey64.launcher.storage.DataFolderRules.hideFromGallery(space) }
        val part = listOf(DataKind.Cheats.dir, "$saveName.json.part")
        try {
            space.openWrite(part).use { it.write(choices.toJson().toByteArray(Charsets.UTF_8)) }
            if (space.exists(path) && !space.delete(path)) throw IOException("can't replace ${path.last()}")
            if (!space.rename(part, path.last())) throw IOException("can't rename ${part.last()}")
        } catch (e: IOException) {
            JLog.e(TAG, "can't write ${path.joinToString("/")}" + "\n" + Log.getStackTraceString(e))
            runCatching { space.delete(part) }
        }
    }

    private companion object {
        const val TAG = "Joey64Cheats"
    }
}
