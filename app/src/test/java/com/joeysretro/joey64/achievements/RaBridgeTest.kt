package com.joeysretro.joey64.achievements

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaBridgeTest {
    @Test fun onlyAddressesOnThisDeviceAreTakenAsTheHost() {
        // What RAOfflineProxy sends.
        assertTrue(RaBridge.isLocalHost("http://127.0.0.1:8080"))
        assertTrue(RaBridge.isLocalHost("http://localhost:9000"))
        assertTrue(RaBridge.isLocalHost("http://[::1]:8080"))
        assertTrue(RaBridge.isLocalHost("http://127.0.0.1"))
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
}
