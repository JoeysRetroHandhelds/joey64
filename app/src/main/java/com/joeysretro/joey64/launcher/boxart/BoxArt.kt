package com.joeysretro.joey64.launcher.boxart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.joeysretro.joey64.BuildConfig
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.data.ArtKind
import com.joeysretro.joey64.launcher.data.ArtLookup
import com.joeysretro.joey64.launcher.data.LibraryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** The MD5 -> No-Intro name table built from libretro-database (assets/boxart/n64-names.tsv, devtools/boxart/build-table.py). */
object BoxArtTable {
    fun parse(text: String): Map<String, String> {
        val m = HashMap<String, String>(2048)
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line[0] == '#') continue
            val tab = line.indexOf('\t')
            if (tab == 32) m[line.substring(0, 32)] = line.substring(33)
        }
        return m
    }

    @Volatile private var table: Map<String, String>? = null

    fun get(context: Context): Map<String, String> =
        table ?: synchronized(this) {
            table ?: parse(context.assets.open("boxart/n64-names.tsv").bufferedReader().use { it.readText() }).also { table = it }
        }
}

/**
 * Box art (plan, Phase 2; ScreenScraper since 2026-11-07, Joey's call, libretro-thumbnails before):
 * covers from [ScreenScraper], downloaded once and kept in `files/boxart/<md5>.jpg`. One request at
 * a time (its limit without a user account). Each game is looked up by its ROM's MD5; when the game
 * found doesn't fit the name (most hacks hash to the original game), by name, so a hack with a cover
 * of its own gets it, else the original game's cover. A game in [BoxArtFixes] gets the cover that
 * names, whatever the lookup would find. Nothing found: "none",
 * looked up again a week later. Busy, closed or out of quota: the rest wait for the next run.
 * Covers from libretro already here stay.
 */
class BoxArt(private val context: Context, private val db: LibraryDb) {

    sealed interface Outcome {
        /** [found] covers downloaded, [none] games with no cover; [busy]: ScreenScraper stopped answering, the rest wait for a later run. */
        data class Done(val found: Int, val none: Int, val failed: Int, val busy: Boolean = false) : Outcome
        /** No connection (or the server didn't answer): nothing changed, try again once online. */
        data object Offline : Outcome
    }

    val dir: File get() = File(context.filesDir, "boxart")

    fun file(md5: String) = File(dir, "$md5.jpg")

    /** A picture the user chose (Details, Change box art), kept apart so Downloaded art can come back. */
    fun customFile(md5: String) = File(File(dir, "custom"), "$md5.jpg")

