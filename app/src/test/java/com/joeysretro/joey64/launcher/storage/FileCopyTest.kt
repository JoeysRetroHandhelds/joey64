package com.joeysretro.joey64.launcher.storage

import com.joeysretro.joey64.saves.AppBackup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Checked writes from a stream (backup restore, settings copy, save push) and the settings copy's zip check (code audit 2026-11-07). */
class FileCopyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun space() = DirSpace(tmp.newFolder())
    private fun DirSpace.file(path: String) = File(root, path)

    @Test fun writesANewFile() {
        val s = space()
        assertTrue(FileCopy.writeChecked("new save".byteInputStream(), s, listOf("saves", "Mario.srm")))
        assertEquals("new save", s.file("saves/Mario.srm").readText())
        assertEquals(listOf("Mario.srm"), s.file("saves").list()!!.toList())
    }

    @Test fun replacesAnOldFileAndLeavesNothingElse() {
        val s = space()
        s.file("saves").mkdirs(); s.file("saves/Mario.srm").writeText("old save")
        assertTrue(FileCopy.writeChecked("new save".byteInputStream(), s, listOf("saves", "Mario.srm")))
        assertEquals("new save", s.file("saves/Mario.srm").readText())
        assertEquals(listOf("Mario.srm"), s.file("saves").list()!!.toList())
    }

    @Test fun aStreamThatBreaksKeepsTheOldFileWhole() {
        val s = space()
        s.file("saves").mkdirs(); s.file("saves/Mario.srm").writeText("old save")
        val breaking = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 100) 'x'.code else throw IOException("card out")
        }
        assertFalse(FileCopy.writeChecked(breaking, s, listOf("saves", "Mario.srm")))
        assertEquals("old save", s.file("saves/Mario.srm").readText())
        assertEquals(listOf("Mario.srm"), s.file("saves").list()!!.toList())
    }

    @Test fun leavesTheStreamOpenForTheNextZipEntry() {
        val zipBytes = zip("a.srm" to "first".toByteArray(), "b.srm" to "second".toByteArray())
        val s = space()
        ZipInputStream(zipBytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) { assertTrue(FileCopy.writeChecked(z, s, listOf("saves", e.name))); e = z.nextEntry }
        }
        assertEquals("first", s.file("saves/a.srm").readText())
        assertEquals("second", s.file("saves/b.srm").readText())
    }

    @Test fun aWholeSettingsCopyReadsWhole() {
        assertTrue(AppBackup.readsWhole(zip(AppBackup.MANIFEST to "{}".toByteArray(), "library.json" to ByteArray(5000) { (it % 7).toByte() })))
    }

    @Test fun aCutShortOrForeignCopyDoesNot() {
        val whole = zip(AppBackup.MANIFEST to "{}".toByteArray(), "library.json" to ByteArray(50_000) { (it * 31).toByte() })
        assertFalse(AppBackup.readsWhole(whole.copyOf(whole.size / 2)))
        assertFalse(AppBackup.readsWhole(zip("library.json" to "{}".toByteArray())))  // no manifest
        assertFalse(AppBackup.readsWhole("not a zip".toByteArray()))
        assertArrayEquals(whole, whole.copyOf())
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }
}
