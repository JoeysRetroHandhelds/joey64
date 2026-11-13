package com.joeysretro.joey64

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Per-game byte fixes for 64DD disks whose dump doesn't run as dumped (gamedb/games.json says why for each).
 * A disk is matched by the MD5 of the whole file as dumped; each fix is an offset with the byte the dump has and the
 * byte that runs. The fix goes into the unpacked copy in cache/dd (the user's file is never touched) and into the
 * core's disk save when there is one: the core loads a disk save in place of the disk (it is the whole disk), so a
 * save written before the fix still has the dump's bytes. The fixed bytes are in the disk's ROM area, which games
 * never write, so the save's own data is not changed.
 */
object DiskFixes {
    class Patch(val offset: Long, val was: Int, val now: Int)
    class Fix(val name: String, val size: Long, val patches: List<Patch>)

    val FIXES: Map<String, Fix> = mapOf(
        // Mario Artist - Paint Studio (Japan) (Beta) (1999-02-11): black after its first frames, no sound. The
        // community fix (Zoinkity, in LuigiBlood's Translate repo, DMPJ990211_Patch.asm, as 64dd.org's "Fix" dump):
        // Fix Framebuffer Swapping, and Disable Print Output. Duo 2026-11-12: title screen at 59.9 fps with sound.
        "a76b619ec832a7e2abcfbdfeb5375e39" to Fix("Mario Artist Paint Studio 1999 beta", 0x3DEC800L, listOf(
            Patch(0x9F0F3, 0x3F, 0x03), // Fix Framebuffer Swapping
            Patch(0x9DB83, 0x09, 0x12), // Disable Print Output
        )),
    )

    /** The fix for [disk] (an unpacked disk file, not yet fixed), or null. Hashes the file only when its size and bytes fit one. */
    fun fixFor(disk: File, knownMd5: String?): Pair<String, Fix>? {
        if (knownMd5 != null) return FIXES[knownMd5.lowercase()]?.takeIf { disk.length() == it.size && holds(disk, it, false) }?.let { knownMd5 to it }
        val candidates = FIXES.entries.filter { (_, f) -> disk.length() == f.size && holds(disk, f, false) }
        if (candidates.isEmpty()) return null
        val md5 = md5(disk)
        return candidates.firstOrNull { it.key == md5 }?.let { it.key to it.value }
    }

    /**
     * Applies [fix] to [disk] and to [save] (the core's disk save, when it exists and still has the dump's bytes).
     * Returns a log line.
     */
    fun apply(fix: Fix, disk: File, save: File?): String {
        write(disk, fix)
        val saveNote = when {
            save == null || !save.isFile -> "no disk save"
            save.length() != fix.size -> "disk save left as it is (size ${save.length()})"
            holds(save, fix, true) -> "disk save already fixed"
            holds(save, fix, false) -> { write(save, fix); "disk save fixed too" }
            else -> "disk save left as it is (other bytes there)"
        }
        return "64DD: ${fix.name}: ${fix.patches.size} bytes fixed in the unpacked disk; $saveNote"
    }

    private fun holds(f: File, fix: Fix, fixed: Boolean): Boolean = try {
        RandomAccessFile(f, "r").use { r ->
            fix.patches.all { p -> r.seek(p.offset); r.read() == if (fixed) p.now else p.was }
        }
    } catch (e: Exception) { false }

    private fun write(f: File, fix: Fix) {
        RandomAccessFile(f, "rw").use { r -> for (p in fix.patches) { r.seek(p.offset); r.write(p.now) } }
    }

    private fun md5(f: File): String {
        val d = MessageDigest.getInstance("MD5")
        f.inputStream().buffered(1 shl 20).use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = s.read(buf); if (n < 0) break; d.update(buf, 0, n) }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
