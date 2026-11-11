package com.joeysretro.joey64

import com.joeysretro.joey64.launcher.library.RomCheck
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Zips whose entry names aren't UTF-8 (code audit 3, p3 L2). */
class ZipNamesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rom = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + ByteArray(60) { (it * 3).toByte() }

    /** A zip whose one entry is named in [charset], without the zip's UTF-8 flag unless it is UTF-8 (old Windows and Japanese tools). */
    private fun zip(name: String, charset: Charset): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, charset).use { z -> z.putNextEntry(ZipEntry(name)); z.write(rom); z.closeEntry() }
        return out.toByteArray()
    }

    @Test fun aNameThatIsntUtf8IsReadOneCharacterAByte() {
        val latin = zip("Pokémon Stadium (Europe).z64", Charsets.ISO_8859_1)
        // What the library hit: Java's zip reader throws on the name.
        assertThrows(IllegalArgumentException::class.java) { ZipInputStream(latin.inputStream()).nextEntry }
        assertEquals("Pokémon Stadium (Europe).z64", ZipInputStream(latin.inputStream(), ZipNames).nextEntry!!.name)
        if (Charset.isSupported("Shift_JIS")) {
            val sjis = zip("ゼルダの伝説 (Japan).z64", Charset.forName("Shift_JIS"))
            assertThrows(IllegalArgumentException::class.java) { ZipInputStream(sjis.inputStream()).nextEntry }
            val name = ZipInputStream(sjis.inputStream(), ZipNames).nextEntry!!.name
            assertTrue(name, name.endsWith(" (Japan).z64"))
        }
    }

    @Test fun utf8NamesStayAsTheyAre() {
        // With the UTF-8 flag (Java's own zips, 7-Zip): read by ZipInputStream itself.
        assertEquals("Pokémon Stadium (Europe).z64",
            ZipInputStream(zip("Pokémon Stadium (Europe).z64", Charsets.UTF_8).inputStream(), ZipNames).nextEntry!!.name)
        // UTF-8 without the flag (macOS's zips): the decoder keeps it.
        assertEquals("Pokémon", ZipNames.newDecoder().decode(ByteBuffer.wrap("Pokémon".toByteArray(Charsets.UTF_8))).toString())
        assertEquals("Pokémon", ZipNames.newDecoder().decode(ByteBuffer.wrap("Pokémon".toByteArray(Charsets.ISO_8859_1))).toString())
        assertEquals("", ZipNames.newDecoder().decode(ByteBuffer.wrap(ByteArray(0))).toString())
    }

    @Test fun romsInSuchZipsAreFoundAndUnpacked() {
        val z = zip("Pokémon Stadium (Europe).z64", Charsets.ISO_8859_1)
        assertTrue(RomCheck.isN64("Pokemon.zip", z.inputStream()))
        val out = tmp.root.resolve("rom")
        assertEquals("Pokémon Stadium (Europe).z64", RomFiles.extract(z.inputStream(), out))
        assertArrayEquals(rom, out.readBytes())
    }
}
