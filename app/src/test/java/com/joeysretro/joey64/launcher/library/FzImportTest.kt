package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.DirSpace
import com.joeysretro.joey64.states.StateFormat
import com.joeysretro.joey64.states.StateSlots
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class FzImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bk = "B29599651A13F681C9923D69354BF4A3"
    private val dkr = "4F0E07F0EEAC7E5D7CE3A75461888D03"
    private val other = "3B8585ED03E8DDB89D7DE456317545E7"

    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun state(md5: String, fill: Byte): ByteArray {
        val v = 0x00010800
        val b = ByteArray(FzSaves.stateSizeNeeded(v)) { fill }
        "M64+SAVE".toByteArray(Charsets.ISO_8859_1).copyInto(b)
        b[8] = 0; b[9] = 1; b[10] = 8; b[11] = 0
        md5.lowercase().toByteArray(Charsets.ISO_8859_1).copyInto(b, 12)
        return b
    }

    private fun put(root: File, path: String, bytes: ByteArray, modified: Long = 1_700_000_000_000L) =
        File(root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes); setLastModified(modified) }

    /** FZ's external game data as Joey's: `<picked>/GameData/<game> <MD5>/...`. */
    private fun fzFolder(): File {
        val fz = tmp.newFolder("M64Plus")
        val g = "GameData/Banjo-Kazooie (U) $bk"
        put(fz, "$g/SramData/Banjo-Kazooie (U) (V1.0) [!].eep", ByteArray(0x200) { 7 })
        put(fz, "$g/SramData/Banjo-Kazooie (U) (V1.0) [!].mpk", ByteArray(0x20000) { 0 }.also { it[5] = 9 })
        put(fz, "$g/SlotSaves/Banjo-Kazooie (U) (V1.0) [!].st0", gz(state(bk, 1)))
        put(fz, "$g/SlotSaves/Banjo-Kazooie (U) (V1.0) [!].st3", gz(state(bk, 2)))
        put(fz, "$g/AutoSaves/2026-06-25-13-40-04.v2.sav", gz(state(bk, 3)))
        put(fz, "$g/AutoSaves/2026-06-25-13-40-04.v2.sav.complete", ByteArray(0))
        // Newer, but never finished (no .complete): not taken.
        put(fz, "$g/AutoSaves/2026-08-03-12-27-51.v2.sav", gz(state(bk, 4)))
        put(fz, "$g/CoreConfig/mupen64plus.cfg", "x".toByteArray())
        put(fz, "GameData/Diddy Kong Racing (U) $dkr/SramData/Diddy Kong Racing (U) (M2) (V1.0) [!].eep", ByteArray(0x800) { 5 })
        put(fz, "GameData/BODY HARVEST (U) $other/SramData/Body Harvest (U) [!].eep", ByteArray(0x200) { 1 })
        put(fz, "GameData/Not a game/SramData/x.eep", ByteArray(0x200))
        return fz
    }

    private val names = mapOf(bk to "Banjo-Kazooie (USA) (Rev 1)", dkr to "Diddy Kong Racing (USA) (En,Fr)")

    @Test fun findsGamesInGameDataOrInThePickedFolderItself() {
        val fz = fzFolder()
        for (picked in listOf(fz, File(fz, "GameData"))) {
            val found = FzImport.find(DirSpace(picked))
            assertEquals(listOf(other, bk, dkr).sorted(), found.games.map { it.md5 }.sorted())
            val b = found.games.first { it.md5 == bk }
            assertEquals(listOf(FzSaves.Part.Eeprom, FzSaves.Part.Mempaks), b.parts.map { it.part })
            // .st0 is Slot 1, .st3 Slot 4; the finished auto save is the Auto save state.
            assertEquals(listOf(StateSlots.AUTO, 1, 4), b.states.map { it.slot })
            assertTrue(b.states.first { it.slot == StateSlots.AUTO }.path.last().startsWith("2026-06-25"))
        }
    }

    @Test fun planMatchesByMd5AndLeavesOtherGamesOut() {
        val fz = DirSpace(fzFolder())
        val plan = FzImport.plan(fz, FzImport.find(fz), names, DirSpace(tmp.newFolder("joey")))
        assertEquals(listOf(other), plan.notInLibrary.map { it.md5 })
        assertEquals(2, plan.saves)
        assertEquals(3, plan.states)
        assertTrue(plan.clashes.isEmpty() && plan.alreadyThere.isEmpty())
        assertEquals(setOf("Banjo-Kazooie (USA) (Rev 1).srm", "Banjo-Kazooie (USA) (Rev 1).state", "Banjo-Kazooie (USA) (Rev 1).state3",
            "Banjo-Kazooie (USA) (Rev 1).state.auto", "Diddy Kong Racing (USA) (En,Fr).srm"), plan.toCopy.map { it.name }.toSet())
    }

    @Test fun writesTheSaveConvertedAndTheStatesForOurCore() {
        val fzDir = fzFolder()
        val fz = DirSpace(fzDir)
        val joeyDir = tmp.newFolder("joey")
        val joey = DirSpace(joeyDir)
        val plan = FzImport.plan(fz, FzImport.find(fz), names, joey)
        for (item in plan.toCopy) assertTrue(item.name, FzImport.write(fz, joey, item, replace = false))
        val srm = File(joeyDir, "saves/Banjo-Kazooie (USA) (Rev 1).srm").readBytes()
        assertEquals(FzSaves.SRM_SIZE, srm.size)
        assertArrayEquals(ByteArray(0x200) { 7 }, srm.copyOfRange(0, 0x200))
        assertEquals(9.toByte(), srm[FzSaves.MEMPAK_AT + 5])
        val st = File(joeyDir, "states/Banjo-Kazooie (USA) (Rev 1).state3").readBytes()
        assertArrayEquals(state(bk, 2), StateFormat.unwrap(st))
        assertArrayEquals(state(bk, 3), StateFormat.unwrap(File(joeyDir, "states/Banjo-Kazooie (USA) (Rev 1).state.auto").readBytes()))
        // FZ's folder is only read.
        assertTrue(File(fzDir, "GameData/Banjo-Kazooie (U) $bk/SlotSaves/Banjo-Kazooie (U) (V1.0) [!].st3").exists())
        // Again: everything is already there.
        val again = FzImport.plan(fz, FzImport.find(fz), names, joey)
        assertEquals(5, again.alreadyThere.size)
        assertTrue(again.toCopy.isEmpty() && again.clashes.isEmpty())
    }

    @Test fun aDifferentFileIsAClashAndOnlyReplacedWhenAsked() {
        val fz = DirSpace(fzFolder())
        val joeyDir = tmp.newFolder("joey")
        val ours = ByteArray(FzSaves.SRM_SIZE) { 3 }
        put(joeyDir, "saves/Diddy Kong Racing (USA) (En,Fr).srm", ours)
        val joey = DirSpace(joeyDir)
        val plan = FzImport.plan(fz, FzImport.find(fz), names, joey)
        val clash = plan.clashes.single()
        assertEquals("Diddy Kong Racing (USA) (En,Fr).srm", clash.item.name)
        assertFalse(FzImport.write(fz, joey, clash.item, replace = false))
        assertArrayEquals(ours, File(joeyDir, "saves/Diddy Kong Racing (USA) (En,Fr).srm").readBytes())
        assertTrue(FzImport.write(fz, joey, clash.item, replace = true))
        val now = File(joeyDir, "saves/Diddy Kong Racing (USA) (En,Fr).srm").readBytes()
        // FZ's EEPROM in, our other parts kept.
        assertArrayEquals(ByteArray(0x800) { 5 }, now.copyOfRange(0, 0x800))
        assertTrue((FzSaves.MEMPAK_AT until FzSaves.SRM_SIZE).all { now[it] == 3.toByte() })
    }

    @Test fun aStateOurCoreCantReadIsNotWritten() {
        val fzDir = tmp.newFolder("fz")
        put(fzDir, "Banjo-Kazooie (U) $bk/SlotSaves/b.st0", "not gzip".toByteArray())
        val fz = DirSpace(fzDir)
        val joey = DirSpace(tmp.newFolder("joey"))
        val item = FzImport.plan(fz, FzImport.find(fz), names, joey).toCopy.single()
        assertEquals(DataKind.States, item.kind)
        assertFalse(FzImport.write(fz, joey, item, replace = false))
    }
}
