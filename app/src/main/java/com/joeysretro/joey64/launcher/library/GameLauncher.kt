package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.storage.withChannel
import com.joeysretro.joey64.JLog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.joeysretro.joey64.MainActivity
import com.joeysretro.joey64.launcher.data.GameFile
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.data.LibraryGame
import com.joeysretro.joey64.launcher.storage.FileCopy.readNBytesCompat
import com.joeysretro.joey64.launcher.storage.RomFolders
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Starts a game from the library (plan, Phase 1: Launch). The game screen ([MainActivity]) takes a
 * ROM the way a frontend hands one over, as a VIEW intent, so the library and ES-DE-style
 * frontends start games the same way. A `.z64`/`.v64`/`.n64` goes as its document in the picked
 * folder. A `.zip` or `.7z` is unpacked to the cache first (the core reads no archives), named after the
 * zip as RetroArch does, so its save gets the same name; the last [KEEP] unpacked games stay, so
 * playing one again starts at once.
 */
class GameLauncher(private val context: Context, private val db: LibraryDb) {

    /**
     * The intent that starts [game], or null when none of its files could be opened. Runs off the
     * main thread (unpacking reads the whole zip); the screen starts it from its activity.
     */
    fun intentFor(game: LibraryGame, loadState: String? = null): Intent? {
        val files = readableFiles(game)
        for (f in files) {
            val uri = try {
                if (ArchiveReader.isZip(f.name) || ArchiveReader.is7z(f.name)) unpacked(game, f) else f.uri.toUri()
            } catch (e: IOException) {
                JLog.w(TAG, "can't open ${f.name}" + "\n" + Log.getStackTraceString(e)); null
            } catch (e: SecurityException) {
                JLog.w(TAG, "can't open ${f.name}" + "\n" + Log.getStackTraceString(e)); null
            } ?: continue
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            loadState?.let { intent.putExtra(MainActivity.EXTRA_LOAD_STATE, it) }
            if (isDisk(f, uri)) {
                intent.putExtra(EXTRA_IS_DISK, true)
                cartCode(game)?.let { code ->
                    val cart = cartFor(game, code)
                    if (cart != null) intent.putExtra(MainActivity.EXTRA_DD_CART, cart.toString()) else intent.putExtra(EXTRA_NEEDS_CART, code)
                }
            }
            // For recently played (JoeyOS reads it): the file in the ROMs folder, not the unpacked copy, and the title.
            intent.putExtra(MainActivity.EXTRA_SOURCE_URI, f.uri).putExtra(MainActivity.EXTRA_SOURCE_NAME, f.name)
                .putExtra(MainActivity.EXTRA_TITLE, game.title).putExtra(MainActivity.EXTRA_MD5, game.md5)
            JLog.i(TAG, "playing ${game.title} from ${f.name}" + (loadState?.let { ", loading the $it state" } ?: ""))
            return intent
        }
        return null
    }

    /** [f] (or the file unpacked from it, [uri]) is a 64DD disk. */
    private fun isDisk(f: GameFile, uri: Uri) =
        RomReader.isDiskName(f.name) || RomReader.isDiskName(uri.lastPathSegment ?: "")

    /**
     * The cartridge [disk] plays with, [code] from [cartCode]: the newest-played game in the library with that header
     * game code, as a content URI (unpacked when it's in an archive). Null: it isn't in the library (the intent then
     * carries [EXTRA_NEEDS_CART] and the library asks for it, Joey 2026-11-07).
     */
    private fun cartFor(disk: LibraryGame, code: String): Uri? {
        for (cart in db.gamesWithCode(code)) {
            for (f in readableFiles(cart)) {
                val uri = try {
                    if (ArchiveReader.isZip(f.name) || ArchiveReader.is7z(f.name)) unpacked(cart, f) else f.uri.toUri()
                } catch (e: Exception) { JLog.w(TAG, "can't open ${f.name}: $e"); null } ?: continue
                JLog.i(TAG, "64DD: ${disk.title} plays with ${cart.title}")
                return uri
            }
        }
        JLog.w(TAG, "64DD: ${disk.title} needs the $code cartridge, which isn't in the library")
        return null
    }

    private fun readableFiles(game: LibraryGame) =
        db.filesOf(game.id).filter { RomFolders.stillReadable(context, it.treeUri.toUri()) }

    /**
     * The name [game]'s save and states go by when [intentFor] starts it: the file it would start,
     * without extension (a zip's own name, as RetroArch names them). Null when none can be opened.
     */
    fun saveNameFor(game: LibraryGame): String? =
        readableFiles(game).firstOrNull()?.let { com.joeysretro.joey64.states.GameStates.saveNameOf(it.name) }

