package com.joeysretro.joey64.launcher.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/** The data move, on two plain folders (DirSpace), the same code path a picked folder takes. */
class DataMoverTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun space(name: String) = DirSpace(tmp.newFolder(name))
    private fun DirSpace.put(path: String, text: String) = File(root, path).apply { parentFile?.mkdirs(); writeText(text) }
    private fun DirSpace.read(path: String) = File(root, path).takeIf { it.exists() }?.readText()

    @Test fun movesEveryKindAndEmptiesTheOldPlace() {
        val from = space("internal")
        val to = space("picked")
        from.put("saves/Super Mario 64.srm", "save")
        from.put("states/Super Mario 64.state1", "state")
        from.put("screenshots/Super Mario 64/shot 1.png", "png")

        val r = DataMover.move(from, to)

        assertEquals(3, r.moved)
        assertTrue(r.complete)
        assertEquals("save", to.read("saves/Super Mario 64.srm"))
        assertEquals("state", to.read("states/Super Mario 64.state1"))
        assertEquals("png", to.read("screenshots/Super Mario 64/shot 1.png"))
        assertFalse(File(from.root, "saves").exists())
        assertFalse(File(from.root, "screenshots").exists())
    }

    @Test fun leavesOtherAppFilesAlone() {
        // The app's files dir also holds the core's system folder and GPU drivers.
        val from = space("internal")
        from.put("system/mupen64plus.ini", "ini")
        from.put("gpu_driver/libvulkan.so", "so")
        from.put("saves/a.srm", "a")

        DataMover.move(from, space("picked"))

        assertEquals("ini", from.read("system/mupen64plus.ini"))
        assertEquals("so", from.read("gpu_driver/libvulkan.so"))
    }

    @Test fun neverOverwritesADifferentFile() {
        val from = space("internal")
        val to = space("picked")
        from.put("saves/Zelda.srm", "new")
        to.put("saves/Zelda.srm", "older, from RetroArch")

        val r = DataMover.move(from, to)

        assertEquals(listOf("saves/Zelda.srm"), r.conflicts)
        assertFalse(r.complete)
        assertEquals("older, from RetroArch", to.read("saves/Zelda.srm"))
        assertEquals("new", from.read("saves/Zelda.srm"))
    }

    @Test fun recognisesAFileAlreadyMoved() {
        // A move that copied the file but was cut off before removing the old one.
        val from = space("internal")
        val to = space("picked")
        from.put("saves/Zelda.srm", "same")
        to.put("saves/Zelda.srm", "same")

        val r = DataMover.move(from, to)

        assertEquals(0, r.moved)
        assertEquals(1, r.alreadyThere)
        assertTrue(r.complete)
        assertEquals(null, from.read("saves/Zelda.srm"))
    }

    @Test fun replacesAHalfWrittenCopyFromAnEarlierMove() {
        val from = space("internal")
        val to = space("picked")
        from.put("saves/Zelda.srm", "whole save")
        to.put("saves/Zelda.srm${DataMover.PART_SUFFIX}", "whol")

        val r = DataMover.move(from, to)

        assertEquals(1, r.moved)
        assertEquals("whole save", to.read("saves/Zelda.srm"))
        assertFalse(File(to.root, "saves/Zelda.srm${DataMover.PART_SUFFIX}").exists())
    }

    @Test fun aFailedCopyKeepsTheOriginal() {
        val from = space("internal")
        from.put("saves/a.srm", "aaaa")
        from.put("saves/b.srm", "bbbb")
        // A destination that runs out of space on b.srm.
        val to = FullOn("b.srm", DirSpace(tmp.newFolder("picked")))

        val r = DataMover.move(from, to)

        assertEquals(1, r.moved)
        assertEquals(listOf("saves/b.srm"), r.failed)
        assertEquals("bbbb", from.read("saves/b.srm"))
        assertEquals(null, from.read("saves/a.srm"))
        assertEquals("aaaa", File(tmp.root, "picked/saves/a.srm").readText())
        assertFalse(File(tmp.root, "picked/saves/b.srm").exists())
        assertFalse(File(tmp.root, "picked/saves/b.srm${DataMover.PART_SUFFIX}").exists())
    }

    @Test fun nothingToMoveIsComplete() {
        val r = DataMover.move(space("internal"), space("picked"))
        assertEquals(0, r.moved)
        assertTrue(r.complete)
    }

    /** Fails every write of a file whose name starts with [name], as a full card would. */
    private class FullOn(val name: String, val inner: FileSpace) : FileSpace by inner {
        override fun openWrite(path: List<String>): OutputStream {
            if (path.last().startsWith(name)) throw java.io.IOException("no space")
            return inner.openWrite(path)
        }
    }
}
