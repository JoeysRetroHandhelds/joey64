package com.joeysretro.joey64.video

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.joeysretro.joey64.JLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Your own RetroArch preset (approved mockup 2026-11-08, scratchpad build_libra_mock.py, "Approve as drawn"):
 * the .slangp presets in a folder the user picks (Android's folder picker; only read, RetroArch's folder is never
 * changed), each copied with only the files it uses into `files/video/slang/<folder name>/` when it's used or
 * previewed, so it keeps working if that folder moves. The host draws it through librashader (libra.h).
 *
 * Stored as the Shader row's value "slang:<folder name>/<path in it>" and [VideoKey.SlangParams], the settings
 * changed from the preset's own ("name=value;name=value"). This object is the pure part (tested by SlangTest).
 */
object Slang {
    const val PREFIX = "slang:"

    fun isSlang(value: String) = value.startsWith(PREFIX)

    /** "crt-geom" for "slang:shaders_slang/crt/crt-geom.slangp". */
    fun name(value: String): String = value.removePrefix(PREFIX).substringAfterLast('/').removeSuffix(".slangp")

    /** The folder it's in, under the picked one: "crt", or "" at its top. */
    fun folder(rel: String): String = rel.substringBeforeLast('/', "")

    /** [rel] (a path inside the picked folder) joined onto [dir] with ./ and ../ worked out; null when it leaves the folder. */
    fun resolve(dir: String, rel: String): String? {
        val parts = ArrayList<String>()
        for (p in (if (dir.isEmpty()) "" else "$dir/").plus(rel.replace('\\', '/')).split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/").ifEmpty { null }
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t.substring(1, t.length - 1) else t
    }

    /** What a .slangp's text names: its shaders, look-up textures and the presets it builds on (#reference). */
    fun presetRefs(text: String): List<String> {
        val values = LinkedHashMap<String, String>()
        val refs = ArrayList<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#reference")) { refs.add(unquote(line.removePrefix("#reference"))); continue }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            var v = line.substring(eq + 1).trim()
            // A comment after the value: a quoted one ends at its closing quote.
            v = if (v.startsWith('"')) v.substring(1).substringBefore('"') else v.substringBefore('#').substringBefore("//").trim()
            values[line.substring(0, eq).trim()] = v
        }
        for ((k, v) in values) if (Regex("shader\\d+").matches(k)) refs.add(v)
        values["textures"]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { t -> values[t]?.let(refs::add) }
        return refs.filter { it.isNotEmpty() }
    }

    /** The files a shader's text #includes. */
    fun includes(text: String): List<String> = text.lines().mapNotNull { l ->
        val t = l.trim()
        if (t.startsWith("#include")) unquote(t.removePrefix("#include").trim().substringBefore("//").trim()).takeIf { it.isNotEmpty() } else null
    }

    data class Deps(val files: List<String>, val missing: List<String>)

