package com.joeysretro.joey64

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder
import java.nio.charset.CoderResult

/**
 * Entry names for the zips in the user's folders (ROMs, the 64DD BIOS, Game Boy games): UTF-8 when they are, else
 * one character a byte. ZipInputStream reads a name without the zip's UTF-8 flag in the charset it was given, and with
 * UTF-8 (its default) a name that isn't UTF-8, such as an old Japanese zip's Shift-JIS, throws
 * IllegalArgumentException: the library counted such a zip as failed and read it again on every scan (code audit 3,
 * p3 L2). Only extensions and folder names are looked at, which are ASCII either way. A name with the flag is still
 * read as UTF-8 by ZipInputStream itself.
 *
 * ZipInputStream decodes each name in one call, so the decoder looks at the whole name at once. Reading only.
 */
object ZipNames : Charset("x-joey64-zip-names", null) {
    override fun contains(cs: Charset) = cs == this
    override fun newDecoder(): CharsetDecoder = Decoder()
    override fun canEncode() = false
    override fun newEncoder(): CharsetEncoder = throw UnsupportedOperationException("zip names are only read")

    private class Decoder : CharsetDecoder(ZipNames, 1f, 1f) {
        override fun decodeLoop(src: ByteBuffer, dst: CharBuffer): CoderResult {
            // Never more characters than bytes in either reading: wait for room for the whole name.
            if (dst.remaining() < src.remaining()) return CoderResult.OVERFLOW
            val bytes = ByteArray(src.remaining()).also { src.get(it) }
            val utf8 = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)) } catch (e: CharacterCodingException) { null }
            if (utf8 != null) dst.put(utf8) else for (b in bytes) dst.put((b.toInt() and 0xFF).toChar())
            return CoderResult.UNDERFLOW
        }
    }
}
