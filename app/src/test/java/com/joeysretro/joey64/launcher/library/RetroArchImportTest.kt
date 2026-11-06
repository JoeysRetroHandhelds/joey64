package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.DirSpace
import com.joeysretro.joey64.launcher.storage.FileCopy
import com.joeysretro.joey64.launcher.storage.ReadOnlySpace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

class RetroArchImportTest {
    @get:Rule val tmp = TemporaryFolder()

    // RetroArch's state formats, written here from its source (tasks/task_save.c, rzip_stream.c).

    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
    private fun le64(n: Long) = le32(n.toInt()) + le32((n shr 32).toInt())
    private fun pad8(b: ByteArray) = b + ByteArray((8 - b.size % 8) % 8)

    private fun coreData(magic: String = "M64+SAVE", size: Int = 3000) =
        magic.toByteArray(Charsets.ISO_8859_1) + ByteArray(size) { (it * 13).toByte() }

    private fun rastate(vararg chunks: Pair<String, ByteArray>): ByteArray {
        var out = "RASTATE".toByteArray() + byteArrayOf(1)
        for ((tag, data) in chunks) out += tag.toByteArray() + le32(data.size) + pad8(data)
        return out + "END ".toByteArray() + le32(0)
    }

    private fun rzip(data: ByteArray, chunkSize: Int = 1000): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("#RZIPv".toByteArray() + byteArrayOf(1) + "#".toByteArray())
        out.write(le32(chunkSize)); out.write(le64(data.size.toLong()))
        for (at in data.indices step chunkSize) {
            val d = Deflater().apply { setInput(data, at, minOf(chunkSize, data.size - at)); finish() }
            val buf = ByteArray(chunkSize * 2 + 64)
            val n = d.deflate(buf)
            out.write(le32(n)); out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun n64(b: ByteArray) = RetroArchImport.isN64State(ByteArrayInputStream(b))

    @Test fun recognisesEveryStateLayout() {
        assertTrue(n64(rzip(rastate("MEM " to coreData()))))            // RetroArch 1.15+, compressed (default)
        assertTrue(n64(rastate("MEM " to coreData())))                  // uncompressed
        assertTrue(n64(rzip(coreData())))                               // older RetroArch: core data only
        assertTrue(n64(coreData()))
        // A chunk RetroArch adds before MEM in some builds is skipped over, across RZIP chunks.
        assertTrue(n64(rzip(rastate("ACHV" to ByteArray(2501) { 7 }, "MEM " to coreData()), chunkSize = 700)))
    }

    @Test fun otherCoresStatesAreNot() {
        assertFalse(n64(rzip(rastate("MEM " to coreData("#!s9xsnp")))))
        assertFalse(n64(rzip(coreData("FCEUX\u0000\u0000\u0000"))))
        assertFalse(n64(rastate("ACHV" to ByteArray(16))))
        assertFalse(n64("#RZIPv\u0001#".toByteArray()))
        assertFalse(n64(ByteArray(0)))
    }

    // A RetroArch folder on disk

    private fun dir(name: String) = DirSpace(tmp.newFolder(name))
    private fun DirSpace.put(path: String, bytes: ByteArray, modified: Long = 1_000_000) =
        File(root, path).apply { parentFile?.mkdirs(); writeBytes(bytes); setLastModified(modified) }
    private fun srm(seed: Int) = ByteArray(RetroArchImport.N64_SRM_SIZE.toInt()) { (it + seed).toByte() }
    private val n64State = rzip(rastate("MEM " to coreData()))
    private val snesState = rzip(rastate("MEM " to coreData("#!s9xsnp")))

    @Test fun findsN64FilesHoweverRetroArchSortsThem() {
        val ra = dir("RetroArch")
        ra.put("saves/Mupen64Plus-Next/Super Mario 64 (USA).srm", srm(1), modified = 2_000_000)
        ra.put("saves/Super Mario 64 (USA).srm", srm(2), modified = 1_000_000)          // older flat copy
        ra.put("saves/n64/Mario Kart 64 (USA).srm", srm(3))                              // sorted by content folder
        ra.put("saves/Mupen64Plus-Next/n64/Banjo-Kazooie (USA).srm", srm(4))             // by core and content
        ra.put("saves/Mupen64Plus-Next/Paper Mario (USA).sync-conflict-20261003-171037-X6EUELL.srm", srm(5))
        ra.put("saves/Snes9x/Chrono Trigger (USA).srm", ByteArray(8192))
        ra.put("saves/.stversions/Super Mario 64 (USA)~20260101.srm", srm(6))
        ra.put("saves/Mupen64Plus/Super Mario 64 (U) [!]-20B854B2.eep", ByteArray(512))
        ra.put("states/Mupen64Plus-Next/Super Mario 64 (USA).state.auto", n64State)
        ra.put("states/Mupen64Plus-Next/Super Mario 64 (USA).state", n64State)
        ra.put("states/Mupen64Plus-Next/Super Mario 64 (USA).state.png", byteArrayOf(1, 2, 3))
        ra.put("states/Mupen64Plus-Next/Super Mario 64 (USA).state3", n64State)
        ra.put("states/Snes9x/Chrono Trigger (USA).state.auto", snesState)
        ra.put("states/Snes9x/notes.txt", byteArrayOf(1))

        val found = RetroArchImport.find(ra)

        assertEquals(listOf("Banjo-Kazooie (USA).srm", "Mario Kart 64 (USA).srm", "Super Mario 64 (USA).srm"), found.saves.map { it.name })
        assertEquals(listOf("saves", "Mupen64Plus-Next", "Super Mario 64 (USA).srm"), found.saves.last().path)
        assertEquals(listOf(listOf("saves", "Super Mario 64 (USA).srm")), found.olderCopies.map { it.path })
        assertEquals(
            listOf("Super Mario 64 (USA).state", "Super Mario 64 (USA).state.auto", "Super Mario 64 (USA).state3"),
            found.states.map { it.name },
        )
        assertEquals("Super Mario 64 (USA).state.png", found.states.first().thumbnail?.name)
        assertEquals(listOf("Super Mario 64 (U) [!]-20B854B2.eep"), found.otherFormatSaves.map { it.name })
    }

    @Test fun aSavesFolderPickedByItself() {
        val ra = dir("saves")
        ra.put("Mupen64Plus-Next/Super Mario 64 (USA).srm", srm(1))
        ra.put("Super Mario 64 (USA).state1", n64State)
        val found = RetroArchImport.find(ra)
        assertEquals(1, found.saves.size)
        assertEquals(1, found.states.size)
    }

    @Test fun planSortsNewSameAndClashing() {
        val ra = dir("RetroArch")
        val joey = dir("Joey64")
        ra.put("saves/Mupen64Plus-Next/A.srm", srm(1))
        ra.put("saves/Mupen64Plus-Next/B.srm", srm(2))
        ra.put("saves/Mupen64Plus-Next/C.srm", srm(3), modified = 5_000_000)
        joey.put("saves/B.srm", srm(2))
        joey.put("saves/C.srm", srm(9), modified = 4_000_000)

        val plan = RetroArchImport.plan(ra, RetroArchImport.find(ra), joey)

        assertEquals(listOf("A.srm"), plan.toCopy.map { it.name })
        assertEquals(listOf("B.srm"), plan.alreadyThere.map { it.name })
        assertEquals(listOf("C.srm"), plan.clashes.map { it.file.name })
        assertTrue(plan.clashes.single().theirsNewer)
    }

    @Test fun copiesWithoutTouchingRetroArchAndReplacesOnlyWhenAsked() {
        val ra = dir("RetroArch")
        val joey = dir("Joey64")
        ra.put("saves/Mupen64Plus-Next/A.srm", srm(1))
        ra.put("saves/Mupen64Plus-Next/C.srm", srm(3))
        ra.put("states/Mupen64Plus-Next/A.state", n64State)
        ra.put("states/Mupen64Plus-Next/A.state.png", byteArrayOf(9, 9))
        joey.put("saves/C.srm", srm(9))
        val before = ra.root.walkTopDown().filter { it.isFile }.associate { it.path to (it.readBytes().toList() to it.lastModified()) }

        val found = RetroArchImport.find(ra)
        val plan = RetroArchImport.plan(ra, found, joey)
        val r = RetroArchImport.copy(ra, joey, plan.toCopy, replace = false)
        assertEquals(2, r.copied)
        assertArrayEquals(srm(1), File(joey.root, "saves/A.srm").readBytes())
        assertArrayEquals(n64State, File(joey.root, "states/A.state").readBytes())
        assertArrayEquals(byteArrayOf(9, 9), File(joey.root, "states/A.state.png").readBytes())

        // The clash is left alone unless the user chose RetroArch's.
        assertEquals(0, RetroArchImport.copy(ra, joey, plan.clashes.map { it.file }, replace = false).copied)
        assertArrayEquals(srm(9), File(joey.root, "saves/C.srm").readBytes())
        assertEquals(1, RetroArchImport.copy(ra, joey, plan.clashes.map { it.file }, replace = true).copied)
        assertArrayEquals(srm(3), File(joey.root, "saves/C.srm").readBytes())
        assertFalse(File(joey.root, "saves/C.srm${FileCopy.OLD_SUFFIX}").exists())
        assertFalse(File(joey.root, "saves/C.srm${FileCopy.PART_SUFFIX}").exists())

        val after = ra.root.walkTopDown().filter { it.isFile }.associate { it.path to (it.readBytes().toList() to it.lastModified()) }
        assertEquals(before, after)
    }

    @Test fun retroArchsFolderCantBeWritten() {
        val ra = ReadOnlySpace(dir("RetroArch").apply { put("saves/A.srm", srm(1)) })
        for (write in listOf<() -> Unit>({ ra.openWrite(listOf("x")) }, { ra.delete(listOf("saves", "A.srm")) }, { ra.rename(listOf("saves", "A.srm"), "B.srm") })) {
            assertTrue(runCatching(write).exceptionOrNull() is UnsupportedOperationException)
        }
    }

    /**
     * Joey's own RetroArch folder (`JOEY64_RETROARCH`, e.g. C:/Emulators/RetroArch-Win64), read
     * only. Expected counts from the shell: 104 distinct 296960-byte .srm names (107 files, three
     * flat copies of names also in Mupen64Plus-Next); 108 N64 states in states/Mupen64Plus-Next
     * (checked with a separate Python reader), of which 107 have names RetroArch loads: the other
     * is a Windows copy, "Pokemon Stadium (USA).state (2).auto", rightly left out.
     */
    @Test fun joeysRetroArchFolder() {
        val path = System.getenv("JOEY64_RETROARCH")?.let(::File)
        assumeTrue("set JOEY64_RETROARCH", path?.isDirectory == true)
        val found = RetroArchImport.find(ReadOnlySpace(DirSpace(path!!)))
        val notInNext = found.states.filter { it.path.getOrNull(1) != "Mupen64Plus-Next" }.map { it.path.joinToString("/") }
        val report = "saves ${found.saves.size}, states ${found.states.size} (${found.states.count { it.thumbnail != null }} with a picture), " +
            "older copies ${found.olderCopies.size}, other-format saves ${found.otherFormatSaves.size}; states outside Mupen64Plus-Next: $notInNext"
        File("build/retroarch-import-check.txt").writeText(report + "\n")
        println(report)
        assertEquals(104, found.saves.size)
        assertEquals(3, found.olderCopies.count { it.kind == DataKind.Saves })
        assertEquals(107, found.states.size)
        assertEquals(emptyList<String>(), notInNext)
    }
}
