package com.joeysretro.joey64

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Joey64's recently played games for JoeyOS's Recently Played (asked for by the JoeyOS session for
 * Joey, 2026-11-06): `Android/data/com.joeysretro.joey64/files/recent.json`, a JSON array, newest
 * first, one entry per game, at most [MAX]:
 * `{"title", "file", "uri", "path", "saveName", "lastPlayed"}`. JoeyOS can't read Joey64's own
 * storage, and a picked data folder can have any name, so this one file is the place it looks.
 * Written whole (`.part`, then renamed), when a game starts (from the library, JoeyOS or a file
 * manager) and again when it's quit. Dev starts (`--es rom`, test.z64) aren't listed.
 */
object RecentlyPlayed {
    const val FILE = "recent.json"
    const val MAX = 50

    data class Entry(
        /** The library's title, else the file name without extension. */
        val title: String,
        /** The ROM's name as it was handed over (the archive's, for a zip or 7z). */
        val file: String,
        /** The URI Joey64 was started with (the archive's, when the launcher unpacked one). */
        val uri: String,
        /** The real file path when it can be worked out from [uri], else "". */
        val path: String,
        val saveName: String,
        val lastPlayed: Long,
    )

    /** [entry] on top of [existing] (the file's text, or null), the game's older entry dropped, at most [MAX]. */
    fun merge(existing: String?, entry: Entry): String {
        val old = try { if (existing.isNullOrBlank()) JSONArray() else JSONArray(existing) } catch (e: Exception) { JSONArray() }
        val out = JSONArray()
        out.put(JSONObject().apply {
            put("title", entry.title)
            put("file", entry.file)
            put("uri", entry.uri)
            if (entry.path.isNotEmpty()) put("path", entry.path)
            put("saveName", entry.saveName)
            put("lastPlayed", entry.lastPlayed)
        })
        for (i in 0 until old.length()) {
            if (out.length() >= MAX) break
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("saveName") == entry.saveName) continue
            out.put(o)
        }
        return out.toString()
    }

    /**
     * A file path from an external storage document id: "primary:ROMs/n64/x.z64" is
     * /storage/emulated/0/ROMs/n64/x.z64, "<uuid>:ROMs/x.z64" is /storage/<uuid>/ROMs/x.z64. Null for anything else.
     */
    fun pathFromDocumentId(docId: String): String? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        // "primary", or a removable volume's id (6439-6232); other ids (msf:, raw:) aren't paths on a volume.
        if (!volume.equals("primary", ignoreCase = true) && !Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(volume)) return null
        val rest = docId.substring(colon + 1).trimStart('/')
        val root = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
        return if (rest.isEmpty()) root else "$root/$rest"
    }

    /** The real path behind [uri], or "" when it can't be told. */
    fun pathOf(uri: Uri): String = when {
        uri.scheme == "file" -> uri.path ?: ""
        uri.authority == "com.android.externalstorage.documents" ->
            (try { DocumentsContract.getDocumentId(uri) } catch (e: Exception) { null })?.let { pathFromDocumentId(it) } ?: ""
        else -> ""
    }

    /** Puts [entry] at the top of the file. Blocks (file I/O): call it off the main thread. */
    fun record(context: Context, entry: Entry) {
        val dir = context.getExternalFilesDir(null) ?: run { JLog.e(TAG, "recently played: no external files folder"); return }
        val file = File(dir, FILE)
        val part = File(dir, "$FILE.part")
        try {
            val text = merge(if (file.isFile) file.readText() else null, entry)
            part.writeText(text)
            if (!part.renameTo(file)) { file.delete(); if (!part.renameTo(file)) throw java.io.IOException("can't rename ${part.name}") }
        } catch (e: Exception) {
            JLog.e(TAG, "recently played: can't write $file: $e")
        }
    }

    private const val TAG = "Joey64Recent"
}