    /**
     * Every file [preset] (a path in the picked folder) uses, itself first: shaders and their #includes, textures,
     * #reference presets and theirs. [read] gives a text file's content, or null when it isn't there.
     * [missing] has the ones that aren't (or that would be outside the folder).
     */
    fun deps(preset: String, read: (String) -> String?, exists: (String) -> Boolean = { read(it) != null }): Deps {
        val files = LinkedHashSet<String>()
        val missing = ArrayList<String>()
        fun visit(path: String, from: String) {
            if (path in files) return
            val lower = path.lowercase(Locale.ROOT)
            val textual = lower.endsWith(".slangp") || lower.endsWith(".slang") || lower.endsWith(".inc") || lower.endsWith(".h") ||
                lower.endsWith(".glsl") || lower.endsWith(".params") || !lower.substringAfterLast('/').contains('.')
            val image = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".bmp") || lower.endsWith(".tga")
            if (image || !textual) {
                if (exists(path)) files.add(path) else missing.add("$from: $path")
                return
            }
            val text = read(path)
            if (text == null) { missing.add("$from: $path"); return }
            files.add(path)
            val dir = folder(path)
            val next = if (lower.endsWith(".slangp")) presetRefs(text) else includes(text)
            for (r in next) {
                val p = resolve(dir, r)
                if (p == null) missing.add("$path: $r") else visit(p, path)
            }
        }
        visit(preset, preset)
        return Deps(files.toList(), missing)
    }

    fun paramsOf(s: String): Map<String, Float> = s.split(';').mapNotNull { part ->
        val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val f = v.trim().toFloatOrNull() ?: return@mapNotNull null
        k.trim().takeIf { it.isNotEmpty() }?.let { it to f }
    }.toMap()

    fun paramsString(m: Map<String, Float>): String = m.entries.sortedBy { it.key }.joinToString(";") { (k, v) -> "$k=${num(v)}" }

    /**
     * A value as the settings list shows it (approved mockup screen 3: 2.40, 1.00, 0.030, 1000): two decimals,
     * three for a step finer than 0.01, none for a step of 10 or more.
     */
    fun format(v: Float, step: Float): String {
        val s = num(step)
        val d = when {
            step >= 10f -> 0
            '.' in s && s.substringAfter('.').length > 2 -> 3
            else -> 2
        }
        return String.format(Locale.ROOT, "%.${d}f", v)
    }

    private fun num(v: Float): String {
        val r = Math.round(v * 10000f) / 10000f
        return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
    }

    /** One of a preset's settings, from the host (NativeBridge.slangParams). */
    data class Param(val name: String, val description: String, val value: Float, val min: Float, val max: Float, val step: Float) {
        val label: String get() = description.ifBlank { name }
        /** One step up (+1) or down (-1), kept in range and on the step's grid from the minimum. */
        fun stepped(from: Float, dir: Int): Float {
            val st = if (step > 0f) step else (max - min) / 100f
            if (st <= 0f) return from
            val k = Math.round((from - min) / st) + dir
            return (min + k * st).coerceIn(min, max)
        }
    }

    /** NativeBridge.slangParams' strings as settings; null with the message when it failed. */
    fun parseParams(a: Array<String>): Pair<List<Param>?, String?> {
        if (a.size == 2 && a[0] == "!") return null to a[1]
        val out = ArrayList<Param>()
        var i = 0
        while (i + 5 < a.size) {
            out.add(Param(a[i], a[i + 1], a[i + 2].toFloatOrNull() ?: 0f, a[i + 3].toFloatOrNull() ?: 0f,
                a[i + 4].toFloatOrNull() ?: 0f, a[i + 5].toFloatOrNull() ?: 0f))
            i += 6
        }
        return out to null
    }

    /** librashader's message in plain words, for the list's description line. */
    fun plainError(message: String): String {
        val m = message.lowercase(Locale.ROOT)
        return when {
            "not in the picked folder" in m || "could not find" in m || "no such file" in m || "not found" in m || "missing" in m ->
                "A file it uses is missing from the folder you picked. Pick the whole shaders_slang folder so the files it shares with other presets come along."
            "isn't in this build" in m -> "This build of Joey64 doesn't have librashader, which draws RetroArch presets."
            "compil" in m || "reflect" in m || "spirv" in m || "glsl" in m || "shader" in m ->
                "One of its shaders uses something librashader can't build on this device."
            else -> "librashader couldn't load it."
        }
    }
}

/**
 * The picked folder, the presets found in it, recent ones, ones that didn't load, and the copies the host reads.
 * All under `files/video/slang/`.
 */
class SlangStore(private val context: Context) {
    val dir = File(context.filesDir, "video/slang")
    private val folderFile = File(dir, "folder.json")
    private val indexFile = File(dir, "index.json")
    private val recentFile = File(dir, "recent.json")
    private val failedFile = File(dir, "failed.json")

    data class Folder(val uri: String, val name: String)

    fun folder(): Folder? = readJson(folderFile)?.let { o ->
        val u = o.optString("uri"); val n = o.optString("name")
        if (u.isEmpty() || n.isEmpty()) null else Folder(u, n)
    }

    /** The picked folder (its read permission kept across restarts). */
    fun setFolder(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { JLog.e(TAG, "can't keep the folder's permission: $e") }
        val name = displayName(uri) ?: "Shaders"
        writeJson(folderFile, JSONObject().put("uri", uri.toString()).put("name", com.joeysretro.joey64.controls.ControlsStore.safe(name)))
        indexFile.delete()
    }

    /** The presets found last time (paths in the folder), or null before a scan. */
    fun index(): List<String>? {
        val f = folder() ?: return null
        val o = readJson(indexFile) ?: return null
        if (o.optString("uri") != f.uri) return null
        val a = o.optJSONArray("presets") ?: return null
        return (0 until a.length()).map { a.getString(it) }
    }

    /** Looks through the whole folder for .slangp files (slow on a big folder: off the main thread). */
    fun scan(): List<String> {
        val f = folder() ?: return emptyList()
        val tree = TreeReader(context, Uri.parse(f.uri))
        val out = ArrayList<String>()
        tree.walk("") { rel -> if (rel.lowercase(Locale.ROOT).endsWith(".slangp")) out.add(rel) }
        out.sortWith(compareBy({ Slang.folder(it).lowercase(Locale.ROOT) }, { it.lowercase(Locale.ROOT) }))
        writeJson(indexFile, JSONObject().put("uri", f.uri).put("presets", JSONArray(out)))
        JLog.i(TAG, "slang: ${out.size} presets in ${f.name}")
        return out
    }

    /** The Shader value for a preset in the picked folder. */
    fun value(rel: String): String = Slang.PREFIX + (folder()?.name ?: "Shaders") + "/" + rel

