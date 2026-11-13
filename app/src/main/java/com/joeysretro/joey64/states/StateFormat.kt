package com.joeysretro.joey64.states

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * RetroArch's save state file (plan, Phase 3: states in RetroArch's format, so RetroArch's states
 * load in Joey64 and ours can go back), as RetroArch 1.15+ writes it (tasks/task_save.c,
 * libretro-common/streams/rzip_stream.c):
 *  - RASTATE: "RASTATE" + version 1, then blocks of a 4-byte tag, a u32 size and the data padded
 *    to 8 bytes. "MEM " holds the core's own data (mupen64plus-next: "M64+SAVE..."), "END " ends it.
 *    "ACHV" holds the achievements' progress (rcheevos' rc_client_serialize_progress, after MEM, as
 *    RetroArch writes it); RetroArch's "RPLY" (replays) is skipped on reading.
 *  - RZIP, around the whole file when compression is on (RetroArch's default): "#RZIPv" 1 "#", a
 *    u32 chunk size, the u64 total size, then chunks of a u32 compressed size and zlib data.
 * Older RetroArch wrote the core's data alone, which reads too.
 */
object StateFormat {
    private const val RZIP_MAGIC = "#RZIPv\u0001#"
    private const val RZIP_HEADER = 20
    private const val RZIP_CHUNK = 131072
    private const val RZIP_LEVEL = 6
    private const val RASTATE = "RASTATE"
    private const val RASTATE_VERSION = 1
    private const val MAX_STATE = 64L * 1024 * 1024

    /** A state file's parts: the core's data and the achievements' progress (null when it has none). */
    class Parts(val mem: ByteArray, val achv: ByteArray?)

    /**
     * The core's [data] as a RetroArch state file: RASTATE with a MEM block, then an ACHV block when
     * [achv] has any, RZIP-compressed when [compress].
     */
    fun wrap(data: ByteArray, achv: ByteArray? = null, compress: Boolean = true): ByteArray {
        val aligned = (data.size + 7) and 7.inv()
        val achvAligned = achv?.takeIf { it.isNotEmpty() }?.let { 8 + ((it.size + 7) and 7.inv()) } ?: 0
        val out = ByteArray(8 + 8 + aligned + achvAligned + 8)
        RASTATE.toByteArray(Charsets.ISO_8859_1).copyInto(out, 0)
        out[7] = RASTATE_VERSION.toByte()
        block(out, 8, "MEM ", data.size)   // the exact size: some cores need it, RetroArch notes
        data.copyInto(out, 16)
        var at = 16 + aligned
        if (achvAligned > 0) {
            block(out, at, "ACHV", achv!!.size)
            achv.copyInto(out, at + 8)
            at += achvAligned
        }
        block(out, at, "END ", 0)
        return if (compress) rzip(out) else out
    }

    /**
     * The core's data in a state file's [bytes] (RZIP or not, RASTATE or bare), or null when it
     * isn't a state this can read.
     */
    fun unwrap(bytes: ByteArray): ByteArray? = read(bytes)?.mem

    fun unwrap(input: InputStream): ByteArray? = unwrap(input.readBytes())

    /** [unwrap] with the ACHV block too. A bare state (older RetroArch) has none. */
    fun read(bytes: ByteArray): Parts? {
        val plain = if (startsWith(bytes, RZIP_MAGIC)) unrzip(bytes) ?: return null else bytes
        if (!startsWith(plain, RASTATE)) return plain.takeIf { it.isNotEmpty() }?.let { Parts(it, null) }
        var mem: ByteArray? = null
        var achv: ByteArray? = null
        var at = 8
        while (at + 8 <= plain.size) {
            val tag = String(plain, at, 4, Charsets.ISO_8859_1)
            val size = le32(plain, at + 4)
            if (size < 0 || at + 8 + size > plain.size) break
            when (tag) {
                "MEM " -> mem = plain.copyOfRange(at + 8, at + 8 + size.toInt())
                "ACHV" -> achv = plain.copyOfRange(at + 8, at + 8 + size.toInt()).takeIf { it.isNotEmpty() }
                "END " -> break
            }
            at += 8 + ((size.toInt() + 7) and 7.inv())
        }
        return mem?.let { Parts(it, achv) }
    }

    private fun block(out: ByteArray, at: Int, tag: String, size: Int) {
        tag.toByteArray(Charsets.ISO_8859_1).copyInto(out, at)
        for (i in 0 until 4) out[at + 4 + i] = (size ushr (8 * i)).toByte()
    }

    private fun rzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 3 + 64)
        out.write(RZIP_MAGIC.toByteArray(Charsets.ISO_8859_1))
        out.write(le(RZIP_CHUNK.toLong(), 4))
        out.write(le(data.size.toLong(), 8))
        val deflater = Deflater(RZIP_LEVEL)
        val buf = ByteArray(RZIP_CHUNK + RZIP_CHUNK / 8 + 64)
        try {
            var at = 0
            while (at < data.size) {
                val n = minOf(RZIP_CHUNK, data.size - at)
                deflater.reset()
                deflater.setInput(data, at, n)
                deflater.finish()
                var len = 0
                while (!deflater.finished()) {
                    if (len == buf.size) throw IOException("rzip chunk grew past its buffer")
                    len += deflater.deflate(buf, len, buf.size - len)
                }
                out.write(le(len.toLong(), 4))
                out.write(buf, 0, len)
                at += n
            }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    private fun unrzip(bytes: ByteArray): ByteArray? {
        if (bytes.size < RZIP_HEADER) return null
        val total = le64(bytes, 12)
        // The core's state is ~16 MB; a damaged header could ask for up to 2 GB and crash the app.
        if (total <= 0 || total > MAX_STATE) return null
        val out = ByteArray(total.toInt())
        var at = RZIP_HEADER
        var filled = 0
        val inflater = Inflater()
        try {
            while (filled < out.size && at + 4 <= bytes.size) {
                val n = le32(bytes, at).toInt()
                at += 4
                // As Long: a length near 2 GB made at + n wrap below the file's size, and setInput then threw
                // ArrayIndexOutOfBounds on the state thread (code audit 3, p1 L1).
                if (n <= 0 || at.toLong() + n > bytes.size) return null
                inflater.reset()
                inflater.setInput(bytes, at, n)
                while (!inflater.finished() && filled < out.size) {
                    val r = inflater.inflate(out, filled, out.size - filled)
                    if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                    filled += r
                }
                at += n
            }
        } catch (e: Exception) {
            // A damaged or made-up state: bad deflate data (DataFormatException) or anything else the reader trips on.
            return null
        } finally {
            inflater.end()
        }
        return out.takeIf { filled == out.size }
    }

    private fun startsWith(b: ByteArray, magic: String): Boolean =
        b.size >= magic.length && magic.indices.all { b[it] == magic[it].code.toByte() }

    private fun le(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }

    private fun le32(b: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }

    private fun le64(b: ByteArray, at: Int): Long =
        (0 until 8).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }
}

