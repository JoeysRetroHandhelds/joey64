package com.joeysretro.joey64.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class NetEndedTest {
    // coreShutDown's netplay codes to what the library says (Joey 2026-11-12: 8 = the host closed the room).
    @Test fun codes() {
        assertEquals(GameStartFailed.Need.NetLost, GameStartFailed.needOf(3))
        assertEquals(GameStartFailed.Need.NetUnreachable, GameStartFailed.needOf(4))
        assertEquals(GameStartFailed.Need.NetTurnedDown, GameStartFailed.needOf(5))
        assertEquals(GameStartFailed.Need.NetMismatch, GameStartFailed.needOf(6))
        assertEquals(GameStartFailed.Need.NetHostClosed, GameStartFailed.needOf(8))
    }
}
