package com.joeysretro.joey64.saves

import com.joeysretro.joey64.launcher.storage.DirSpace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.random.Random

/** 64DD disk saves in the per-start backups, zipped whole (Joey 2026-11-07: "Zipped whole disk"). */
class DiskBackupsTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun at(s: String) = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).parse(s)!!.time
    private val game = "SimCity 64 (Japan)"
    private val disk = "$game.ndd.disk_save"
    private val ext = ".ndd.disk_save.zip"

    private fun setup(bytes: ByteArray): DirSpace {
        val space = DirSpace(tmp.newFolder())
        File(space.root, "saves").mkdirs()
        File(space.root, "saves/$disk").writeBytes(bytes)
        return space
    }
    private fun backups(space: DirSpace) = File(space.root, "backups").list()!!.filter { it != ".nomedia" }.sorted()

    @Test fun namesKeepSrmAndDiskApart() {
        val names = listOf("$game 2026-11-07 1530.srm", "$game 2026-11-07 1530$ext", "$game 2026-11-07 1530-2$ext", "$game 2026-11-07 1530$ext.part")
        assertEquals(listOf("$game 2026-11-07 1530.srm"), BackupRules.backupsOf(game, names))
        assertEquals(listOf("$game 2026-11-07 1530$ext", "$game 2026-11-07 1530-2$ext"), BackupRules.backupsOf(game, names, ext))
        assertEquals("$game 2026-11-07 1530-3$ext", BackupRules.freeName(game, at("2026-11-07 1530"), names.toSet(), ext))
    }

    @Test fun extraFieldRoundTrips() {
        val sum = 0xDEADBEEFL to 64_931_840L
        assertEquals(sum, DiskZip.parse(DiskZip.extra(sum)))
        assertEquals(null, DiskZip.parse(byteArrayOf(1, 0, 0, 0)))
    }

    @Test fun zipHoldsTheDiskUnderItsOwnNameAndOnlyChangesAreBackedUp() {
        val bytes = Random(7).nextBytes(3_000_000).also { it.fill(0, 1_000_000, 2_900_000) }
        val space = setup(bytes)
        val first = SaveBackups.maybeBackUp(space, game, BackupWhen.OnStart, 5, at("2026-11-07 1530"))
        assertEquals(listOf("$game 2026-11-07 1530$ext"), first)
        ZipFile(File(space.root, "backups/${first[0]}")).use { z ->
            val e = z.entries().toList().single()
            assertEquals(disk, e.name)
            assertArrayEquals(bytes, z.getInputStream(e).readBytes())
        }
        // Unchanged: no new copy.
        assertEquals(emptyList<String>(), SaveBackups.maybeBackUp(space, game, BackupWhen.OnStart, 5, at("2026-11-07 1600")))
        // Changed: a new copy, and the oldest go past keep.
        for ((i, t) in listOf("1601", "1602", "1603").withIndex()) {
            bytes[i] = (bytes[i] + 1).toByte(); File(space.root, "saves/$disk").writeBytes(bytes)
            assertEquals(1, SaveBackups.maybeBackUp(space, game, BackupWhen.OnStart, 2, at("2026-11-07 $t")).size)
        }
        assertEquals(listOf("$game 2026-11-07 1602$ext", "$game 2026-11-07 1603$ext"), backups(space))
    }

    @Test fun srmAndDiskBothBackedUp() {
        val space = setup(ByteArray(1000) { it.toByte() })
        File(space.root, "saves/$game.srm").writeBytes(ByteArray(512) { 3 })
        val made = SaveBackups.maybeBackUp(space, game, BackupWhen.OnStart, 5, at("2026-11-07 1530"))
        assertEquals(listOf("$game 2026-11-07 1530.srm", "$game 2026-11-07 1530$ext"), made)
        assertTrue(File(space.root, "backups/.nomedia").exists())
    }

    @Test fun offMakesNothing() {
        val space = setup(ByteArray(10))
        assertEquals(emptyList<String>(), SaveBackups.maybeBackUp(space, game, BackupWhen.Off, 5, at("2026-11-07 1530")))
    }
}
