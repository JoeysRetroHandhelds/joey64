package com.joeysretro.joey64.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Tombstone's reading of the protobuf fields it uses, on a hand-built one (and a real one when given). */
class TombstoneTest {
    private class Pb {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (true) { val b = (x and 0x7f).toInt(); x = x ushr 7; if (x == 0L) { out.write(b); return }; out.write(b or 0x80) } }
        fun int(field: Int, v: Long) = apply { varint((field shl 3).toLong()); varint(v) }
        fun bytes(field: Int, b: ByteArray) = apply { varint((field shl 3 or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray())
        fun msg(field: Int, m: Pb) = bytes(field, m.out.toByteArray())
        fun fixed64(field: Int, v: Long) = apply { varint((field shl 3 or 1).toLong()); for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xff).toInt()) }
    }

    private fun frame(pc: Long, file: String, fn: String = "", off: Long = 0) =
        Pb().int(1, pc).int(2, pc + 0x7000000000).apply { if (fn.isNotEmpty()) str(4, fn).int(5, off) }.str(6, file).str(8, "abcdef")

    private fun thread(id: Long, name: String, vararg frames: Pb) = Pb().int(1, id).str(2, name).apply { frames.forEach { msg(4, it) } }

    private fun tombstone(): ByteArray {
        val other = thread(100, "main", frame(0x10, "/system/lib64/libc.so", "read", 4))
        val crashed = thread(4604, "emu", frame(0x1234, "/data/app/~~x==/com.joeysretro.joey64-y==/lib/arm64/libjoey64-core.so", "r4300_step", 16),
            frame(0x5678, "/data/app/~~x==/lib/arm64/libjoey64host.so"))
        return Pb().int(1, 1).str(2, "fingerprint").int(5, 4604).int(6, 4604)
            .msg(10, Pb().int(1, 11).str(2, "SIGSEGV").int(3, 1).str(4, "SEGV_MAPERR").int(8, 1).int(9, 0xdead))
            .str(14, "")
            .msg(16, Pb().int(1, 100).msg(2, other))
            .msg(16, Pb().int(1, 4604).msg(2, crashed))
            .fixed64(30, 42)   // a field it doesn't know: skipped
            .out.toByteArray()
    }

    @Test fun readsSignalAndTheCrashedThreadsBacktrace() {
        val s = Tombstone.summary(tombstone())!!
        val lines = s.lines()
        assertEquals("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xdead", lines[0])
        assertEquals("thread emu", lines[1])
        assertEquals("#00 pc 00001234  libjoey64-core.so (r4300_step+16)", lines[2])
        assertEquals("#01 pc 00005678  libjoey64host.so", lines[3])
        assertTrue("no folders: $s", !s.contains("/data"))
    }

    @Test fun junkIsNotATombstone() {
        assertNull(Tombstone.summary(byteArrayOf(0x0a, 0x7f, 1)))
        assertNull(Tombstone.summary(ByteArray(0)))
    }

    @Test fun longBacktracesAreCut() {
        val frames = (0 until 40).map { frame(it.toLong(), "/x/lib.so") }.toTypedArray()
        val b = Pb().int(6, 1).msg(10, Pb().int(1, 6)).msg(16, Pb().int(1, 1).msg(2, thread(1, "t", *frames))).out.toByteArray()
        val s = Tombstone.summary(b)!!
        assertTrue(s.endsWith("... 16 more"))
        assertEquals(1 + 1 + Tombstone.MAX_FRAMES + 1, s.lines().size)
    }

    /** JOEY64_TOMBSTONE=<a .tombstone.pb from files/logs>: prints what a report would carry. */
    @Test fun realTombstone() {
        val path = System.getenv("JOEY64_TOMBSTONE")
        assumeTrue(path != null && File(path).exists())
        val s = Tombstone.summary(File(path!!).readBytes())
        println("TOMBSTONE SUMMARY:\n$s")
        assertTrue(s != null && s.startsWith("signal "))
    }
}
