package com.joeysretro.joey64.states

import com.joeysretro.joey64.launcher.library.RetroArchImport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StateFormatTest {
    /** Something shaped like the core's data: its magic, then a mix of repeats and noise, an odd length. */
    private fun coreData(size: Int): ByteArray {
        val r = Random(7)
        val b = ByteArray(size) { if (it % 3 == 0) r.nextInt().toByte() else (it / 64).toByte() }
        "M64+SAVE".toByteArray().copyInto(b)
        return b
    }

    @Test fun compressedRoundTrip() {
        // Several RZIP chunks (128 KB each) and a last part-chunk; a size not a multiple of 8.
        val data = coreData(3 * 131072 + 4321)
        val file = StateFormat.wrap(data)
        assertEquals("#RZIPv\u0001#", String(file, 0, 8, Charsets.ISO_8859_1))
        assertArrayEquals(data, StateFormat.unwrap(file))
    }

    @Test fun plainLayoutIsRetroArchs() {
        val data = coreData(13)
        val file = StateFormat.wrap(data, compress = false)
        // "RASTATE" 1, "MEM " + exact size, data padded to 16, "END " 0.
        assertEquals(8 + 8 + 16 + 8, file.size)
        assertEquals("RASTATE", String(file, 0, 7, Charsets.ISO_8859_1))
        assertEquals(1, file[7].toInt())
        assertEquals("MEM ", String(file, 8, 4, Charsets.ISO_8859_1))
        assertEquals(13, file[12].toInt())
        assertEquals("END ", String(file, 32, 4, Charsets.ISO_8859_1))
        assertArrayEquals(data, StateFormat.unwrap(file))
    }

    @Test fun skipsOtherBlocksAndReadsBareData() {
        val data = coreData(40)
        // An "ACHV" block of 5 bytes (padded to 8) before MEM, as RetroArch writes with achievements on.
        val withAchievements = "RASTATE".toByteArray() + byteArrayOf(1) +
            "ACHV".toByteArray() + byteArrayOf(5, 0, 0, 0) + ByteArray(8) +
            "MEM ".toByteArray() + byteArrayOf(40, 0, 0, 0) + data +
            "END ".toByteArray() + ByteArray(4)
        assertArrayEquals(data, StateFormat.unwrap(withAchievements))
        // Older RetroArch: the core's data alone.
        assertArrayEquals(data, StateFormat.unwrap(data))
    }

    @Test fun damagedFilesGiveNull() {
        val file = StateFormat.wrap(coreData(200_000))
        assertNull(StateFormat.unwrap(file.copyOf(file.size / 2)))
        val plain = StateFormat.wrap(coreData(64), compress = false)
        assertNull(StateFormat.unwrap(plain.copyOf(30)))
        assertNull(StateFormat.unwrap(ByteArray(0)))
    }

    @Test fun ourStatesAreFoundByTheImport() {
        // The import's reader (RetroArch's files) takes what we write as an N64 state.
        assertTrue(RetroArchImport.isN64State(StateFormat.wrap(coreData(300_000)).inputStream()))
        assertTrue(RetroArchImport.isN64State(StateFormat.wrap(coreData(100), compress = false).inputStream()))
    }

    @Test fun slotNamesFollowRetroArch() {
        assertEquals("Banjo.state.auto", StateSlots.fileName("Banjo", StateSlots.AUTO))
        assertEquals("Banjo.state", StateSlots.fileName("Banjo", 1))
        assertEquals("Banjo.state1", StateSlots.fileName("Banjo", 2))
        assertEquals("Banjo.state9", StateSlots.fileName("Banjo", 10))
        assertEquals("Banjo.state9.png", StateSlots.thumbnailName("Banjo", 10))
    }
}
