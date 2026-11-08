package com.joeysretro.joey64.reports

/**
 * A native crash's tombstone (Android 12+, ApplicationExitInfo.getTraceInputStream for REASON_CRASH_NATIVE)
 * read into a short text for a crash report: the signal, the abort message, and the crashing thread's
 * backtrace (library file name only, offset in it, function). Only the fields named here are read, from
 * AOSP's system/core/debuggerd/proto/tombstone.proto:
 *  - Tombstone: tid 6, signal_info 10, abort_message 14, threads 16 (map uint32 -> Thread: key 1, value 2)
 *  - Signal: number 1, name 2, code 3, code_name 4, has_fault_address 8, fault_address 9
 *  - Thread: id 1, name 2, current_backtrace 4
 *  - BacktraceFrame: rel_pc 1, function_name 4, function_offset 5, file_name 6
 * Pure: unit tested, and checked on a real one (the Thor, kill -11, 2026-11-07).
 */
object Tombstone {
    const val MAX_FRAMES = 24

    private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var r = 0L; var shift = 0
            while (true) {
                if (pos >= end || shift > 63) throw IllegalArgumentException("bad varint")
                val x = b[pos++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }
        /** The next field: its number and wire type; [bytes] for type 2, [value] for 0/1/5. */
        var field = 0; var type = 0; var value = 0L; var start = 0; var len = 0
        fun next() {
            val key = varint()
            field = (key ushr 3).toInt(); type = (key and 7).toInt()
            when (type) {
                0 -> value = varint()
                1 -> { need(8); value = (0 until 8).fold(0L) { a, i -> a or ((b[pos + i].toLong() and 0xff) shl (8 * i)) }; pos += 8 }
                2 -> { val n = varint(); if (n < 0 || n > end - pos) throw IllegalArgumentException("bad length"); start = pos; len = n.toInt(); pos += len }
                5 -> { need(4); value = (0 until 4).fold(0L) { a, i -> a or ((b[pos + i].toLong() and 0xff) shl (8 * i)) }; pos += 4 }
                else -> throw IllegalArgumentException("wire type $type")
            }
        }
        private fun need(n: Int) { if (end - pos < n) throw IllegalArgumentException("short") }
        fun sub() = Reader(b, start, start + len)
        fun string() = String(b, start, len, Charsets.UTF_8)
    }

    private class Frame(var relPc: Long = 0, var function: String = "", var offset: Long = 0, var file: String = "")
    private class Thread(var id: Long = 0, var name: String = "", val frames: MutableList<Frame> = mutableListOf())

    /** The summary, or null if [bytes] isn't a tombstone this can read. */
    fun summary(bytes: ByteArray): String? = try { read(bytes) } catch (e: IllegalArgumentException) { null } catch (e: IndexOutOfBoundsException) { null }

    private fun read(bytes: ByteArray): String? {
        val r = Reader(bytes, 0, bytes.size)
        var tid = -1L; var abort = ""
        var sigNo = -1L; var sigName = ""; var code = -1L; var codeName = ""; var hasFault = false; var fault = 0L
        val threads = mutableListOf<Thread>()
        while (r.more()) {
            r.next()
            when {
                r.field == 6 && r.type == 0 -> tid = r.value
                r.field == 14 && r.type == 2 -> abort = r.string()
                r.field == 10 && r.type == 2 -> {
                    val s = r.sub()
                    while (s.more()) {
                        s.next()
                        when {
                            s.field == 1 && s.type == 0 -> sigNo = s.value.toInt().toLong()
                            s.field == 2 && s.type == 2 -> sigName = s.string()
                            s.field == 3 && s.type == 0 -> code = s.value.toInt().toLong()
                            s.field == 4 && s.type == 2 -> codeName = s.string()
                            s.field == 8 && s.type == 0 -> hasFault = s.value != 0L
                            s.field == 9 && s.type == 0 -> fault = s.value
                        }
                    }
                }
                r.field == 16 && r.type == 2 -> {
                    val entry = r.sub()
                    while (entry.more()) {
                        entry.next()
                        if (entry.field == 2 && entry.type == 2) threads += thread(entry.sub())
                    }
                }
            }
        }
        if (sigNo < 0 && threads.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("signal ").append(if (sigNo >= 0) sigNo.toString() else "?")
        if (sigName.isNotEmpty()) sb.append(" (").append(sigName).append(')')
        if (code >= 0) { sb.append(", code ").append(code); if (codeName.isNotEmpty()) sb.append(" (").append(codeName).append(')') }
        if (hasFault) sb.append(", fault addr 0x").append(java.lang.Long.toHexString(fault))
        if (abort.isNotBlank()) sb.append("\nabort: ").append(abort.trim().take(300))
        val crashed = threads.firstOrNull { it.id == tid } ?: threads.firstOrNull()
        if (crashed != null) {
            sb.append("\nthread ").append(crashed.name.ifEmpty { "?" })
            for ((i, f) in crashed.frames.take(MAX_FRAMES).withIndex()) {
                sb.append('\n').append('#').append(i.toString().padStart(2, '0')).append(" pc ")
                    .append(java.lang.Long.toHexString(f.relPc).padStart(8, '0')).append("  ")
                    .append(f.file.substringAfterLast('/').ifEmpty { "?" })
                if (f.function.isNotEmpty()) sb.append(" (").append(f.function.take(120)).append('+').append(f.offset).append(')')
            }
            if (crashed.frames.size > MAX_FRAMES) sb.append("\n... ").append(crashed.frames.size - MAX_FRAMES).append(" more")
        }
        return sb.toString()
    }

    private fun thread(r: Reader): Thread {
        val t = Thread()
        while (r.more()) {
            r.next()
            when {
                r.field == 1 && r.type == 0 -> t.id = r.value
                r.field == 2 && r.type == 2 -> t.name = r.string()
                r.field == 4 && r.type == 2 -> {
                    val fr = r.sub(); val f = Frame()
                    while (fr.more()) {
                        fr.next()
                        when {
                            fr.field == 1 && fr.type == 0 -> f.relPc = fr.value
                            fr.field == 4 && fr.type == 2 -> f.function = fr.string()
                            fr.field == 5 && fr.type == 0 -> f.offset = fr.value
                            fr.field == 6 && fr.type == 2 -> f.file = fr.string()
                        }
                    }
                    t.frames += f
                }
            }
        }
        return t
    }
}
