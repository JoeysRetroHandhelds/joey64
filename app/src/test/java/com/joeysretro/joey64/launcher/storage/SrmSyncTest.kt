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
        val texts = HashMap<String, String?>()
        override fun text(key: String) = texts[key]
        override fun putText(key: String, value: String?) { texts[key] = value }
    }

    /** A folder whose reads can be turned off, as a picked folder Android can't read at a start. */
    private class Blinkable(val d: DirSpace) : FileSpace by d {
        var unreadable = false
        override fun openRead(path: List<String>) = if (unreadable) null else d.openRead(path)
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

    /** Code audit 4, B-H1: a game started while the folder couldn't be read; its blank save never goes over the real one. */
    @Test fun neverPulledWorkingCopyDoesNotReplaceTheFolderCopy() {
        val space = DirSpace(tmp.newFolder("picked"))
        val work = tmp.newFolder("work")
        val st = MapStamps()
        val real = byteArrayOf(1, 2, 3, 4)
        File(space.root, "saves").mkdirs(); File(space.root, "saves/Game.srm").writeBytes(real)
        val blank = File(work, "Game.srm").apply { writeBytes(byteArrayOf(0, 0, 0, 0)) }
        SrmSync.pushFile(space, work, "Game.srm", st)
        assertArrayEquals(real, File(space.root, "saves/Game.srm").readBytes())
        val kept = File(space.root, "backups").listFiles { f -> f.name.startsWith("Game ") && f.name.endsWith(".srm") }.orEmpty()
        assertEquals(1, kept.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), kept[0].readBytes())
        // Pushed again unchanged: no second copy.
        SrmSync.pushFile(space, work, "Game.srm", st)
        assertEquals(1, File(space.root, "backups").listFiles { f -> f.name.endsWith(".srm") }.orEmpty().size)
        // The game saves on (every push sees a new copy): still one backup, holding the newest (the peer's review of B-H1).
        for (i in 1..3) {
            blank.writeBytes(byteArrayOf(0, 0, 0, i.toByte())); blank.setLastModified(blank.lastModified() + 2000L * i)
            SrmSync.pushFile(space, work, "Game.srm", st)
        }
        val one = File(space.root, "backups").listFiles { f -> f.name.endsWith(".srm") }.orEmpty()
        assertEquals(1, one.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 3), one[0].readBytes())
        assertArrayEquals(real, File(space.root, "saves/Game.srm").readBytes())
        // A start whose pull finds nothing to read keeps its own backup: the one before stays.
        val blind = Blinkable(space).apply { unreadable = true }
        SrmSync.pullFile(blind, work, "Game.srm", st)
        blank.writeBytes(byteArrayOf(0, 0, 9, 9)); blank.setLastModified(blank.lastModified() + 10_000L)
        SrmSync.pushFile(space, work, "Game.srm", st)
        assertEquals(2, File(space.root, "backups").listFiles { f -> f.name.endsWith(".srm") }.orEmpty().size)
        // The next start brings the real save back, and then it's in step.
        SrmSync.pullFile(space, work, "Game.srm", st)
        assertArrayEquals(real, blank.readBytes())
        assertTrue(st.get("Game") > 0)
    }

    @Test fun neverPulledButSameBytesIsJustInStep() {
        val space = DirSpace(tmp.newFolder("picked"))
        val work = tmp.newFolder("work")
        val st = MapStamps()
        File(space.root, "saves").mkdirs(); File(space.root, "saves/Game.srm").writeBytes(byteArrayOf(5, 6))
        File(work, "Game.srm").writeBytes(byteArrayOf(5, 6))
        SrmSync.pushFile(space, work, "Game.srm", st)
        assertEquals(File(work, "Game.srm").lastModified(), st.get("Game"))
        assertFalse(File(space.root, "backups").exists())
    }

    /** The stale push: in step once, then another device saved to the folder while this one played its old copy. */
    @Test fun folderThatMovedOnIsNotWrittenOver() {
        val space = DirSpace(tmp.newFolder("picked"))
        val work = tmp.newFolder("work")
        val st = MapStamps()
        val f = File(work, "Game.srm").apply { writeBytes(byteArrayOf(1, 1)) }
        SrmSync.pushFile(space, work, "Game.srm", st)
        // Another device saves there; this one starts while the folder can't be read and plays its old copy.
        val theirs = byteArrayOf(2, 2, 2)
        File(space.root, "saves/Game.srm").apply { writeBytes(theirs); setLastModified(lastModified() + 5000) }
        SrmSync.pullFile(Blinkable(space).apply { unreadable = true }, work, "Game.srm", st)
        f.writeBytes(byteArrayOf(1, 1, 7)); f.setLastModified(f.lastModified() + 9000)
        SrmSync.pushFile(space, work, "Game.srm", st)
        assertArrayEquals(theirs, File(space.root, "saves/Game.srm").readBytes())
        val kept = File(space.root, "backups").listFiles { x -> x.name.endsWith(".srm") }.orEmpty()
        assertEquals(1, kept.size)
        assertArrayEquals(byteArrayOf(1, 1, 7), kept[0].readBytes())
        // The next start brings the other device's save in.
        SrmSync.pullFile(space, work, "Game.srm", st)
        assertArrayEquals(theirs, f.readBytes())
    }

    /** A sync app writing the same bytes again gives the folder's copy a new time: that's no other device's save. */
    @Test fun folderRewrittenWithTheSameBytesIsStillPushedOver() {
        val space = DirSpace(tmp.newFolder("picked"))
        val work = tmp.newFolder("work")
        val st = MapStamps()
        val f = File(work, "Game.srm").apply { writeBytes(byteArrayOf(1, 1)) }
        SrmSync.pushFile(space, work, "Game.srm", st)
        File(space.root, "saves/Game.srm").apply { setLastModified(lastModified() + 5000) }
        f.writeBytes(byteArrayOf(3, 3)); f.setLastModified(f.lastModified() + 9000)
        SrmSync.pushFile(space, work, "Game.srm", st)
        assertArrayEquals(byteArrayOf(3, 3), File(space.root, "saves/Game.srm").readBytes())
        assertFalse(File(space.root, "backups").exists())
    }

    /** The working copy is newer and the folder moved on too: the newer copy is kept aside and the folder's comes in. */
    @Test fun newerWorkingCopyAndMovedFolderAtStart() {
        val space = DirSpace(tmp.newFolder("picked"))
        val work = tmp.newFolder("work")
        val st = MapStamps()
        val f = File(work, "Game.srm").apply { writeBytes(byteArrayOf(1, 1)) }
        SrmSync.pushFile(space, work, "Game.srm", st)
        File(space.root, "saves/Game.srm").apply { writeBytes(byteArrayOf(2, 2, 2)); setLastModified(lastModified() + 5000) }
        f.writeBytes(byteArrayOf(1, 1, 7)); f.setLastModified(f.lastModified() + 9000)
        SrmSync.pullFile(space, work, "Game.srm", st)
        assertArrayEquals(byteArrayOf(2, 2, 2), f.readBytes())
        assertArrayEquals(byteArrayOf(1, 1, 7), File(space.root, "backups").listFiles { x -> x.name.endsWith(".srm") }!!.single().readBytes())
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
