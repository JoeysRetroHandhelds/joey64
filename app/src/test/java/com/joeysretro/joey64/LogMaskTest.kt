package com.joeysretro.joey64

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LogMaskTest {
    @Test fun publicAddressesGoAndWiFiOnesStay() {
        assertEquals("joining Bob's room at <public address>:45000", LogMask.addresses("joining Bob's room at 81.2.69.160:45000"))
        assertEquals("at 192.168.1.20:45001", LogMask.addresses("at 192.168.1.20:45001"))
        assertEquals("10.0.0.2 172.20.1.1 127.0.0.1 100.70.1.2", LogMask.addresses("10.0.0.2 172.20.1.1 127.0.0.1 100.70.1.2"))
        assertEquals("<public address> and <public address>:2", LogMask.addresses("172.32.0.1 and 8.8.8.8:2"))
        assertEquals("connected to <public address>.", LogMask.addresses("connected to 81.2.69.160."))
        assertEquals("failed to connect to /<public address> (port 45000) from /192.168.1.5 (port 41234)",
            LogMask.addresses("failed to connect to /81.2.69.160 (port 45000) from /192.168.1.5 (port 41234)"))
        // Not an address: left alone.
        assertEquals("version 1.2.3.4.5 and 300.1.1.1", LogMask.addresses("version 1.2.3.4.5 and 300.1.1.1"))
        // versions, not addresses (2026-11-12: the Adreno driver line lost its compiler version)
        assertEquals("Compiler Version: E031.41.03.44 v8.1.2.3", LogMask.addresses("Compiler Version: E031.41.03.44 v8.1.2.3"))
        assertEquals("build 031.41.03.44 and <public address>", LogMask.addresses("build 031.41.03.44 and 8.8.8.8"))
        assertEquals("from=<public address>", LogMask.addresses("from=81.2.69.160"))
    }

    @Test fun urisKeepOnlyTheLastName() {
        assertEquals("content://com.android.externalstorage.documents/…/Zelda (USA).z64",
            LogMask.uri("content://com.android.externalstorage.documents/tree/primary%3AROMs%2FN64/document/" +
                "primary%3AROMs%2FN64%2FJohn%20Smith%2FZelda%20(USA).z64"))
        assertEquals("content://com.android.externalstorage.documents/…/N64",
            LogMask.uri("content://com.android.externalstorage.documents/tree/primary%3AROMs%2FN64"))
        assertEquals("file:///…/Mario+Kart.z64", LogMask.uri("file:///storage/emulated/0/John/Mario+Kart.z64"))
        assertEquals("…/current.rom", LogMask.uri("/storage/emulated/0/Android/data/x/cache/current.rom"))
        assertEquals("content://media/…/", LogMask.uri("content://media/"))
    }

    @Test fun extrasAsTheLogKeepsThem() {
        assertEquals("<name>", LogMask.extra("com.joeysretro.joey64.NETPLAY_NAME", "Joey"))
        assertEquals("<key>", LogMask.extra("com.joeysretro.joey64.NETPLAY_KEY", "68c44e79"))
        assertEquals("<key>", LogMask.extra("com.joeysretro.joey64.NETPLAY_KEYS", "0,68c44e79,0,0"))
        assertEquals("<public address>:45000:2", LogMask.extra("com.joeysretro.joey64.NETPLAY_JOIN", "81.2.69.160:45000:2"))
        assertEquals("127.0.0.1:45000:1", LogMask.extra("com.joeysretro.joey64.NETPLAY_JOIN", "127.0.0.1:45000:1"))
        assertEquals("content://a.b/…/Doshin.ndd", LogMask.extra("com.joeysretro.joey64.DD_CART", "content://a.b/tree/x%3AJohn%2FDoshin.ndd"))
        assertEquals("Super Mario 64 (USA)", LogMask.extra("com.joeysretro.joey64.SAVE_NAME", "Super Mario 64 (USA)"))
        assertEquals("null", LogMask.extra("k", null))
    }

    @Test fun cappedReadsStopAtTheCap() {
        val data = ByteArray(300_000) { it.toByte() }
        assertArrayEquals(data, Capped.bytes(data.inputStream(), 300_000))
        val e = assertThrows(Capped.TooBig::class.java) { Capped.bytes(data.inputStream(), 200 * Capped.KB, "the list") }
        assertEquals("the list is over 200 KB", e.message)
        assertEquals("A cover is over 1 MB", Capped.TooBig("A cover", Capped.MB).message)
    }
}
