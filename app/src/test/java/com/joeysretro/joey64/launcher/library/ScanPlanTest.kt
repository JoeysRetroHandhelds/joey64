package com.joeysretro.joey64.launcher.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanPlanTest {
    private val sd = "content://tree/sd"
    private val usb = "content://tree/usb"

    private fun found(name: String, tree: String = sd, size: Long = 100, modified: Long = 1) =
        FoundRom("$tree/$name", tree, name, size, modified)

    private fun known(name: String, tree: String = sd, size: Long = 100, modified: Long = 1, gameId: Long? = 1) =
        KnownFile("$tree/$name", tree, size, modified, gameId)

    @Test fun aFirstScanReadsEverything() {
        val p = ScanPlanner.plan(listOf(found("a.z64"), found("b.zip")), emptyList(), setOf(sd))
        assertEquals(listOf("a.z64", "b.zip"), p.toRead.map { it.name })
        assertEquals(emptyList<FoundRom>(), p.unchanged)
        assertEquals(emptyList<String>(), p.gone)
    }

    @Test fun sameSizeAndDateIsNotReadAgain() {
        val p = ScanPlanner.plan(listOf(found("a.z64"), found("b.zip")), listOf(known("a.z64"), known("b.zip")), setOf(sd))
        assertEquals(emptyList<FoundRom>(), p.toRead)
        assertEquals(listOf("a.z64", "b.zip"), p.unchanged.map { it.name })
    }

    @Test fun aChangedSizeOrDateIsReadAgain() {
        val p = ScanPlanner.plan(
            listOf(found("a.z64", size = 200), found("b.zip", modified = 2), found("c.v64")),
            listOf(known("a.z64"), known("b.zip"), known("c.v64")),
            setOf(sd),
        )
        assertEquals(listOf("a.z64", "b.zip"), p.toRead.map { it.name })
        assertEquals(listOf("c.v64"), p.unchanged.map { it.name })
    }

    @Test fun aFileThatWasntARomStaysUnreadUntilItChanges() {
        val p = ScanPlanner.plan(listOf(found("notes.zip")), listOf(known("notes.zip", gameId = null)), setOf(sd))
        assertEquals(listOf("notes.zip"), p.unchanged.map { it.name })
    }

    @Test fun filesThatLeftAScannedFolderAreGone() {
        val p = ScanPlanner.plan(listOf(found("a.z64")), listOf(known("a.z64"), known("deleted.z64")), setOf(sd))
        assertEquals(listOf("$sd/deleted.z64"), p.gone)
    }

    @Test fun aFolderThatCouldntBeScannedLosesNothing() {
        // The USB stick is out: its files aren't found, but its folder wasn't scanned either.
        val p = ScanPlanner.plan(
            listOf(found("a.z64")),
            listOf(known("a.z64"), known("b.z64", tree = usb), known("c.z64", tree = usb)),
            setOf(sd),
        )
        assertEquals(emptyList<String>(), p.gone)
    }

    @Test fun theSameNameInTwoFoldersIsTwoFiles() {
        val p = ScanPlanner.plan(listOf(found("a.z64"), found("a.z64", tree = usb)), listOf(known("a.z64")), setOf(sd, usb))
        assertEquals(listOf("$usb/a.z64"), p.toRead.map { it.uri })
        assertEquals(listOf("$sd/a.z64"), p.unchanged.map { it.uri })
    }

    @Test fun titles() {
        assertEquals("Super Mario 64 (USA)", ScanPlanner.titleOf("Super Mario 64 (USA).zip"))
        assertEquals("F-Zero X (USA) (Rev 1)", ScanPlanner.titleOf("F-Zero X (USA) (Rev 1).z64"))
        assertEquals("no extension", ScanPlanner.titleOf("no extension"))
        assertEquals(".z64", ScanPlanner.titleOf(".z64"))
    }
}