    /** The path in the picked folder for [value], when it's from that folder. */
    fun relOf(value: String): String? {
        val f = folder() ?: return null
        val v = value.removePrefix(Slang.PREFIX)
        return if (v.startsWith(f.name + "/")) v.removePrefix(f.name + "/") else null
    }

    /** The copy the host reads. */
    fun localFile(value: String): File = File(dir, value.removePrefix(Slang.PREFIX))

    /**
     * Copies the preset and every file it uses from the picked folder (again, so changes there come along).
     * Null when done, else why not (also recorded as [failed]).
     */
    fun import(value: String): String? {
        val rel = relOf(value) ?: return if (localFile(value).isFile) null else "the folder it was in isn't picked any more".also { fail(value, it) }
        val f = folder() ?: return "no folder"
        val tree = TreeReader(context, Uri.parse(f.uri))
        val deps = Slang.deps(rel, { tree.readText(it) }, { tree.exists(it) })
        if (deps.missing.isNotEmpty()) {
            val msg = "not in the picked folder: " + deps.missing.first()
            fail(value, msg)
            return msg
        }
        val root = File(dir, f.name)
        for (p in deps.files) {
            val out = File(root, p)
            if (!tree.copy(p, out)) { val msg = "can't copy $p"; fail(value, msg); return msg }
        }
        JLog.i(TAG, "slang: copied ${deps.files.size} files for $rel")
        return null
    }

    /** Copies it when there's no copy yet (previews). */
    fun ensure(value: String): String? = if (localFile(value).isFile) null else import(value)

    fun recent(): List<String> = readJson(recentFile)?.optJSONArray("recent")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    fun used(value: String) {
        val list = (listOf(value) + recent().filter { it != value }).take(5)
        writeJson(recentFile, JSONObject().put("recent", JSONArray(list)))
    }

    fun failures(): Map<String, String> = readJson(failedFile)?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap()

    fun fail(value: String, message: String) {
        val m = failures().toMutableMap(); m[value] = message
        writeJson(failedFile, JSONObject(m as Map<*, *>))
    }

    fun loaded(value: String) {
        val m = failures()
        if (value in m) writeJson(failedFile, JSONObject((m - value) as Map<*, *>))
    }

    private fun displayName(uri: Uri): String? = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    private fun readJson(f: File): JSONObject? = try { if (f.isFile) JSONObject(f.readText()) else null } catch (e: Exception) { null }

    private fun writeJson(f: File, o: JSONObject) {
        try {
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(o.toString())
            if (!part.renameTo(f)) { f.delete(); part.renameTo(f) }
        } catch (e: Exception) { JLog.e(TAG, "can't write ${f.name}: $e") }
    }

    private companion object { const val TAG = "Joey64Video" }
}

/** Reads a picked folder (a document tree) by paths inside it; folder listings are kept while it's used. */
internal class TreeReader(private val context: Context, private val tree: Uri) {
    private data class Entry(val id: String, val dir: Boolean)
    private val listings = HashMap<String, Map<String, Entry>>()
    private val rootId = DocumentsContract.getTreeDocumentId(tree)

    private fun list(id: String): Map<String, Entry> = listings.getOrPut(id) {
        val out = HashMap<String, Entry>()
        try {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) out[c.getString(1)] = Entry(c.getString(0), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        } catch (e: Exception) { JLog.e("Joey64Video", "can't list a shader folder: $e") }
        out
    }

    private fun find(rel: String): Entry? {
        var cur = Entry(rootId, true)
        for (part in rel.split('/').filter { it.isNotEmpty() }) {
            if (!cur.dir) return null
            cur = list(cur.id)[part] ?: list(cur.id).entries.firstOrNull { it.key.equals(part, ignoreCase = true) }?.value ?: return null
        }
        return cur
    }

    fun exists(rel: String) = find(rel)?.dir == false

    fun readText(rel: String): String? {
        val e = find(rel)?.takeIf { !it.dir } ?: return null
        return try {
            context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.id))?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (ex: Exception) { null }
    }

    fun copy(rel: String, out: File): Boolean {
        val e = find(rel)?.takeIf { !it.dir } ?: return false
        return try {
            out.parentFile?.mkdirs()
            context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.id))?.use { i ->
                out.outputStream().use { o -> i.copyTo(o) }
            } != null
        } catch (ex: Exception) { false }
    }

    /** Every file under [rel], depth first. */
    fun walk(rel: String, onFile: (String) -> Unit) {
        val e = (if (rel.isEmpty()) Entry(rootId, true) else find(rel)) ?: return
        for ((name, child) in list(e.id).entries.sortedBy { it.key.lowercase(Locale.ROOT) }) {
            val p = if (rel.isEmpty()) name else "$rel/$name"
            if (child.dir) walk(p, onFile) else onFile(p)
        }
    }
}
