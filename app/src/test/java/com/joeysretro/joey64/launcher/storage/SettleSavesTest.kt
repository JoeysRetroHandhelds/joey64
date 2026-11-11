package com.joeysretro.joey64.launcher.storage

import com.joeysretro.joey64.saves.BackupWhen
import com.joeysretro.joey64.saves.SaveBackups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A data folder move onto a folder that already has a save of the same name (code audit 3, H1): Joey64's copy takes
 * its place and the one that was there goes to the new folder's backups, so nothing is replaced unseen.
 */
class SettleSavesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).parse("2026-11-11 0905")!!.time
    private fun space(name: String) = DirSpace(tmp.newFolder(name))
    private fun DirSpace.put(path: String, text: String) = File(root, path).apply { parentFile?.mkdirs(); writeText(text) }
    private fun DirSpace.read(path: String) = File(root, path).takeIf { it.exists() }?.readText()
    private fun DirSpace.backups() = File(root, "backups").list().orEmpty().filter { it != ".nomedia" }.sorted()

    @Test fun joey64sSaveWinsAndTheOtherGoesToBackups() {
        val from = space("internal")
        val to = space("picked")
        from.put("saves/Zelda.srm", "mine")
        to.put("saves/Zelda.srm", "theirs")

        val moved = DataMover.move(from, to)
        assertEquals(listOf("saves/Zelda.srm"), moved.conflicts)
        val r = DataMover.settleSaves(from, to, moved, fromIsWorkingCopy = true, now = now)

        assertEquals(1, r.keptAside)
        assertTrue(r.conflicts.isEmpty())
        assertEquals("mine", to.read("saves/Zelda.srm"))
        assertEquals(listOf("Zelda 2026-11-11 0905.srm"), to.backups())
        assertEquals("theirs", to.read("backups/Zelda 2026-11-11 0905.srm"))
        assertNull(from.read("saves/Zelda.srm"))
        assertFalse(File(from.root, "saves").exists())
    }

    @Test fun otherKindsStayAsConflicts() {
        val from = space("internal")
        val to = space("picked")
        from.put("states/Zelda.state1", "mine")
        to.put("states/Zelda.state1", "theirs")

        val r = DataMover.settleSaves(from, to, DataMover.move(from, to), fromIsWorkingCopy = true, now = now)

        assertEquals(listOf("states/Zelda.state1"), r.conflicts)
        assertEquals(0, r.keptAside)
        assertEquals("mine", from.read("states/Zelda.state1"))
        assertEquals("theirs", to.read("states/Zelda.state1"))
    }

    @Test fun aSaveThatCantBeKeptAsideStaysWhereItWas() {
        val from = space("picked1")
        val to = space("picked2")
        from.put("saves/Zelda.srm", "mine")
        to.put("saves/Zelda.srm", "theirs")
        // The new folder's backups can't be written: a file where the folder would be.
        to.put("backups", "not a folder")

        val r = DataMover.settleSaves(from, to, DataMover.move(from, to), fromIsWorkingCopy = false, now = now)

        assertEquals(listOf("saves/Zelda.srm"), r.conflicts)
        assertEquals("theirs", to.read("saves/Zelda.srm"))
        assertEquals("mine", from.read("saves/Zelda.srm"))
    }

    @Test fun backUpSaysWhichFilesFailed() {
        val space = space("picked")
        space.put("saves/Zelda.srm", "save")
        space.put("backups", "not a folder")

        val run = SaveBackups.backUp(space, "Zelda", BackupWhen.OnStart, 5, now)

        assertFalse(run.ok)
        assertEquals(listOf("Zelda.srm"), run.failed)
        assertFalse(SaveBackups.keepAside(space, "Zelda.srm", now))
    }

    @Test fun keepAsideIsNeverPruned() {
        val space = space("picked")
        space.put("saves/Zelda.srm", "one")
        for (i in 0 until 4) {
            space.put("saves/Zelda.srm", "copy $i")
            assertTrue(SaveBackups.keepAside(space, "Zelda.srm", now))
        }
        assertEquals(4, space.backups().size)
        assertTrue(SaveBackups.isSaveFile("Zelda.ndd.disk_save"))
        assertFalse(SaveBackups.isSaveFile(".srm"))
        assertFalse(SaveBackups.isSaveFile("Zelda.state1"))
    }
}
