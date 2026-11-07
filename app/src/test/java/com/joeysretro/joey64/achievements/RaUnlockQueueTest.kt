package com.joeysretro.joey64.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RaUnlockQueueTest {
    private fun queue() = RaUnlockQueue(File(Files.createTempDirectory("raq").toFile(), "unsent.json")) {}

    @Test fun readsAnUnlockRequestWithoutItsKey() {
        val a = RaUnlockQueue.parseAward("r=awardachievement&u=Joey%20R&t=SECRET&a=1234&h=0&m=abc&o=90&v=f00")
        assertEquals(RaUnlockQueue.Award("Joey R", 1234, "abc", 90), a)
        assertNull(RaUnlockQueue.parseAward("r=ping&u=Joey&t=x&g=1"))
        assertNull(RaUnlockQueue.parseAward(null))
        assertEquals(0L, RaUnlockQueue.parseAward("r=awardachievement&u=J&t=x&a=5&h=0&v=1")!!.secondsAgo)
    }

    @Test fun settledFollowsRcheevosRetryRule() {
        val json = """{"Success":true}""".toByteArray()
        assertTrue(RaUnlockQueue.settled(200, json))
        assertTrue(RaUnlockQueue.settled(403, """{"Success":false,"Error":"x"}""".toByteArray()))
        assertFalse(RaUnlockQueue.settled(-2, null))
        assertFalse(RaUnlockQueue.settled(503, json))
        assertFalse(RaUnlockQueue.settled(429, json))
        assertFalse(RaUnlockQueue.settled(200, "<html>captive portal</html>".toByteArray()))
    }

    @Test fun keepsOnePerAccountAndAchievement() {
        val q = queue()
        q.add(RaUnlockQueue.Award("Joey", 7, "h", 10), nowMs = 100_000)
        q.add(RaUnlockQueue.Award("joey", 7, "h", 0), nowMs = 200_000) // a retry of the same one
        q.add(RaUnlockQueue.Award("Joey", 8, "h", 0), nowMs = 200_000)
        q.add(RaUnlockQueue.Award("Other", 7, "h", 0), nowMs = 200_000)
        assertEquals(2, q.count("JOEY"))
        assertEquals(90_000L, q.all().first { it.id == 7 && it.user == "Joey" }.atMs)
        q.remove("Joey", 7)
        assertEquals(listOf(8), q.all().filter { it.user == "Joey" }.map { it.id })
        q.clear("joey")
        assertEquals(0, q.count("Joey"))
        assertEquals(1, q.count("Other"))
    }
}
