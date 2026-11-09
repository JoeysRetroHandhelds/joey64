package com.joeysretro.joey64.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.joeysretro.joey64.JLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * RetroArch overlay packs (approved mockup 2026-11-08, scratchpad build_ovpack_mock.py / ovpack_mock.png, "Approve as
 * drawn"): Sides of the picture › RetroArch overlay. A pack's overlay is a .cfg ("overlays = 1", "overlay0_overlay =
 * bezel.png", "overlay0_full_screen = true", "overlay0_descs = 0") naming a screen-sized image with a see-through hole
 * for the game. Joey64 shows only the image: .cfg files with touch buttons (descs, RetroArch's on-screen gamepads) are
 * listed greyed. The host draws the image over the game (look.glsl sides mode 4), the game in the hole Joey64 finds in
 * the image ([hole]) or where Place the game puts it.
 *
 * Stored: Sides = "retroarch", [VideoKey.SidesRa] = "raov:<folder name>/<path>.cfg", or "match:<folder name>" (Match each
 * game: each game the overlay named after it, No-Intro style), [VideoKey.SidesRaOther] what games without one get
 * (none, blur, frame). This object is the pure part (tested by RaOverlayTest).
 */
object RaOverlay {
    const val PREFIX = "raov:"
    const val MATCH = "match:"

    fun isOverlay(value: String) = value.startsWith(PREFIX)
    fun isMatch(value: String) = value.startsWith(MATCH)

    /** "Retro TV" for "raov:overlays/borders/Retro TV.cfg". */
    fun name(value: String): String = value.removePrefix(PREFIX).substringAfterLast('/').substringBeforeLast('.')

    /** One overlay of a .cfg: its image (a path inside the picked folder) and name. */
    data class Image(val path: String, val name: String)

    /** A parsed .cfg: its images in order, and whether any overlay has touch buttons. */
    data class Cfg(val images: List<Image>, val touch: Boolean)

    /** RetroArch's config lines: key = value, "quoted" or not; # starts a comment. Lines without '=' are skipped. */
    fun keys(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=').takeIf { it > 0 } ?: continue
            val k = line.substring(0, eq).trim()
            var v = line.substring(eq + 1).trim()
            v = if (v.startsWith("\"")) v.substring(1).substringBefore('"') else v.substringBefore('#').trim()
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    /** The overlays in [text], a .cfg at [cfgRel] in the picked folder (its images are relative to the .cfg's folder). */
    fun parse(text: String, cfgRel: String): Cfg {
        val k = keys(text)
        val n = k["overlays"]?.toIntOrNull()?.coerceIn(0, 64) ?: 0
        val dir = Slang.folder(cfgRel)
        val images = ArrayList<Image>()
        var touch = false
        for (i in 0 until n) {
            if ((k["overlay${i}_descs"]?.toIntOrNull() ?: 0) > 0) touch = true
            val img = k["overlay${i}_overlay"]?.takeIf { it.isNotEmpty() } ?: continue
            val path = Slang.resolve(dir, img) ?: continue
            images.add(Image(path, k["overlay${i}_name"] ?: ""))
        }
        return Cfg(images, touch)
    }

    /** The image for the screen: one named for portrait on a portrait screen (as RetroArch tells them), else the first other one. */
    fun pick(cfg: Cfg, portrait: Boolean): Image? {
        val (p, l) = cfg.images.partition { "portrait" in it.name.lowercase(Locale.ROOT) }
        return (if (portrait) p.firstOrNull() ?: l.firstOrNull() else l.firstOrNull() ?: p.firstOrNull())
    }

    /** A hole in the image, in ten-thousandths of its width and height: left, top, right, bottom. */
    data class Hole(val l: Int, val t: Int, val r: Int, val b: Int) {
        override fun toString() = "$l,$t,$r,$b"
        companion object {
            fun of(s: String): Hole? = s.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 4 }?.let { Hole(it[0], it[1], it[2], it[3]) }
        }
    }

    /**
     * The see-through hole the game goes in: the pixels clear (alpha under 64) connected to the image's centre, as a
     * box. Null when the centre isn't clear, or the clear part is under 5% of the image or reaches all four edges
     * (no frame around it). [alpha] is w x h, one value 0..255 per pixel.
     */
    fun hole(alpha: IntArray, w: Int, h: Int): Hole? {
        if (w < 4 || h < 4) return null
        fun clear(x: Int, y: Int) = alpha[y * w + x] < 64
        val cx = w / 2; val cy = h / 2
        if (!clear(cx, cy)) return null
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        stack[sp++] = cy * w + cx; seen[cy * w + cx] = true
        var l = cx; var r = cx; var t = cy; var b = cy; var count = 0
        while (sp > 0) {
            val p = stack[--sp]
            val x = p % w; val y = p / w
            count++
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            for (q in intArrayOf(if (x > 0) p - 1 else -1, if (x < w - 1) p + 1 else -1, if (y > 0) p - w else -1, if (y < h - 1) p + w else -1)) {
                if (q >= 0 && !seen[q] && alpha[q] < 64) { seen[q] = true; stack[sp++] = q }
            }
        }
        if (count * 20L < w.toLong() * h) return null
        if (l == 0 && t == 0 && r == w - 1 && b == h - 1) return null
        return Hole((l * 10000L / w).toInt(), (t * 10000L / h).toInt(), ((r + 1) * 10000L / w).toInt(), ((b + 1) * 10000L / h).toInt())
    }

    /**
     * Where the game goes on a [sw] x [sh] screen for [hole] in an [iw] x [ih] image the host shows covering the
     * screen (shape kept, centred, cut at the edges): the game at [aw]:[ah] as big as fits in the hole, centred in it.
     * As Place the game stores it: left, top, width in tenths of a percent of the screen.
     */
    fun placeIn(hole: Hole, iw: Int, ih: Int, sw: Int, sh: Int, aw: Int = 4, ah: Int = 3): Triple<Int, Int, Int> {
        val s = maxOf(sw.toDouble() / iw, sh.toDouble() / ih)
        val ox = (sw - iw * s) / 2; val oy = (sh - ih * s) / 2
        val hl = ox + hole.l / 10000.0 * iw * s; val ht = oy + hole.t / 10000.0 * ih * s
        val hw = (hole.r - hole.l) / 10000.0 * iw * s; val hh = (hole.b - hole.t) / 10000.0 * ih * s
        var gw = hw; var gh = gw * ah / aw
        if (gh > hh) { gh = hh; gw = gh * aw / ah }
        val gx = hl + (hw - gw) / 2; val gy = ht + (hh - gh) / 2
        return Triple((gx * 1000 / sw).toInt(), (gy * 1000 / sh).toInt(), (gw * 1000 / sw).toInt().coerceAtLeast(1))
    }

    /** The name a game and an overlay are matched by: lower case, no extension. */
    fun key(name: String): String = name.substringAfterLast('/').let { n ->
        if ('.' in n && n.substringAfterLast('.').length in 2..4 && !n.substringAfterLast('.').contains(' ')) n.substringBeforeLast('.') else n
    }.trim().lowercase(Locale.ROOT)

    /** The title without its tags: "super mario 64" for "Super Mario 64 (USA) [!]". */
    fun bare(name: String): String = key(name).substringBefore(" (").substringBefore(" [").trim()

    /**
     * The overlay (a path among [cfgs]) named after a game called any of [names] (its file name, its title): the
     * same name first, then the same name without tags ("(USA)", "[!]"), so a Europe copy gets the USA one's.
     */
    fun match(names: List<String>, cfgs: List<String>): String? {
        val keys = names.map { key(it) }.filter { it.isNotEmpty() }
        cfgs.firstOrNull { key(it) in keys }?.let { return it }
        val bares = keys.map { bare(it) }.filter { it.isNotEmpty() }
        return cfgs.firstOrNull { bare(it) in bares }
    }
}

/**
 * The picked overlays folder, the overlays found in it, recent ones, ones that didn't load, found holes and Place the
 * game for each, and the copies the host reads. All under `files/video/overlays/`.
 */
class RaOverlayStore(private val context: Context) {
    val dir = File(context.filesDir, "video/overlays")
    private val folderFile = File(dir, "folder.json")
    private val indexFile = File(dir, "index.json")
    private val recentFile = File(dir, "recent.json")
    private val failedFile = File(dir, "failed.json")
    private val holesFile = File(dir, "holes.json")
    private val placesFile = File(dir, "places.json")

    data class Folder(val uri: String, val name: String)
    /** An overlay .cfg in the folder: its path, and whether it has touch buttons (listed greyed). */
    data class Entry(val rel: String, val touch: Boolean)

    fun folder(): Folder? = readJson(folderFile)?.let { o ->
        val u = o.optString("uri"); val n = o.optString("name")
        if (u.isEmpty() || n.isEmpty()) null else Folder(u, n)
    }

    /** The picked folder (its read permission kept across restarts). */
    fun setFolder(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { JLog.e(TAG, "can't keep the folder's permission: $e") }
        val name = displayName(uri) ?: "Overlays"
        writeJson(folderFile, JSONObject().put("uri", uri.toString()).put("name", com.joeysretro.joey64.controls.ControlsStore.safe(name)))
        indexFile.delete()
    }

    /** The overlays found last time, or null before a scan. */
    fun index(): List<Entry>? {
        val f = folder() ?: return null
        val o = readJson(indexFile) ?: return null
        if (o.optString("uri") != f.uri) return null
        val a = o.optJSONArray("overlays") ?: return null
        return (0 until a.length()).map { a.getJSONObject(it).let { e -> Entry(e.getString("rel"), e.optBoolean("touch")) } }
    }

    /** Reads every .cfg in the folder (slow on a big folder: off the main thread). */
    fun scan(): List<Entry> {
        val f = folder() ?: return emptyList()
        val tree = TreeReader(context, Uri.parse(f.uri))
        val out = ArrayList<Entry>()
        tree.walk("") { rel ->
            if (!rel.lowercase(Locale.ROOT).endsWith(".cfg")) return@walk
            val cfg = tree.readText(rel)?.let { RaOverlay.parse(it, rel) } ?: return@walk
            if (cfg.images.isNotEmpty() || cfg.touch) out.add(Entry(rel, cfg.touch))
        }
        out.sortWith(compareBy({ Slang.folder(it.rel).lowercase(Locale.ROOT) }, { it.rel.lowercase(Locale.ROOT) }))
        writeJson(indexFile, JSONObject().put("uri", f.uri).put("overlays",
            JSONArray(out.map { JSONObject().put("rel", it.rel).put("touch", it.touch) })))
        JLog.i(TAG, "overlays: ${out.size} in ${f.name}")
        return out
    }

    fun value(rel: String): String = RaOverlay.PREFIX + (folder()?.name ?: "Overlays") + "/" + rel
    fun matchValue(): String = RaOverlay.MATCH + (folder()?.name ?: "Overlays")

    fun relOf(value: String): String? {
        val f = folder() ?: return null
        val v = value.removePrefix(RaOverlay.PREFIX)
        return if (v.startsWith(f.name + "/")) v.removePrefix(f.name + "/") else null
    }

    /** The copy of the .cfg the host's image is read from. */
    fun localCfg(value: String): File = File(dir, value.removePrefix(RaOverlay.PREFIX))

    /** The overlays in the folder named like games (Match each game): for [names] of one game, its overlay's value. */
    fun matchFor(names: List<String>): String? {
        val list = index()?.filter { !it.touch }?.map { it.rel } ?: return null
        return RaOverlay.match(names, list)?.let { value(it) }
    }

    /** True when some of the folder's overlays are named like games in [games] (each a game's names). */
    fun matchCount(games: List<List<String>>): Int {
        val list = index()?.filter { !it.touch }?.map { it.rel } ?: return 0
        return games.count { RaOverlay.match(it, list) != null }
    }

    /**
     * Copies the .cfg and its images from the picked folder (again, so changes there come along). Null when done,
     * else why not (also recorded as [fail]).
     */
    fun import(value: String): String? {
        val rel = relOf(value) ?: return if (localCfg(value).isFile) null else "the folder it was in isn't picked any more".also { fail(value, it) }
        val f = folder() ?: return "no folder"
        val tree = TreeReader(context, Uri.parse(f.uri))
        val text = tree.readText(rel) ?: return "can't read $rel".also { fail(value, it) }
        val cfg = RaOverlay.parse(text, rel)
        if (cfg.images.isEmpty()) return "it names no image".also { fail(value, it) }
        val root = File(dir, f.name)
        for (img in cfg.images) {
            if (!tree.exists(img.path)) return "its image ${img.path} isn't in the folder".also { fail(value, it) }
            if (!tree.copy(img.path, File(root, img.path))) return "can't copy ${img.path}".also { fail(value, it) }
        }
        if (!tree.copy(rel, File(root, rel))) return "can't copy $rel".also { fail(value, it) }
        loaded(value)
        return null
    }

    fun ensure(value: String): String? = if (localCfg(value).isFile) null else import(value)

    /** The copied image for the screen's orientation, or null. */
    fun imageFile(value: String, portrait: Boolean): File? {
        val cfgFile = localCfg(value)
        val rel = value.removePrefix(RaOverlay.PREFIX).substringAfter('/')
        val cfg = try { RaOverlay.parse(cfgFile.readText(), rel) } catch (e: Exception) { return null }
        val img = RaOverlay.pick(cfg, portrait) ?: return null
        val root = File(dir, value.removePrefix(RaOverlay.PREFIX).substringBefore('/'))
        return File(root, img.path).takeIf { it.isFile }
    }

    /** The hole in [image] (found once, kept by the image's size and date), and the image's size. */
    fun holeOf(image: File): Triple<RaOverlay.Hole?, Int, Int>? {
        val key = "${image.path}:${image.length()}:${image.lastModified()}"
        val o = readJson(holesFile) ?: JSONObject()
        o.optJSONObject(key)?.let { h -> return Triple(RaOverlay.Hole.of(h.optString("hole")), h.optInt("w"), h.optInt("h")) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
        val bmp = BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val alpha = IntArray(px.size) { (px[it] ushr 24) and 255 }
        val hole = RaOverlay.hole(alpha, bmp.width, bmp.height)
        o.put(key, JSONObject().put("hole", hole?.toString() ?: "").put("w", bounds.outWidth).put("h", bounds.outHeight))
        writeJson(holesFile, o)
        JLog.i(TAG, "overlays: hole in ${image.name}: ${hole ?: "none"}")
        return Triple(hole, bounds.outWidth, bounds.outHeight)
    }

    /** Place the game for an overlay (tenths of a percent of the screen), when the user moved it. */
    fun place(value: String): Triple<Int, Int, Int>? = readJson(placesFile)?.optString(value)?.let { s ->
        s.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 3 && it[2] > 0 }?.let { Triple(it[0], it[1], it[2]) }
    }

    /** Sets (or with null, forgets: back to what was found) Place the game for an overlay. */
    fun setPlace(value: String, p: Triple<Int, Int, Int>?) {
        val o = readJson(placesFile) ?: JSONObject()
        if (p == null) o.remove(value) else o.put(value, "${p.first},${p.second},${p.third}")
        writeJson(placesFile, o)
    }

    fun recent(): List<String> = readJson(recentFile)?.optJSONArray("recent")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    fun used(value: String) {
        val list = (listOf(value) + recent().filter { it != value }).take(5)
        writeJson(recentFile, JSONObject().put("recent", JSONArray(list)))
    }

    fun failures(): Map<String, String> = readJson(failedFile)?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap()

    fun fail(value: String, message: String) {
        JLog.e(TAG, "overlay $value didn't load: $message")
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
