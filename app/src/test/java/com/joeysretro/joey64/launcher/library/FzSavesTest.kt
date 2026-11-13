package com.joeysretro.joey64.launcher.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class FzSavesTest {
    private fun sha256(b: ByteArray, from: Int = 0, to: Int = b.size) =
        MessageDigest.getInstance("SHA-256").apply { update(b, from, to - from) }.digest().joinToString("") { "%02x".format(it) }

    private fun allFF(b: ByteArray, from: Int, to: Int) = (from until to).all { b[it] == 0xFF.toByte() }

    /** A blank pak as our core writes it (files/saves/Resident Evil 2 (USA).srm on the Odin 2 Portal, all four paks). */
    private val corePak = "da9edb0602aab9f1130efdea82acc4ebbf891669b9308709a0113578076bd994"

    @Test fun theRegionsAreTheLibretroSaveLayout() {
        assertEquals(0, FzSaves.EEPROM_AT)
        assertEquals(0x800, FzSaves.MEMPAK_AT)
        assertEquals(0x20800, FzSaves.SRAM_AT)
        assertEquals(0x28800, FzSaves.FLASH_AT)
        assertEquals(296960, FzSaves.SRM_SIZE)
    }

    @Test fun aBlankSaveIsTheCoresOwnByteForByte() {
        val b = FzSaves.blankSrm()
        assertEquals(FzSaves.SRM_SIZE, b.size)
        assertTrue(allFF(b, 0, FzSaves.MEMPAK_AT))
        assertTrue(allFF(b, FzSaves.SRAM_AT, FzSaves.SRM_SIZE))
        for (i in 0 until FzSaves.MEMPAKS) {
            val at = FzSaves.MEMPAK_AT + i * FzSaves.MEMPAK_SIZE
            assertEquals("pak $i", corePak, sha256(b, at, at + FzSaves.MEMPAK_SIZE))
        }
    }

    @Test fun aFormattedPakHasTheIdBlockChecksums() {
        val mem = ByteArray(FzSaves.MEMPAK_SIZE)
        FzSaves.formatMempak(mem, 0, intArrayOf(0x11223344, 0x55667788, 0x0, 0x7FFFFFFF, -1, 42))
        fun u16(at: Int) = ((mem[at].toInt() and 0xFF) shl 8) or (mem[at + 1].toInt() and 0xFF)
        for (block in intArrayOf(1, 3, 4, 6)) {
            val id = block * 32
            var sum = 0
            for (i in 0 until 28 step 2) sum += u16(id + i)
            assertEquals("block $block", sum and 0xFFFF, u16(id + 28))
            assertEquals("block $block", (0xFFF2 - (sum and 0xFFFF)) and 0xFFFF, u16(id + 30))
        }
        // The index table: pages 5-127 free (3), byte 1 their sum; page 2 a copy of page 1.
        for (p in 5 until 128) assertEquals(3, u16(256 + 2 * p))
        assertEquals((123 * 3) and 0xFF, mem[257].toInt() and 0xFF)
        assertArrayEquals(mem.copyOfRange(256, 512), mem.copyOfRange(512, 768))
    }

    @Test fun aFourKEepromFillsTheStartAndLeavesTheRest() {
        val eep = ByteArray(0x200) { (it * 7).toByte() }
        val out = FzSaves.toSrm(mapOf(FzSaves.Part.Eeprom to eep))
        assertArrayEquals(eep, out.copyOfRange(0, 0x200))
        assertTrue(allFF(out, 0x200, FzSaves.MEMPAK_AT))
        assertEquals(corePak, sha256(out, FzSaves.MEMPAK_AT, FzSaves.MEMPAK_AT + FzSaves.MEMPAK_SIZE))
    }

    @Test fun eachPartLandsInItsRegion() {
        val parts = mapOf(
            FzSaves.Part.Eeprom to ByteArray(0x800) { 1 },
            FzSaves.Part.Mempaks to ByteArray(4 * 0x8000) { 2 },
            FzSaves.Part.Sram to ByteArray(0x8000) { 3 },
            FzSaves.Part.Flash to ByteArray(0x20000) { 4 },
        )
        val out = FzSaves.toSrm(parts)
        for ((part, bytes) in parts) assertArrayEquals(part.name, bytes, out.copyOfRange(part.at, part.at + bytes.size))
    }

    @Test fun ourOtherRegionsStayAsTheyAre() {
        val ours = ByteArray(FzSaves.SRM_SIZE) { 9 }
        val out = FzSaves.toSrm(mapOf(FzSaves.Part.Sram to ByteArray(0x8000) { 3 }), ours)
        assertTrue((0 until FzSaves.SRAM_AT).all { out[it] == 9.toByte() })
        assertTrue((FzSaves.FLASH_AT until FzSaves.SRM_SIZE).all { out[it] == 9.toByte() })
        assertEquals(9.toByte(), ours[FzSaves.SRAM_AT]) // [ours] isn't changed
    }

    @Test(expected = FzSaves.TooBig::class) fun aPartTooBigIsRefused() {
        FzSaves.toSrm(mapOf(FzSaves.Part.Eeprom to ByteArray(0x801)))
    }

    @Test fun partsAreKnownByExtension() {
        assertEquals(FzSaves.Part.Eeprom, FzSaves.Part.of("Banjo-Kazooie (U) (V1.0) [!].eep"))
        assertEquals(FzSaves.Part.Mempaks, FzSaves.Part.of("x.MPK"))
        assertEquals(FzSaves.Part.Sram, FzSaves.Part.of("THE MISSING LINK (U).sra"))
        assertEquals(FzSaves.Part.Flash, FzSaves.Part.of("Paper Mario (U).fla"))
        assertNull(FzSaves.Part.of("x.st0"))
    }

    @Test fun theFolderNameGivesTheRomMd5() {
        assertEquals("B29599651A13F681C9923D69354BF4A3", FzSaves.romMd5("Banjo-Kazooie (U) B29599651A13F681C9923D69354BF4A3"))
        assertEquals("05194D49C14E52055DF72A54D40791E1", FzSaves.romMd5("05194d49c14e52055df72a54d40791e1"))
        assertNull(FzSaves.romMd5("SUPER MARIO 64 (U)"))
        assertNull(FzSaves.romMd5("X05194D49C14E52055DF72A54D40791E1"))
    }

    private fun state(version: Int, size: Int): ByteArray {
        val b = ByteArray(size)
        "M64+SAVE".toByteArray(Charsets.ISO_8859_1).copyInto(b)
        b[8] = (version ushr 24).toByte(); b[9] = (version ushr 16).toByte(); b[10] = (version ushr 8).toByte(); b[11] = version.toByte()
        "05194d49c14e52055df72a54d40791e1".toByteArray(Charsets.ISO_8859_1).copyInto(b, 12)
        return b
    }

    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test fun anFzStateUnzipsForOurCore() {
        val v = 0x00010800
        val raw = state(v, FzSaves.stateSizeNeeded(v))
        val back = FzSaves.stateForCore(ByteArrayInputStream(gz(raw)))
        assertNotNull(back)
        assertArrayEquals(raw, back)
        assertEquals(FzSaves.StateInfo(v, "05194D49C14E52055DF72A54D40791E1"), FzSaves.stateInfo(back!!))
    }

    @Test fun badStatesAreRefused() {
        val v = 0x00010800
        val need = FzSaves.stateSizeNeeded(v)
        assertNull("cut short", FzSaves.stateForCore(ByteArrayInputStream(gz(state(v, need - 1)))))
        assertNull("newer than ours", FzSaves.stateForCore(ByteArrayInputStream(gz(state(0x00020000, need + 8192)))))
        assertNull("not gzip", FzSaves.stateForCore(ByteArrayInputStream(state(v, need))))
        assertNull("not a state", FzSaves.stateForCore(ByteArrayInputStream(gz(ByteArray(need)))))
        assertNull("over the limit", FzSaves.stateForCore(ByteArrayInputStream(gz(state(v, need))), limit = need - 1))
    }
}