/**
 * Which file holds which slot, by RetroArch's names (decided 2026-11-04: 10 slots and an auto slot,
 * matching RetroArch's slots 0-9 and `.auto`, so imported states line up). The pause menu calls
 * them Slot 1 to Slot 10: Slot 1 is RetroArch's slot 0 (`.state`), Slot 10 its slot 9 (`.state9`).
 */
object StateSlots {
    const val COUNT = 10
    /** The auto slot: written on Quit to library, used by Continue. */
    const val AUTO = 0
    /**
     * The older quit saves (Settings › Saves › Quit saves to keep, approved Saves mockup 2026-11-07):
     * Auto 2 is slot [AUTO_OLDER], up to Auto 10, as `.state.auto2` ... `.state.auto10` (RetroArch has
     * no such files). Each quit moves Auto to Auto 2, Auto 2 to Auto 3, and so on.
     */
    const val AUTO_OLDER = 11
    const val AUTO_MAX = 10

    /** Auto n (1 = [AUTO]). */
    fun auto(n: Int): Int = if (n <= 1) AUTO else AUTO_OLDER + n - 2

    fun isAuto(slot: Int) = slot == AUTO || slot in AUTO_OLDER until AUTO_OLDER + AUTO_MAX - 1

    /** Which Auto [slot] is: 1 for [AUTO], 2 for Auto 2 ... */
    fun autoNumber(slot: Int) = if (slot == AUTO) 1 else slot - AUTO_OLDER + 2

    /** [saveName] is the ROM's file name without extension, as saves are named. */
    fun fileName(saveName: String, slot: Int): String = when (slot) {
        AUTO -> "$saveName.state.auto"
        1 -> "$saveName.state"
        in 2..COUNT -> "$saveName.state${slot - 1}"
        in AUTO_OLDER until AUTO_OLDER + AUTO_MAX - 1 -> "$saveName.state.auto${autoNumber(slot)}"
        else -> throw IllegalArgumentException("no slot $slot")
    }

    /** The pause menu's name for [slot]. */
    fun label(slot: Int) = when {
        slot == AUTO -> "Auto save state"
        isAuto(slot) -> "Auto save state ${autoNumber(slot)}"
        else -> "Slot $slot"
    }

    /** RetroArch's picture of a state sits beside it as `<state file>.png`. */
    fun thumbnailName(saveName: String, slot: Int) = fileName(saveName, slot) + ".png"
}
