package com.joeysretro.joey64.launcher.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.library.ArchiveReader
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Settings › Library › 64DD BIOS (approved 2026-11-07): the files the user picks (plain, or inside a .zip or .7z, any
 * of the three byte orders) are matched by the MD5 of their big-endian bytes and the ones Joey64 knows are saved in
 * the data folder's `bios/` under the names the core looks for ([BiosSync], which copies them where the core reads
 * them before a disk starts). Anything else (the Drive Controller .bin files, font.ttf) is left out.
 */
object BiosImport {
    private const val TAG = "Joey64Bios"
    /** A 64DD IPL is 4 MiB; anything much bigger isn't one (and isn't read whole). */
    private const val MAX_SIZE = 8L * 1024 * 1024

    /** A known IPL: [label] for the row and popup, [file] its name in bios/, [rank] which wins the same name (higher). */
    data class Ipl(val md5: String, val label: String, val file: String, val rank: Int)

    val KNOWN = listOf(
        Ipl("8d3d9f294b6e174bc7b1d2fd1c727530", "Japan v1.2", "IPL_JPN.n64", 3),
        Ipl("7630650c7fc16e199911269647186e79", "Japan v1.1 (beta)", "IPL_JPN.n64", 2),
        Ipl("94dba2d79615deed567aa85f4cf74162", "Japan v1.0 (beta)", "IPL_JPN.n64", 1),
        Ipl("37c36e4286d36892a9fc70eafe4104be", "USA (prototype)", "IPL_USA.n64", 1),
        Ipl("aad37b1492886b892f1821f37fd3ae34", "Development", "IPL_DEV.n64", 1),
    )
    private val BY_MD5 = KNOWN.associateBy { it.md5 }

    /** An IPL's first word in its three byte orders (z64 80 27 07 40). */
    private val Z64 = byteArrayOf(0x80.toByte(), 0x27, 0x07, 0x40)
    private val V64 = byteArrayOf(0x27, 0x80.toByte(), 0x40, 0x07)
    private val N64 = byteArrayOf(0x40, 0x07, 0x27, 0x80.toByte())

    private fun starts(b: ByteArray, m: ByteArray) = b.size >= 4 && (0 until 4).all { b[it] == m[it] }

    /** [bytes] in big-endian (.z64) order, or null when they don't start like an IPL. */
    fun toBigEndian(bytes: ByteArray): ByteArray? = when {
        starts(bytes, Z64) -> bytes
        starts(bytes, V64) -> bytes.copyOf().also { b -> var i = 0; while (i + 1 < b.size) { val t = b[i]; b[i] = b[i + 1]; b[i + 1] = t; i += 2 } }
        starts(bytes, N64) -> bytes.copyOf().also { b ->
            var i = 0
            while (i + 3 < b.size) { val a = b[i]; val c = b[i + 1]; b[i] = b[i + 3]; b[i + 1] = b[i + 2]; b[i + 2] = c; b[i + 3] = a; i += 4 }
        }
        else -> null
    }

    fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Which IPL [bytes] (any byte order) are, with their big-endian bytes; null when Joey64 doesn't know them. */
    fun identify(bytes: ByteArray): Pair<Ipl, ByteArray>? {
        val z64 = toBigEndian(bytes) ?: return null
        return BY_MD5[md5(z64)]?.let { it to z64 }
    }

    /**
     * Which of [found] to write, per file name: the highest rank, and only over a file already there of a lower rank
     * ([have]: file name -> the IPL there now). A beta never replaces Japan v1.2.
     */
    fun toWrite(found: List<Ipl>, have: Map<String, Ipl?>): List<Ipl> =
        found.groupBy { it.file }.mapNotNull { (file, ipls) ->
            val best = ipls.maxBy { it.rank }
            val there = have[file]
            if (there != null && there.rank >= best.rank) null else best
        }

    /** What bios/ holds now: file name -> the IPL it is (null for a file Joey64 doesn't know). Reads up to 12 MB. */
    fun installed(space: FileSpace): Map<String, Ipl?> = buildMap {
        for (name in KNOWN.map { it.file }.distinct()) {
            val bytes = try { space.openRead(listOf(BiosSync.DIR, name))?.use { it.readBytes() } } catch (e: Exception) { null } ?: continue
            put(name, BY_MD5[md5(bytes)])
        }
    }

