package com.joeysretro.joey64.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaBridgeTest {
    @Test fun onlyAddressesOnThisDeviceAreTakenAsTheHost() {
        // What RAOfflineProxy sends.
        assertTrue(RaBridge.isLocalHost("http://127.0.0.1:8080"))
        assertTrue(RaBridge.isLocalHost("http://localhost:9000"))
        assertTrue(RaBridge.isLocalHost("http://[::1]:8080"))
        assertTrue(RaBridge.isLocalHost("http://127.0.0.1:8080/"))
        // PPSSPP's rules (2026-11-07): http only, a port, nothing after the host.
        assertFalse(RaBridge.isLocalHost("http://127.0.0.1"))
        assertFalse(RaBridge.isLocalHost("https://127.0.0.1:8080"))
        assertFalse(RaBridge.isLocalHost("http://127.0.0.1:8080/dorequest.php"))
        assertFalse(RaBridge.isLocalHost("http://127.0.0.1:8080?x=1"))
        assertFalse(RaBridge.isLocalHost("http://127.0.0.1:8080#x"))
        assertFalse(RaBridge.isLocalHost("http://127.0.0.1:99999"))
        // Anything that could carry the account's token off the device.
        assertFalse(RaBridge.isLocalHost("http://192.168.1.20:8080"))
        assertFalse(RaBridge.isLocalHost("https://evil.example"))
        assertFalse(RaBridge.isLocalHost("http://127.0.0.1.evil.example"))
        assertFalse(RaBridge.isLocalHost("http://localhost.evil.example:8080"))
        assertFalse(RaBridge.isLocalHost("http://user@evil.example"))
        assertFalse(RaBridge.isLocalHost("http://127.0.0.1@evil.example"))
        assertFalse(RaBridge.isLocalHost("ftp://127.0.0.1"))
        assertFalse(RaBridge.isLocalHost("127.0.0.1:8080"))
        assertFalse(RaBridge.isLocalHost(""))
    }

    @Test fun richPresenceOffDropsOnlyThePingsMessage() {
        assertEquals("r=ping&u=joey&t=abc&g=1234&h=0",
            RaBridge.withoutRichPresence("r=ping&u=joey&t=abc&g=1234&m=Mario+is+in+Bob-omb+Battlefield&h=0"))
        // Other requests go as they are, even with an m= field.
        assertEquals("r=awardachievement&u=joey&a=5&m=abc", RaBridge.withoutRichPresence("r=awardachievement&u=joey&a=5&m=abc"))
        assertEquals("r=pingx&m=1", RaBridge.withoutRichPresence("r=pingx&m=1"))
    }

    @Test fun aRefusedPasswordIsTheWrongPasswordLine() {
        assertEquals(RaBridge.SignInState.Wrong, RaBridge.signInResult(-34)) // RC_INVALID_CREDENTIALS
        assertEquals(RaBridge.SignInState.Wrong, RaBridge.signInResult(-35)) // RC_EXPIRED_TOKEN
        assertTrue(RaBridge.signInResult(-32) is RaBridge.SignInState.Failed) // no answer: offline
        assertTrue(RaBridge.signInResult(-2) is RaBridge.SignInState.Failed)
        assertEquals(RaBridge.SignInState.Idle, RaBridge.signInResult(0))
    }

    @Test fun defaultsAreTheApprovedOnes() {
        val o = RaOptions()
        assertTrue(o.achievements && o.popUnlocks && o.popProgress && o.popChallenges && o.popSummary && o.saveBadges && o.richPresence)
        assertFalse(o.encore || o.spectator || o.unofficial || o.screenshotOnUnlock)
        assertEquals(PopupSpot.TopLeft, o.popPlace)
        assertEquals(PopupSpot.BottomRight, o.badgePlace)
        assertEquals(5, o.popSeconds)
        assertEquals(PopupSpot.TopRight, PopupSpot.of("TopRight", PopupSpot.TopLeft))
        assertEquals(PopupSpot.TopLeft, PopupSpot.of("Nowhere", PopupSpot.TopLeft))
    }
}
