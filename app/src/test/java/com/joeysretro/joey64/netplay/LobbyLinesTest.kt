package com.joeysretro.joey64.netplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** The lobby's line reader (code audit 3, H6): a line past MAX_LINE bytes is refused instead of read without end. */
class LobbyLinesTest {
    private fun input(s: String) = ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))

    @Test fun readsLinesAndTheLastOneWithoutAnEnd() {
        val i = input("ROOM\t2\tJoey\r\nHELLO\t2\tSam é\nlast")
        assertEquals(listOf("ROOM", "2", "Joey"), LobbyLines.split(LobbyLines.readLine(i)!!))
        assertEquals("HELLO\t2\tSam é", LobbyLines.readLine(i))
        assertEquals("last", LobbyLines.readLine(i))
        assertNull(LobbyLines.readLine(i))
    }

    @Test fun aLineAtTheLimitIsRead() {
        val line = "x".repeat(LobbyLines.MAX_LINE)
        assertEquals(line, LobbyLines.readLine(input(line + "\n")))
    }

    @Test fun aLongerLineIsRefused() {
        val i = input("y".repeat(LobbyLines.MAX_LINE + 1) + "\n")
        try { LobbyLines.readLine(i); fail("read a line past the limit") } catch (e: IOException) { }
    }

    @Test fun aPlayersLineWithFourLongNamesFits() {
        val name = "n".repeat(48)
        val list = (1..4).map { LobbyLines.Entry(it, name, name, "wifi", 999) }
        val line = LobbyLines.players(list)
        assertEquals(true, line.toByteArray(Charsets.UTF_8).size < LobbyLines.MAX_LINE)
        assertEquals(list, LobbyLines.parsePlayers(LobbyLines.readLine(input(line + "\n"))!!))
    }
}
