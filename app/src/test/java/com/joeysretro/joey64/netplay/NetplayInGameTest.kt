package com.joeysretro.joey64.netplay

import com.joeysretro.joey64.controls.HotkeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetplayInGameTest {
    @Test fun offWhenNotInARoom() {
        assertEquals(NetInfo.OFF, NetInfo.of(null))
        assertEquals(NetInfo.OFF, NetInfo.of(intArrayOf(0, 0, 0, 0, 0, 0)))
    }

    @Test fun readsTheBridgeFields() {
        // Hosting, players 1, 2 and 4 joined, out of step, player 3 left, player 2 paused, waiting for player 4.
        val n = NetInfo.of(intArrayOf(1, 1, 0b1011, 1 or (1 shl 3), 2, 4, 1))
        assertEquals(1, n.me)
        assertTrue(n.inRoom); assertTrue(n.hosting); assertTrue(n.outOfStep)
        assertEquals(listOf(1, 2, 4), n.playersIn(n.joined))
        assertEquals(listOf(3), n.playersIn(n.left))
        assertEquals(2, n.pausedBy); assertEquals(4, n.waitingFor)
        // An older bridge with four fields.
        val old = NetInfo.of(intArrayOf(1, 0, 0, 0))
        assertTrue(old.inRoom); assertFalse(old.hosting); assertEquals(0, old.pausedBy)
    }

    @Test fun words() {
        assertEquals("Player 2", NetplayWords.players(listOf(2)))
        assertEquals("Players 1 and 3", NetplayWords.players(listOf(1, 3)))
        assertEquals("Players 1, 2 and 4", NetplayWords.players(listOf(1, 2, 4)))
        assertEquals("Players 1 and 3 are waiting for you", NetplayWords.waitingForYou(listOf(1, 3)))
        assertEquals("Player 3 is waiting for you", NetplayWords.waitingForYou(listOf(3)))
        assertEquals("The other players are waiting for you", NetplayWords.waitingForYou(emptyList()))
        // No em or en dashes in what the screens say.
        val all = listOf(NetplayWords.PAUSED_TEXT, NetplayWords.OUT_OF_STEP_TEXT, NetplayWords.leftText(2), NetplayWords.slowLine(3)) +
            HotkeyAction.entries.mapNotNull { NetplayWords.blockedLine(it) }
        assertTrue(all.none { '–' in it || '—' in it })
    }

    @Test fun blockedHotkeys() {
        for (a in listOf(HotkeyAction.Save, HotkeyAction.Load, HotkeyAction.Reset, HotkeyAction.FastForward, HotkeyAction.SlowMotion,
            HotkeyAction.Rewind, HotkeyAction.FrameAdvance, HotkeyAction.GameShark, HotkeyAction.UndoLoad, HotkeyAction.UndoSave))
            assertTrue(a.name, NetplayWords.blockedLine(a) != null)
        assertEquals("Save states wait until you leave", NetplayWords.blockedLine(HotkeyAction.Save))
        for (a in listOf(HotkeyAction.PauseMenu, HotkeyAction.Pause, HotkeyAction.Screenshot, HotkeyAction.VolumeUp, HotkeyAction.Quit))
            assertNull(a.name, NetplayWords.blockedLine(a))
    }

    @Test fun myPlayer() {
        assertEquals(1, NetplayWords.myPlayer(true, null))
        assertEquals(3, NetplayWords.myPlayer(false, "192.168.1.5:45000:3"))
        assertEquals(0, NetplayWords.myPlayer(false, "192.168.1.5:45000"))
        assertEquals(0, NetplayWords.myPlayer(false, null))
        assertEquals(2, NetplayWords.myPlayer(false, "192.168.1.5:45000:3", fromBridge = 2))
    }

    @Test fun playersList() {
        assertEquals(emptyList<NetPlayer>(), NetPlayers.parse(""))
        assertEquals(listOf(NetPlayer(1, "Odin3", 5), NetPlayer(2, "Odin2 Portal", 118)), NetPlayers.parse("2|Odin2 Portal|118;1|Odin3|5"))
        assertEquals(listOf(NetPlayer(3, "Player 3", 0)), NetPlayers.parse("3||;9|x|1;junk"))
        assertEquals("Sam (Odin 3)'s connection is slow", NetplayWords.slowLine(2, "Sam (Odin 3)"))
        assertEquals("Player 2 left the game", NetplayWords.leftTitle(2))
    }
}
