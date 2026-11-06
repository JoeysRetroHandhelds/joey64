package com.joeysretro.joey64.launcher.ingame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupQueueTest {
    private fun p(key: String, name: String = key) = InGamePopup(key, InGamePopup.Kind.Saved, "Save state", name, "")

    @Test fun showsOneAtATimeInOrder() {
        val q = PopupQueue(5_000)
        q.add(p("a"), 0)
        q.add(p("b"), 100)
        assertEquals("a", q.current?.key)
        assertFalse(q.tick(4_999))
        assertTrue(q.tick(5_000))
        assertEquals("b", q.current?.key)
        assertEquals(10_000, q.until)
        assertTrue(q.tick(10_000))
        assertNull(q.current)
    }

    @Test fun theSameKeyTakesThePlaceOfTheOneShowing() {
        val q = PopupQueue(5_000)
        q.add(p("slot", "Slot 2"), 0)
        q.add(p("slot", "Slot 3"), 3_000)
        assertEquals("Slot 3", q.current?.name)
        assertEquals(8_000, q.until)
    }

    @Test fun theSameKeyTakesThePlaceOfTheOneWaiting() {
        val q = PopupQueue(5_000)
        q.add(p("saved"), 0)
        q.add(p("slot", "Slot 2"), 10)
        q.add(p("slot", "Slot 3"), 20)
        q.tick(5_000)
        assertEquals("Slot 3", q.current?.name)
        q.tick(10_000)
        assertNull(q.current)
    }
}
