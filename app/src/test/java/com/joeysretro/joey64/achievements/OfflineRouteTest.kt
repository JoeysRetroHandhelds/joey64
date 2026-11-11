package com.joeysretro.joey64.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRouteTest {
    private val proxy = "http://127.0.0.1:8080"

    @Test fun joey64IgnoresARunningProxy() {
        val r = OfflineRoute.of(OfflineBy.Joey64, proxy)
        assertNull(r.host)
        assertFalse(r.blocked)
        assertTrue(r.queue)
    }

    @Test fun proxyRunningTakesEverythingWithoutJoey64Queue() {
        val r = OfflineRoute.of(OfflineBy.Proxy, proxy)
        assertEquals(proxy, r.host)
        assertFalse(r.blocked)
        assertFalse(r.queue)
    }

    @Test fun proxyNotRunningBlocksWithoutFallingBack() {
        val r = OfflineRoute.of(OfflineBy.Proxy, null)
        assertNull(r.host)
        assertTrue(r.blocked)
        assertFalse(r.queue)
    }

    @Test fun rowValuesAsDrawn() {
        assertEquals("Joey64", OfflineRoute.rowValue(OfflineBy.Joey64, true, 0))
        assertEquals("Joey64 · 2 waiting", OfflineRoute.rowValue(OfflineBy.Joey64, false, 2))
        assertEquals("RAOfflineProxy", OfflineRoute.rowValue(OfflineBy.Proxy, true, 2))
        assertEquals("RAOfflineProxy · not running", OfflineRoute.rowValue(OfflineBy.Proxy, false, 0))
    }

    @Test fun descriptionCountsOne() {
        assertTrue(OfflineRoute.describe(OfflineBy.Joey64, false, 1).startsWith("1 unlock earned offline hasn't"))
        assertTrue(OfflineRoute.describe(OfflineBy.Joey64, false, 2).startsWith("2 unlocks earned offline haven't"))
    }

    @Test fun unknownStoredChoiceIsJoey64() {
        assertEquals(OfflineBy.Joey64, OfflineBy.of(null))
        assertEquals(OfflineBy.Joey64, OfflineBy.of("Nope"))
        assertEquals(OfflineBy.Proxy, OfflineBy.of("Proxy"))
    }
}
