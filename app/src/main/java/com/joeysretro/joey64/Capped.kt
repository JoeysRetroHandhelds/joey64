package com.joeysretro.joey64

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and copies with a size cap (code audit 3, Lows): what comes from the network or a picked folder can be any
 * size, and a whole-file read of a huge one fills the heap or the storage.
 */
object Capped {
    const val KB = 1024L
    const val MB = 1024L * 1024
    /** The most copied in from a picked folder or file (a shader texture, an overlay image, a picture, a Game Boy game): none is near it (code audit 3, p4 L7). */
    const val PICKED_FILE = 64 * MB

    class TooBig(what: String, max: Long) : IOException("$what is over ${size(max)}")

    /** [input] to its end, at most [max] bytes, else [TooBig]. */
    fun bytes(input: InputStream, max: Long, what: String = "the file"): ByteArray =
        ByteArrayOutputStream().also { copy(input, it, max, what) }.toByteArray()

    /** [bytes] as UTF-8 text. */
    fun text(input: InputStream, max: Long, what: String = "the reply"): String = String(bytes(input, max, what), Charsets.UTF_8)

    /** Copies [input] to [out], at most [max] bytes, else [TooBig] (what was written stays: the caller drops it). The count. */
    fun copy(input: InputStream, out: OutputStream, max: Long, what: String = "the file"): Long {
        val buf = ByteArray(1 shl 16)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) return n
            n += r
            if (n > max) throw TooBig(what, max)
            out.write(buf, 0, r)
        }
    }

    private fun size(b: Long) = if (b >= MB) "${b / MB} MB" else "${b / KB} KB"
}
