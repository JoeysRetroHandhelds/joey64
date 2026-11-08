package com.joeysretro.joey64.launcher.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** The picked data folder's copies of a game's saves: save RAM and a 64DD disk save (2026-11-07). */
class SrmSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MapStamps : SrmSync.Stamps {
        val m = HashMap<String, Long>()
        override fun get(key: String) = m[key] ?: -1L
        override fun put(key: String, at: Long) { m[key] = at }
    }

    private val name = "SimCity 64 (Japan).ndd.disk_save"
    // Big enough to need streaming in pieces; a real disk save is 64.9 MB.
    private val disk = Random(7).nextBytes(5 * 1024 * 1024 + 123)

    @Test fun namesCoverSaveRamAndDiskSaves() {
        assertEquals(listOf("A.srm", "A.ndd.disk_save", "A.d64.disk_save"), SrmSync.names("A"))
    }

    @Test fun diskSaveGoesOutAndComesBack() {
        val space = DirSpace(tmp.newFolder("picked"))
        val work = tmp.newFolder("work")
        val st = MapStamps()
        File(work, name).writeBytes(disk)
        SrmSync.pushFile(space, work, name, st)
        assertArrayEquals(disk, File(space.root, "saves/$name").readBytes())
        assertEquals(File(work, name).lastModified(), st.get(name))

        // Another device: nothing local yet, so the folder's copy comes in.
        val work2 = tmp.newFolder("work2")
        val st2 = MapStamps()
        SrmSync.pullFile(space, work2, name, st2)
        assertArrayEquals(disk, File(work2, name).readBytes())
        assertFalse(File(work2, "$name.part").exists())
        assertTrue(st2.get(name) > 0)
    }

    @Test fun unchangedIsNotPushedAgain() {
        val space = DirSpace(tmp.newFolder("picked"))
        val work = tmp.newFolder("work")
        val st = MapStamps()
        File(work, name).writeBytes(disk)
        SrmSync.pushFile(space, work, name, st)
        File(space.root, "saves/$name").writeBytes(byteArrayOf(1, 2, 3)) // changed in the folder only
        SrmSync.pushFile(space, work, name, st)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(space.root, "saves/$name").readBytes())
    }

    @Test fun newerWorkingCopyWinsOverPull() {
        val space = DirSpace(tmp.newFolder("picked"))
        val work = tmp.newFolder("work")
        val st = MapStamps()
        val f = File(work, name)
        f.writeBytes(disk)
        SrmSync.pushFile(space, work, name, st)
        // The game wrote to the disk and Joey64 closed before the push.
        val newer = disk.copyOf().also { it[100] = (it[100] + 1).toByte() }
        f.writeBytes(newer)
        f.setLastModified(f.lastModified() + 5000)
        SrmSync.pullFile(space, work, name, st)
        assertArrayEquals(newer, f.readBytes())
        assertArrayEquals(newer, File(space.root, "saves/$name").readBytes())
    }

    @Test fun unchangedFolderCopyIsNotPulledAgain() {
        val space = DirSpace(tmp.newFolder("picked"))
        val work = tmp.newFolder("work")
        val st = MapStamps()
        val f = File(work, name)
        f.writeBytes(disk)
        f.setLastModified(f.lastModified() - 60_000) // a copy would give it a new time
        SrmSync.pushFile(space, work, name, st)
        val at = f.lastModified()
        SrmSync.pullFile(space, work, name, st)
        assertEquals(at, f.lastModified())
        // Changed in the folder (another device): it comes in.
        val other = disk.copyOf().also { it[5] = (it[5] + 1).toByte() }
        File(space.root, "saves/$name").apply { writeBytes(other); setLastModified(lastModified() + 5000) }
        SrmSync.pullFile(space, work, name, st)
        assertArrayEquals(other, f.readBytes())
        // And then it's in step again.
        val at2 = f.lastModified()
        f.setLastModified(at2); SrmSync.pullFile(space, work, name, st)
        assertEquals(at2, f.lastModified())
    }

    @Test fun saveRamKeepsItsOldKey() {
        val space = DirSpace(tmp.newFolder("picked"))
        val work = tmp.newFolder("work")
        val st = MapStamps()
        File(work, "Game.srm").writeBytes(byteArrayOf(9, 9))
        SrmSync.pushFile(space, work, "Game.srm", st)
        assertTrue(st.get("Game") > 0)
    }
}