    /**
     * Keeps the picked image [uri] as [md5]'s own cover: decoded at most [CUSTOM_WIDTH] wide (a
     * phone photo is far bigger than any tile), as a JPEG, written whole or not at all.
     */
    fun saveCustom(md5: String, uri: android.net.Uri) {
        val r = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null; the size is in [bounds].
        (r.openInputStream(uri) ?: throw IOException("can't open $uri")).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IOException("not an image")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= CUSTOM_WIDTH) sample *= 2
        val decoded = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IOException("not an image")
        val bmp = if (decoded.width > CUSTOM_WIDTH) Bitmap.createScaledBitmap(decoded, CUSTOM_WIDTH, decoded.height * CUSTOM_WIDTH / decoded.width, true)
            .also { decoded.recycle() } else decoded
        val to = customFile(md5)
        to.parentFile?.mkdirs()
        val part = File(to.parentFile, to.name + ".part")
        try {
            part.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("can't encode") }
            if (!part.renameTo(to)) throw IOException("can't rename ${part.name}")
        } finally {
            bmp.recycle()
            part.delete()
        }
    }

    /** Looks up and downloads the cover of every library game not looked up yet (or found with none over a week ago). [onCover] after each new one. */
    suspend fun fetch(maxWidth: Int = HIGH_WIDTH, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }, onCover: () -> Unit = {}): Outcome = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        // A game found with no cover before this build was installed, that this build's fixes.tsv lists with a game,
        // is looked up again now, not a week later (2026-11-07: Star Road Deluxe, checked before its line was added).
        val listed = BoxArtFixes.get(context).filterValues { it is BoxArtFixes.Fix.Game }.keys
        val installed = try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (e: Exception) { 0L }
        db.retryNone(listed, installed).takeIf { it > 0 }?.let { JLog.i(TAG, "box art: $it games without a cover are listed in fixes.tsv now, looking them up again") }
        val ours = joeyCovers(now, onCover)
        val todo = db.artToLookUp(now - RETRY_NONE_MS)
        if (todo.isEmpty()) return@withContext Outcome.Done(ours, 0, 0)
        if (!ScreenScraper.usable) {
            JLog.w(TAG, "box art: this build has no ScreenScraper login (local.properties), ${todo.size} games not looked up")
            return@withContext Outcome.Done(0, 0, 0)
        }
        File(dir, "listing.txt").delete() // libretro's cover listing, no longer used
        val table = BoxArtTable.get(context)
        val fixes = BoxArtFixes.get(context)
        dir.mkdirs()
        var found = ours; var none = 0; var failed = 0; var busy = 0
        for ((i, g) in todo.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(i, todo.size)
            // A slow answer or a dropped connection is retried after a wait (2026-11-07: one 30 s timeout
            // ended a 1,216-game run after 44 covers); only when it keeps failing is the network taken as gone.
            var r = lookUp(g, table, fixes, now, maxWidth)
            for (wait in OFFLINE_RETRY_MS) {
                if (r != Result.Offline) break
                JLog.w(TAG, "box art: ${g.fileName}: no answer, trying again in ${wait / 1000} s")
                delay(wait)
                r = lookUp(g, table, fixes, now, maxWidth)
            }
            when (r) {
                Result.Found -> { found++; onCover() }
                Result.None -> none++
                Result.Failed -> failed++
                Result.Busy -> { busy = todo.size - found - none - failed; break }
                Result.Offline -> {
                    JLog.w(TAG, "box art: offline after $found covers")
                    return@withContext if (found == 0) Outcome.Offline else Outcome.Done(found, none, failed)
                }
            }
        }
        JLog.i(TAG, "box art: ${todo.size} to look up, ${found - ours} downloaded ($ours of Joey64's own before), $none without a cover, $failed failed, $busy left for later (busy), " +
            "${SystemClock.elapsedRealtime() - t0} ms")
        Outcome.Done(found, none, failed, busy = busy > 0)
    }

    /**
     * Joey64's own covers ([JoeyCovers]) for the library games they're for, ahead of any lookup: they win over
     * ScreenScraper's, and a changed picture replaces the one kept. A cover the user chose (Custom) or No box art
     * (Hidden) stays. Returns how many were downloaded.
     */
    private fun joeyCovers(now: Long, onCover: () -> Unit): Int {
        val covers = JoeyCovers(context)
        val index = covers.index()
        var n = 0
        for (row in db.artOfGames(index.keys)) {
            if (row.kind == ArtKind.Custom.db || row.kind == ArtKind.Hidden.db) continue
            val want = JoeyCovers.PREFIX + index[row.md5]
            if (row.kind == ArtKind.Downloaded.db && row.cover == want) continue
            try {
                dir.mkdirs()
                keep(covers.picture(row.md5), file(row.md5))
                db.setArt(row.gameId, ArtKind.Downloaded.db, want, now)
                JLog.i(TAG, "cover for ${row.md5}: Joey64's own")
                n++
                onCover()
            } catch (e: IOException) {
                // No change: tried again next run.
                JLog.w(TAG, "box art: Joey64's cover for ${row.md5} not downloaded ($e)")
            }
        }
        return n
    }

    /** A room's game cover (a game not in the library): boxart/rooms/<md5>.jpg. */
    fun roomCoverFile(md5: String) = File(File(dir, "rooms"), "$md5.jpg")

    /**
     * Looks up the cover of a room's game by its [md5] (Joey64's own, fixes.tsv, then ScreenScraper by MD5 and by
     * [title], as for the library), kept as [roomCoverFile]; null when there's none (asked again after a week), it
     * failed, or this build has no ScreenScraper login. Blocking: one ScreenScraper request at a time (the caller's lock).
     */
    fun roomCover(md5: String, title: String, maxWidth: Int): File? {
        val to = roomCoverFile(md5)
        if (to.exists()) return to
        val none = File(to.parentFile, "$md5.none")
        val now = System.currentTimeMillis()
        if (none.exists() && now - none.lastModified() < RETRY_NONE_MS) return null
        return try {
            to.parentFile?.mkdirs()
            val covers = JoeyCovers(context)
            if (covers.index().containsKey(md5)) { keep(covers.picture(md5), to); JLog.i(TAG, "room cover for $md5: Joey64's own"); return to }
            if (!ScreenScraper.usable) return null
            val base = ScreenScraper.base(devBase())
            val noIntro = BoxArtTable.get(context)[md5]
            val fix = BoxArtFixes.get(context)[md5]
            if (fix is BoxArtFixes.Fix.Game && fix.region != null) {
                val got = try { download(ScreenScraper.coverUrl(base, fix.id, fix.region, maxWidth), to); true } catch (e: NotAnImage) { false }
                if (got) { JLog.i(TAG, "room cover for $md5: game ${fix.id} (${fix.region}, listed)"); return to }
            }
            val term = ScreenScraperPick.searchTerm(noIntro ?: title)
            val info = { p: Map<String, String> -> ScreenScraperPick.game(ScreenScraper.jeuInfos(base, mapOf("systemeid" to "${ScreenScraper.N64}") + p))?.takeIf { it.covers.isNotEmpty() } }
            val game = when {
                fix == BoxArtFixes.Fix.None -> null
                fix is BoxArtFixes.Fix.Game -> info(mapOf("gameid" to fix.id))
                else -> {
                    val hashed = info(mapOf("romtype" to "rom", "romnom" to "$title.z64", "md5" to md5))
                    if (hashed != null && hashed.fits(term)) hashed
                    else (if (term.isEmpty()) null else ScreenScraper.jeuRecherche(base, term).firstOrNull()?.let { j ->
                        val hit = ScreenScraperPick.game(j)
                        if (hit == null || !hit.fits(term)) null else info(mapOf("gameid" to hit.id))
                    }) ?: hashed
                }
            }
            val cover = game?.let { ScreenScraperPick.cover(it, noIntro ?: title) }
            if (game == null || cover == null) {
                none.writeText(""); none.setLastModified(now)
                JLog.i(TAG, "room cover for $md5: none")
                null
            } else {
                download(ScreenScraper.mediaUrl(cover.second, maxWidth), to)
                JLog.i(TAG, "room cover for $md5: ${game.name} (${cover.first})")
                to
            }
        } catch (e: ScreenScraper.Busy) {
            JLog.w(TAG, "room cover for $md5: ScreenScraper busy or closed (${e.code})"); null
        } catch (e: IOException) {
            JLog.w(TAG, "room cover for $md5: $e"); null
        }
    }

    private enum class Result { Found, None, Failed, Busy, Offline }

    /** Waits before trying a game again after no answer (timeout, connection refused, no DNS). */
    private val OFFLINE_RETRY_MS = longArrayOf(10_000, 60_000)

    /** ScreenScraper answered with something other than a picture ("NOMEDIA" for a cover it doesn't have). */
    private class NotAnImage(size: Int) : IOException("cover: not an image ($size bytes)")

    private fun lookUp(g: ArtLookup, table: Map<String, String>, fixes: Map<String, BoxArtFixes.Fix>, now: Long, maxWidth: Int): Result { return try {
        val base = ScreenScraper.base(devBase())
        val noIntro = table[g.md5]
        val byHash = {
            val p = mutableMapOf("systemeid" to "${ScreenScraper.N64}", "romtype" to "rom", "romnom" to g.fileName + ".z64", "md5" to g.md5)
            if (g.romSize > 0) p["romtaille"] = "${g.romSize}"
            if (g.crc32.isNotEmpty()) p["crc"] = g.crc32
            ScreenScraperPick.game(ScreenScraper.jeuInfos(base, p))?.takeIf { it.covers.isNotEmpty() }
        }
        val term = ScreenScraperPick.searchTerm(noIntro ?: g.fileName)
        val byName = {
            if (term.isEmpty()) null else ScreenScraper.jeuRecherche(base, term).firstOrNull()?.let { j ->
                // Search answers carry no media list: the game's own lookup has them.
                val hit = ScreenScraperPick.game(j)
                if (hit == null || !hit.fits(term)) null
                else ScreenScraperPick.game(ScreenScraper.jeuInfos(base, mapOf("systemeid" to "${ScreenScraper.N64}", "gameid" to hit.id)))
                    ?.takeIf { it.covers.isNotEmpty() }
            }
        }
        // The MD5's game counts when its name fits: hacks (some are in the table too, Sealed Palace as a
        // "Pirate" OoT) mostly hash to the original game, whose cover is kept for when a name search finds none.
        val fix = fixes[g.md5]
        // A known game with its cover's region: straight to the cover, no lookup. Not there (NOMEDIA, or
        // anything that isn't an image): the lookups below, as for any game.
        if (fix is BoxArtFixes.Fix.Game && fix.region != null) {
            val got = try { download(ScreenScraper.coverUrl(base, fix.id, fix.region, maxWidth), file(g.md5)); true }
                catch (e: NotAnImage) { JLog.w(TAG, "box art: ${g.fileName}: the listed cover isn't there, looking it up"); false }
            if (got) {
                db.setArt(g.gameId, ArtKind.Downloaded.db, "screenscraper:${fix.id}:${fix.region}", now)
                JLog.i(TAG, "cover for ${g.fileName}: game ${fix.id} (${fix.region}, listed)")
                return Result.Found
            }
        }
        val hashed = if (fix == null) byHash() else null
        val (game, how) = when {
            fix == BoxArtFixes.Fix.None -> null to "fix"
            fix is BoxArtFixes.Fix.Game -> ScreenScraperPick.game(ScreenScraper.jeuInfos(base, mapOf("systemeid" to "${ScreenScraper.N64}", "gameid" to fix.id))) to "fix"
            hashed != null && hashed.fits(term) -> hashed to "MD5"
            else -> byName()?.let { it to "name" } ?: hashed?.let { it to "MD5, the original game" } ?: (null to "")
        }
        val cover = game?.let { ScreenScraperPick.cover(it, noIntro ?: g.fileName) }
        if (game == null || cover == null) {
            db.setArt(g.gameId, "none", "screenscraper", now)
            Result.None
        } else {
            download(ScreenScraper.mediaUrl(cover.second, maxWidth), file(g.md5))
            db.setArt(g.gameId, ArtKind.Downloaded.db, "screenscraper:${game.id}:${cover.first}", now)
            JLog.i(TAG, "cover for ${g.fileName}: ${game.name} (${cover.first}, by $how)")
            Result.Found
        }
    } catch (e: ScreenScraper.Busy) {
        JLog.w(TAG, "box art: ScreenScraper busy or closed (${e.code}), stopping until next time")
        Result.Busy
    } catch (e: IOException) {
        // No row either way: looked up again next time.
        if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException) Result.Offline
        else { JLog.w(TAG, "box art: ${g.fileName}: $e"); Result.Failed }
    }
    }

    /** Downloads [url] and keeps it as a JPEG, written whole or not at all. */
    private fun download(url: String, to: File) = keep(ScreenScraper.get(url, "cover"), to)

    /** Keeps the picture [bytes] as a JPEG at [to], written whole or not at all. */
    private fun keep(bytes: ByteArray, to: File) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw NotAnImage(bytes.size)
        val part = File(to.parentFile, to.name + ".part")
        try {
            part.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("can't encode") }
            if (!part.renameTo(to)) throw IOException("can't rename ${part.name}")
        } finally {
            bmp.recycle()
            part.delete()
        }
    }

    /** Debug builds read a stand-in API address from `files/dev_boxart_base` (a feed on the PC, for testing offline and failures). */
    private fun devBase(): String? {
        if (!BuildConfig.DEBUG) return null
        return try { File(context.filesDir, "dev_boxart_base").readText().trim() } catch (e: IOException) { null }
    }

    companion object {
        /** Settings › Library › Box art quality: High (the default) and Low, covers' widths in pixels. */
        const val HIGH_WIDTH = 640
        const val LOW_WIDTH = 320
        private const val TAG = "Joey64BoxArt"
        private const val RETRY_NONE_MS = 7 * 24 * 3600_000L
        private const val CUSTOM_WIDTH = 1024
    }
}
