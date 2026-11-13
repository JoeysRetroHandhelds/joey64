package com.joeysretro.joey64.launcher.library

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * M64Plus FZ's saves turned into ours (queue "Import from M64Plus FZ"). No screen yet: that waits on Joey's mockup.
 *
 * FZ runs the same Mupen64Plus device code as our core and writes each save type raw from the same memory, one file
 * each, in `GameData/<header name> <country> <ROM MD5>/SramData/`: `.eep` (0x200 for 4K-EEPROM games, 0x800 for 16K),
 * `.sra` (0x8000), `.fla` (0x20000) and `.mpk` (all four controller paks, 4 x 0x8000). Our `.srm` (the libretro save
 * memory) is the same four regions end to end, so converting is placing the bytes; checked against real FZ saves
 * (OoT's SRAM reads 98000000 5a211009 41444c45… in both, no byte swap).
 *
 * FZ's states (SlotSaves: `<game>.st<n>`, AutoSaves: `<date>.v2.sav`) are Mupen64Plus states gzipped; our core loads them
 * unzipped (state version 1.8 there, our core reads 1.0 to 1.9).
 */
object FzSaves {
    const val EEPROM_AT = 0
    const val EEPROM_SIZE = 0x800
    const val MEMPAK_AT = EEPROM_AT + EEPROM_SIZE
    const val MEMPAK_SIZE = 0x8000
    const val MEMPAKS = 4
    const val SRAM_AT = MEMPAK_AT + MEMPAKS * MEMPAK_SIZE
    const val SRAM_SIZE = 0x8000
    const val FLASH_AT = SRAM_AT + SRAM_SIZE
    const val FLASH_SIZE = 0x20000
    const val SRM_SIZE = FLASH_AT + FLASH_SIZE

    init { check(SRM_SIZE.toLong() == RetroArchImport.N64_SRM_SIZE) }

    /** Where each FZ save file goes in ours, and the most it may hold. */
    enum class Part(val ext: String, val at: Int, val max: Int) {
        Eeprom("eep", EEPROM_AT, EEPROM_SIZE),
        Mempaks("mpk", MEMPAK_AT, MEMPAKS * MEMPAK_SIZE),
        Sram("sra", SRAM_AT, SRAM_SIZE),
        Flash("fla", FLASH_AT, FLASH_SIZE);

        companion object { fun of(fileName: String) = entries.firstOrNull { fileName.endsWith(".${it.ext}", ignoreCase = true) } }
    }

    class TooBig(val part: Part, val size: Int) : IllegalArgumentException("${part.ext} file of $size bytes, most is ${part.max}")

    /**
     * Our save with FZ's [parts] put in. On top of [ours] (the game's save here now, left as it is outside those
     * parts) or, with none, on a blank one as our core makes it. A part shorter than its region (a 4K EEPROM) fills
     * the start and leaves the rest. [ours] isn't changed.
     */
    fun toSrm(parts: Map<Part, ByteArray>, ours: ByteArray? = null): ByteArray {
        require(ours == null || ours.size == SRM_SIZE) { "our save is ${ours?.size} bytes, not $SRM_SIZE" }
        val out = ours?.copyOf() ?: blankSrm()
        for ((part, bytes) in parts) {
            if (bytes.size > part.max) throw TooBig(part, bytes.size)
            bytes.copyInto(out, part.at)
        }
        return out
    }

    /**
     * A save as our core starts one (libretro.c format_saved_memory): 0xFF everywhere but four formatted paks, byte for
     * byte. Their serial is zero: the core asks its pak id generator for one, but that is seeded later, in main_run
     * (main.c), so every blank .srm it writes has serial 0 (checked against its own .srm files, 2026-11-13).
     */
    fun blankSrm(): ByteArray {
        val out = ByteArray(SRM_SIZE) { 0xFF.toByte() }
        for (i in 0 until MEMPAKS) formatMempak(out, MEMPAK_AT + i * MEMPAK_SIZE, IntArray(6))
        return out
    }

    /** mempak.c format_mempak with the default device id 1, 1 bank, version 0. */
    fun formatMempak(mem: ByteArray, at: Int, serial: IntArray) {
        require(serial.size == 6)
        java.util.Arrays.fill(mem, at, at + MEMPAK_SIZE, 0)
        // Page 0: the ID block at 32-byte blocks 1, 3, 4 and 6; the rest zero.
        val id = at + 32
        for (i in 0 until 6) be32(mem, id + 4 * i, serial[i])
        be16(mem, id + 24, 0x0001)
        mem[id + 26] = 1
        mem[id + 27] = 0
        var sum = 0
        for (i in 0 until 28 step 2) sum += u16(mem, id + i)
        sum = sum and 0xFFFF
        be16(mem, id + 28, sum)
        be16(mem, id + 30, (0xFFF2 - sum) and 0xFFFF)
        for (b in intArrayOf(3, 4, 6)) mem.copyInto(mem, at + b * 32, id, id + 32)
        // Page 1: the index table, pages 5..127 empty (3), byte 1 the sum of those; page 2 a copy.
        val p1 = at + 256
        for (i in 5 until 128) be16(mem, p1 + 2 * i, 0x0003)
        var t = 0
        for (i in 10 until 256) t += mem[p1 + i].toInt() and 0xFF
        mem[p1 + 1] = t.toByte()
        mem.copyInto(mem, at + 512, p1, p1 + 256)
    }

    /** The ROM MD5 in an FZ GameData folder name (`SUPER MARIO 64 (U) 20B854…A2`), upper case, or null. */
    fun romMd5(folderName: String): String? =
        Regex("""(?:^|\s)([0-9A-Fa-f]{32})$""").find(folderName.trim())?.groupValues?.get(1)?.uppercase()

    /** Header of a Mupen64Plus state: magic, version, ROM MD5 (32 ASCII hex). */
    data class StateInfo(val version: Int, val romMd5: String)

    fun stateInfo(state: ByteArray): StateInfo? {
        if (state.size < 44 || String(state, 0, 8, Charsets.ISO_8859_1) != "M64+SAVE") return null
        return StateInfo(
            (u16(state, 8) shl 16) or u16(state, 10),
            String(state, 12, 32, Charsets.ISO_8859_1).uppercase(),
        )
    }

    /** The body a state of [version] needs, as our core checks it (libretro.c retro_unserialize). */
    fun stateSizeNeeded(version: Int): Int {
        var need = 44 + 16788244 + 1024
        if (version >= 0x00010100) need += 4
        if (version >= 0x00010200) need += 4096
        return need
    }

    /**
     * An FZ state unzipped into one our core loads, or null when it isn't one (not gzip, not a Mupen64Plus state, a
     * version newer than ours reads, cut short). Read at most [limit] bytes so a bad file can't fill memory.
     */
    fun stateForCore(input: InputStream, limit: Int = 32 shl 20): ByteArray? {
        val raw = try {
            GZIPInputStream(input).use { z ->
                val out = ByteArrayOutputStream(17 shl 20)
                val buf = ByteArray(64 shl 10)
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) return null
                }
                out.toByteArray()
            }
        } catch (e: java.io.IOException) { return null }
        val info = stateInfo(raw) ?: return null
        if (info.version < 0x00010000 || info.version > OUR_STATE_VERSION) return null
        if (raw.size < stateSizeNeeded(info.version)) return null
        return raw
    }

    /** savestates.c savestate_latest_version in our core. */
    const val OUR_STATE_VERSION = 0x00010900

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun be16(b: ByteArray, at: Int, v: Int) { b[at] = (v shr 8).toByte(); b[at + 1] = v.toByte() }
    private fun be32(b: ByteArray, at: Int, v: Int) { be16(b, at, v ushr 16); be16(b, at + 2, v) }
}
