package com.joeysretro.joey64.netplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomCodeTest {
    @Test fun roundTrips() {
        for ((ip, port) in listOf("203.0.113.7" to 45000, "192.168.1.23" to 45007, "0.0.0.0" to 45000, "255.255.255.255" to 45003, "10.0.0.2" to 45001)) {
            val code = RoomCode.encode(ip, port)!!
            assertEquals(8, code.length)
            assertTrue(code, code.all { it in RoomCode.CHARS })
            assertEquals(RoomCode.Room(ip, port), RoomCode.decode(code))
            assertEquals(RoomCode.Room(ip, port), RoomCode.decode(RoomCode.shown(code).lowercase()))
        }
    }

    @Test fun shownInTwoGroups() {
        assertEquals("K7Q4 MD2X", RoomCode.shown("k7q4md2x"))
    }

    @Test fun noLettersTheKeyboardLacks() {
        assertTrue(RoomCode.CHARS.none { it in "O0I1" })
        assertEquals(32, RoomCode.CHARS.toSet().size)
    }

    @Test fun refusesWhatItCantHold() {
        assertNull(RoomCode.encode("203.0.113.7", 44999))
        assertNull(RoomCode.encode("203.0.113.7", 45008))
        assertNull(RoomCode.encode("2001:db8::1", 45000))
        assertNull(RoomCode.encode("300.1.1.1", 45000))
        assertNull(RoomCode.decode("K7Q4MD2"))
        assertNull(RoomCode.decode("K7Q4MD2O"))
    }

    /** Netplay5 screen 5: one character typed wrong is always caught by the check. */
    @Test fun everySingleTypoIsCaught() {
        val code = RoomCode.encode("198.51.100.42", 45002)!!
        for (i in code.indices) for (ch in RoomCode.CHARS) {
            if (ch == code[i]) continue
            val typo = code.substring(0, i) + ch + code.substring(i + 1)
            assertNull(typo, RoomCode.decode(typo))
        }
    }

    @Test fun lobbyLines() {
        assertEquals("a b c", LobbyLines.field("a\tb|c"))
        assertEquals(listOf("HELLO", "1", "Sam", "Odin 3", "ab"), LobbyLines.split(LobbyLines.line("HELLO", 1, "Sam", "Odin 3", "ab") + "\r\n"))
        val list = listOf(LobbyLines.Entry(2, "Sam", "Retroid Pocket 5", "wifi", 12), LobbyLines.Entry(1, "Joey", "AYN Thor", "host", null))
        assertEquals(list.sortedBy { it.slot }, LobbyLines.parsePlayers(LobbyLines.players(list)))
        val b = LobbyLines.Beacon(45001, "Joey", "AYN Thor", "Mario Kart 64 (USA)", "0123456789abcdef0123456789abcdef", 2, "K7Q4MD2X")
        assertEquals(b, LobbyLines.parseBeacon(LobbyLines.beacon(b)))
        assertNull(LobbyLines.parseBeacon("RMG something else"))
        assertNull(LobbyLines.parseBeacon(LobbyLines.beacon(b.copy(md5 = "nothex"))))
    }

    /** Netplay5 screen 3: X moves a player to the next number, swapping; 4 wraps to 1. */
    @Test fun movingPlayers() {
        val slots = mapOf("host" to 1, "sam" to 2)
        assertEquals(mapOf("host" to 2, "sam" to 1), LobbyLines.move(slots, "host"))
        assertEquals(mapOf("host" to 1, "sam" to 3), LobbyLines.move(slots, "sam"))
        assertEquals(mapOf("host" to 4, "kim" to 1), LobbyLines.move(mapOf("host" to 1, "kim" to 4), "kim"))
        // PlayTogether keys the host as null (found on the Thor 2026-11-10: X on the host did nothing).
        assertEquals(mapOf<String?, Int>(null to 2, "sam" to 1), LobbyLines.move(mapOf<String?, Int>(null to 1, "sam" to 2), null))
        assertEquals(3, LobbyLines.freeSlot(listOf(1, 2, 4)))
        assertNull(LobbyLines.freeSlot(listOf(1, 2, 3, 4)))
        // A 2-player game's room (Joey 2026-11-10: "Yes, cap it"): two places, X wraps 2 to 1.
        assertNull(LobbyLines.freeSlot(listOf(1, 2), places = 2))
        assertEquals(2, LobbyLines.freeSlot(listOf(1), places = 2))
        assertEquals(1, LobbyLines.nextSlot(2, places = 2))
        assertEquals(3, LobbyLines.nextSlot(2, places = 3))
        assertEquals(mapOf("host" to 2, "sam" to 1), LobbyLines.move(mapOf("host" to 1, "sam" to 2), "sam", places = 2))
    }

    @Test fun publicList() {
        val code = RoomCode.encode("203.0.113.7", 45000)!!
        val rooms = RoomList.parse("""{"rooms":[{"id":"a","name":"Kim","device":"Odin 3","game":"Super Smash Bros. (USA)","md5":"ABCDEF0123456789ABCDEF0123456789","players":2,"code":"$code"},{"id":"b","code":"BADCODE1"}]}""")
        assertEquals(1, rooms.size)
        assertNotNull(rooms[0])
        assertEquals("203.0.113.7", rooms[0].ip); assertEquals(45000, rooms[0].port)
        assertEquals("abcdef0123456789abcdef0123456789", rooms[0].md5)
        // Only an MD5 (it names the cover's file): a room with anything else is left out (code audit 3, p3 L6).
        assertEquals(0, RoomList.parse("""{"rooms":[{"id":"a","md5":"../../shared_prefs/x","code":"$code"}]}""").size)
    }
}