    /** The ROM inside [f] (a zip or 7z), unpacked to `cache/launch/<md5>/` under the archive's name: reused only for the same ROM under the same name. */
    private fun unpacked(game: LibraryGame, f: GameFile): Uri? {
        val root = File(context.cacheDir, LAUNCH_DIR)
        val dir = File(root, game.md5)
        // Reused only under this archive's name: the same ROM in two archives named apart has two saves.
        val title = ScanPlanner.titleOf(f.name)
        dir.listFiles()?.firstOrNull { it.isFile && !it.name.endsWith(PART) && it.nameWithoutExtension == title }?.let { done ->
            dir.setLastModified(System.currentTimeMillis())
            return uriOf(done)
        }
        // The first entry that starts like an N64 ROM, as the scan's ArchiveReader picks it.
        val done = if (ArchiveReader.is7z(f.name)) {
            context.contentResolver.withChannel(f.uri.toUri()) { ch ->
                ArchiveReader.firstIn7z(ch) { name, input -> unpackEntry(name, input, dir, f) }
            }
        } else {
            val input = context.contentResolver.openInputStream(f.uri.toUri()) ?: return null
            var out: File? = null
            ZipInputStream(input.buffered(256 * 1024)).use { zip ->
                while (out == null) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory || ArchiveReader.skip(e.name)) continue
                    out = unpackEntry(e.name, zip, dir, f)
                }
            }
            out
        } ?: return null
        trim(root)
        return uriOf(done)
    }

    /**
     * Writes [input] to [dir] when it starts like an N64 ROM, named after the archive [f] (so its save
     * is named as RetroArch names it) with the entry's extension. Null when it isn't a ROM.
     */
    private fun unpackEntry(entry: String, input: java.io.InputStream, dir: File, f: GameFile): File? {
        val head = ByteArray(4)
        val n = input.readNBytesCompat(head)
        // A 64DD disk by its name (disks have no common first word); a ROM by its first word.
        if (!RomReader.isDiskName(entry.substringAfterLast('/')) && (n < 4 || RomByteOrder.of(head) == null)) return null
        dir.mkdirs()
        val ext = entry.substringAfterLast('.', "z64").lowercase()
        val target = File(dir, ScanPlanner.titleOf(f.name) + "." + ext)
        val part = File(dir, target.name + PART)
        part.outputStream().use { o -> o.write(head, 0, n.coerceAtLeast(0)); input.copyTo(o, 256 * 1024) }
        if (!part.renameTo(target)) { part.delete(); throw IOException("can't unpack ${f.name}") }
        return target
    }

    /** Keeps the [KEEP] most recently played unpacked games. */
    private fun trim(root: File) {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        for (old in dirs.drop(KEEP)) old.deleteRecursively()
    }

    private fun uriOf(file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".launch", file)

    companion object {
        const val LAUNCH_DIR = "launch"
        const val KEEP = 3
        /** On the intent: the game is a 64DD disk (the library checks for the BIOS before starting it). */
        const val EXTRA_IS_DISK = "com.joeysretro.joey64.launcher.IS_DISK"
        /** On the intent: the disk plays with the cartridge of this header game code, which isn't in the library (not started then). */
        const val EXTRA_NEEDS_CART = "com.joeysretro.joey64.launcher.NEEDS_CART"
        /**
         * 64DD disks that play with a cartridge in (the cartridge boots and reads the disk), by the disk's MD5: the
         * cartridge's header game code. F-Zero X - Expansion Kit (Japan): F-Zero X (Japan); Dezaemon DD (Japan)
         * (Proto): Dezaemon 3D (Japan).
         */
        val DD_CARTS = mapOf(
            "cb2fb00c3921245ae04bb38ba01abe92" to "CFZJ",
            "40aa8ec52c5e025d6c6ad94cebc2218f" to "CDZJ",
        )
        /** The same by title, for other dumps of those disks. */
        val DD_CART_TITLES = mapOf("F-Zero X - Expansion Kit" to "CFZJ", "Dezaemon DD" to "CDZJ")
        /** The cartridge a disk needs, by header game code, and the disk, as the missing-cartridge popup names them. */
        val CART_NAMES = mapOf("CFZJ" to "F-Zero X (Japan)", "CDZJ" to "Dezaemon 3D (Japan)")
        val DISK_NAMES = mapOf("CFZJ" to "F-Zero X Expansion Kit", "CDZJ" to "Dezaemon DD")

        /** The header game code of the cartridge [disk] plays with ([DD_CARTS], else by title), or null when it needs none. */
        fun cartCode(disk: LibraryGame): String? =
            DD_CARTS[disk.md5] ?: DD_CART_TITLES.entries.firstOrNull { disk.title.startsWith(it.key) }?.value
        private const val PART = ".part"
        private const val TAG = "Joey64Launch"
    }
}