    fun installed(context: Context) = installed(DataFolders.current(context).space)

    /** The row's value: the Japan IPL there (the one retail disks need), else any other, "Set" for a file Joey64 doesn't know, else null (Not set). */
    fun label(have: Map<String, Ipl?>): String? =
        (have["IPL_JPN.n64"] ?: KNOWN.map { it.file }.distinct().firstNotNullOfOrNull { have[it] })?.label
            ?: if (have.isNotEmpty()) "Set" else null

    /** True when a disk can start: an IPL is in bios/ (the core picks it by the disk's region). */
    fun ready(context: Context): Boolean {
        val space = DataFolders.current(context).space
        return BiosSync.IPL_NAMES.any { try { space.exists(listOf(BiosSync.DIR, it)) } catch (e: Exception) { false } }
    }

    /** Files bigger than this in a picked folder aren't read (an IPL's zip is 3.4 MB; a disk's is tens of MB). */
    private const val MAX_FILE = 16L * 1024 * 1024

    /**
     * The folder the user picked (Joey 2026-11-07: pick a folder, Joey64 searches it): every file in it and in its
     * folders two levels down, up to [MAX_FILE], goes through [import]. The folder's grant isn't kept: it's read now.
     */
    fun importFolder(context: Context, tree: Uri): List<Ipl> {
        val space = TreeSpace(context.contentResolver, tree)
        val files = space.walk(maxDepth = 3).filter { (_, doc) -> doc.second.size in 1..MAX_FILE }.map { (_, doc) -> space.documentUri(doc.first) }
        JLog.i(TAG, "looking at ${files.size} files in the picked folder")
        return import(context, files)
    }

    /**
     * Reads every picked file (each entry of an archive), saves the IPLs it knows in bios/ and returns the ones found,
     * best first (empty: none). Blocking: off the main thread.
     */
    fun import(context: Context, uris: List<Uri>): List<Ipl> {
        val r = context.contentResolver
        val found = ArrayList<Pair<Ipl, ByteArray>>()
        for (uri in uris) {
            val name = try { r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } } catch (e: Exception) { null } ?: ""
            try {
                if (ArchiveReader.is7z(name)) {
                    r.withChannel(uri) { ch -> ArchiveReader.firstIn7z(ch) { _, input -> take(input, found); null } }
                } else {
                    r.openInputStream(uri)?.use { input ->
                        if (ArchiveReader.isZip(name)) {
                            val zip = ZipInputStream(input.buffered())
                            while (true) {
                                val e = zip.nextEntry ?: break
                                if (!e.isDirectory && !ArchiveReader.skip(e.name)) take(zip, found)
                            }
                        } else take(input, found)
                    }
                }
            } catch (e: Exception) {
                JLog.w(TAG, "can't read a picked file: $e")
            }
        }
        val space = DataFolders.current(context).space
        val write = toWrite(found.map { it.first }, installed(space))
        for (ipl in write) {
            val bytes = found.first { it.first == ipl }.second
            val part = listOf(BiosSync.DIR, ipl.file + ".part")
            space.openWrite(part).use { it.write(bytes) }
            space.delete(listOf(BiosSync.DIR, ipl.file))
            if (!space.rename(part, ipl.file)) throw java.io.IOException("can't save ${ipl.file}")
            JLog.i(TAG, "${ipl.label} saved as ${BiosSync.DIR}/${ipl.file}")
        }
        return found.map { it.first }.distinct().sortedByDescending { it.rank }
    }

    /** Adds [input] to [found] when it is a known IPL. Reads at most [MAX_SIZE]. */
    private fun take(input: InputStream, found: MutableList<Pair<Ipl, ByteArray>>) {
        val head = ByteArray(4)
        var n = 0
        while (n < 4) { val k = input.read(head, n, 4 - n); if (k < 0) break; n += k }
        if (n < 4 || toBigEndian(head) == null) return
        val out = java.io.ByteArrayOutputStream(4 * 1024 * 1024)
        out.write(head)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val k = input.read(buf); if (k < 0) break
            out.write(buf, 0, k)
            if (out.size() > MAX_SIZE) return
        }
        identify(out.toByteArray())?.let { found += it }
    }
}
