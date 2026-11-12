package com.joeysretro.joey64.staterequest

import com.joeysretro.joey64.staterequest.StateRequests.Kind
import com.joeysretro.joey64.staterequest.StateRequests.Prefs
import com.joeysretro.joey64.staterequest.StateRequests.Ring
import com.joeysretro.joey64.staterequest.StateRequests.RingEntry
import com.joeysretro.joey64.staterequest.StateUpload.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateRequestsTest {
    private val md5a = "0123456789abcdef0123456789abcdef"
    private val md5b = "fedcba9876543210fedcba9876543210"
    private val wantedJson = """{"version":1,"games":[
        {"md5":"${md5a.uppercase()}","title":"Game A","kind":"gameplay","need":"Any level","why":"x","enough":3},
        {"md5":"$md5b","title":"Game B","kind":"problem","need":"The broken boss","why":"y","enough":1},
        {"md5":"nothex","kind":"gameplay"},
        {"md5":"$md5b","kind":"other"}]}"""

    @Test fun wantedListIsRead() {
        val w = StateRequests.parseWanted(wantedJson)
        assertEquals(setOf(md5a, md5b), w.keys)
        assertEquals(Kind.Gameplay, w[md5a]!!.kind)
        assertEquals("Any level", w[md5a]!!.need)
        assertEquals("Game A", w[md5a]!!.title)
        assertEquals(Kind.Problem, w[md5b]!!.kind)
    }

    @Test fun aBadWantedListIsEmpty() {
        assertTrue(StateRequests.parseWanted("").isEmpty())
        assertTrue(StateRequests.parseWanted("not json").isEmpty())
        assertTrue(StateRequests.parseWanted("""{"version":1}""").isEmpty())
    }

    @Test fun ringIsRead() {
        val r = StateRequests.parseRing("""{"version":1,"playSeconds":900,"quitAt":1000000,"entries":[
            {"path":"/data/user/0/p/files/states/ring/$md5a/0.raw","takenAt":500000,"playAt":400,"drew3d":true},
            {"path":"/x/../etc/passwd","takenAt":600000,"playAt":500,"drew3d":true},
            {"path":"/data/user/0/p/files/states/ring/$md5a/1.raw","takenAt":700000,"drew3d":false}]}""")!!
        assertEquals(900L, r.playSeconds)
        assertEquals(1000000L, r.quitAt)
        assertEquals(2, r.entries.size)
        assertEquals(400L, r.entries[0].playAt)
        assertEquals(StateRequests.MIN_PLAY_SECONDS, r.entries[1].playAt)
        assertNull(StateRequests.parseRing("nope"))
    }

    private fun e(n: Int, takenAt: Long, drew3d: Boolean, playAt: Long = 600) = RingEntry("/f/$n.raw", takenAt, drew3d, playAt)
    private val quit = 10_000_000L

    @Test fun gameplayTakesTheNewest3dStateWellBeforeTheQuit() {
        val ring = Ring(900, quit, listOf(
            e(0, quit - 600_000, true), e(1, quit - 300_000, true), e(2, quit - 200_000, false), e(3, quit - 60_000, true)))
        assertEquals("/f/1.raw", StateRequests.pick(ring, Kind.Gameplay)!!.path)
    }

    @Test fun gameplayNeedsFiveMinutesOfPlayAtTheState() {
        val ring = Ring(900, quit, listOf(e(0, quit - 600_000, true, playAt = 600), e(1, quit - 300_000, true, playAt = 120)))
        assertEquals("/f/0.raw", StateRequests.pick(ring, Kind.Gameplay)!!.path)
        assertNull(StateRequests.pick(Ring(900, quit, listOf(e(1, quit - 300_000, true, playAt = 120))), Kind.Gameplay))
    }

    @Test fun problemTakesAnyEarlyStateElseTheNewest() {
        val ring = Ring(900, quit, listOf(e(0, quit - 600_000, false), e(1, quit - 60_000, false)))
        assertEquals("/f/0.raw", StateRequests.pick(ring, Kind.Problem)!!.path)
        assertEquals("/f/1.raw", StateRequests.pick(Ring(900, quit, listOf(e(1, quit - 60_000, false))), Kind.Problem)!!.path)
    }

    @Test fun noQuitTimeNoPick() {
        val ring = Ring(900, 0, listOf(e(0, 1000, true)))
        assertNull(StateRequests.pick(ring, Kind.Gameplay))
        assertNull(StateRequests.pick(ring, Kind.Problem))
    }

    @Test fun decideFollowsTheAnswersAndPlayTime() {
        val wanted = StateRequests.parseWanted(wantedJson)
        val ring = Ring(900, quit, listOf(e(0, quit - 600_000, true)))
        val none = Prefs(false, emptySet(), emptySet())
        assertNotNull(StateRequests.decide(md5a.uppercase(), wanted, none, ring))
        assertNull(StateRequests.decide("00000000000000000000000000000000", wanted, none, ring))
        assertNull(StateRequests.decide(md5a, wanted, none.copy(never = true), ring))
        assertNull(StateRequests.decide(md5a, wanted, none.copy(skipped = setOf(md5a)), ring))
        assertNull(StateRequests.decide(md5a, wanted, none.copy(sent = setOf(md5a)), ring))
        assertNull(StateRequests.decide(md5a, wanted, none, ring.copy(playSeconds = 299)))
        assertNull(StateRequests.decide(md5a, wanted, none, null))
    }

    @Test fun serverAnswers() {
        val now = 1_000_000L
        assertEquals(Result.Sent, StateUpload.resultOf(204, null, 0, now))
        assertEquals(Result.Drop(done = true), StateUpload.resultOf(404, null, 0, now))
        assertEquals(Result.Drop(done = true), StateUpload.resultOf(410, null, 11, now))
        assertEquals(Result.Drop(), StateUpload.resultOf(400, null, 0, now))
        assertEquals(Result.Drop(), StateUpload.resultOf(413, null, 0, now))
        assertEquals(Result.Later(now + 600_000), StateUpload.resultOf(429, 600, 0, now))
        assertEquals(Result.Later(now + 60_000), StateUpload.resultOf(429, 5, 0, now))
        assertEquals(Result.Later(now + 86_400_000), StateUpload.resultOf(429, 999_999, 0, now))
        assertEquals(Result.Later(now + 600_000), StateUpload.resultOf(503, 600, 0, now))
        assertEquals(Result.Later(now + 60_000), StateUpload.resultOf(-1, null, 0, now))
        assertEquals(Result.Later(now + 240_000), StateUpload.resultOf(-1, null, 2, now))
        assertEquals(Result.Later(now + 61_440_000), StateUpload.resultOf(500, null, 11, now))
        assertEquals(Result.Drop(), StateUpload.resultOf(503, null, 12, now))
    }
}
