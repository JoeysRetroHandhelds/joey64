package com.joeysretro.joey64.states

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Each game's own recovery state (Joey 2026-11-12, F3), and the move of the old single one into its game's folder. */
class RecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(dir: File, name: String, at: Long, bytes: ByteArray) {
        dir.mkdirs()
        File(dir, "state").writeBytes(bytes)
        File(dir, "info.properties").writeText("save_name=$name\nsaved_at=$at\n")
    }

    @Test fun oldSingleStateMovesUnchangedIntoItsGamesFolder() {
        val root = tmp.newFolder("recovery")
        val bytes = ByteArray(5000) { (it * 7).toByte() }
        write(root, "Legend of Zelda, The - Ocarina of Time (USA)", 100L, bytes)
        val found = GameStates.Recovery.allIn(root)
        assertEquals(1, found.size)
        assertEquals("Legend of Zelda, The - Ocarina of Time (USA)", found[0].saveName)
        assertEquals(100L, found[0].savedAt)
        assertEquals(5000L, found[0].size)
        assertFalse(File(root, "state").exists())
        assertFalse(File(root, "info.properties").exists())
        val moved = File(GameStates.Recovery.dirIn(root, found[0].saveName), "state")
        assertArrayEquals(bytes, moved.readBytes())
    }

    @Test fun clearingOneGameKeepsTheOthers() {
        val root = tmp.newFolder("recovery")
        write(GameStates.Recovery.dirIn(root, "OoT"), "OoT", 100L, byteArrayOf(1))
        write(GameStates.Recovery.dirIn(root, "MK64"), "MK64", 200L, byteArrayOf(2))
        assertEquals(listOf("MK64", "OoT"), GameStates.Recovery.allIn(root).map { it.saveName })
        GameStates.Recovery.clear(GameStates.Recovery.dirIn(root, "MK64"))
        assertNull(GameStates.Recovery.findIn(root, "MK64"))
        assertTrue(GameStates.Recovery.findIn(root, "OoT") != null)
        assertEquals(listOf("OoT"), GameStates.Recovery.allIn(root).map { it.saveName })
    }

    @Test fun oldStateOlderThanTheGamesOwnIsDropped() {
        val root = tmp.newFolder("recovery")
        write(GameStates.Recovery.dirIn(root, "OoT"), "OoT", 300L, byteArrayOf(9))
        write(root, "OoT", 100L, byteArrayOf(1))
        val r = GameStates.Recovery.findIn(root, "OoT")!!
        assertEquals(300L, r.savedAt)
        assertFalse(File(root, "state").exists())
    }
}
