package com.joeysretro.joey64.launcher.screens

import com.joeysretro.joey64.staterequest.StateRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state request popup's panel lines by the wanted list's why (approved mockup 2026-11-12; compat: Joey 2026-11-12). */
class StateRequestLinesTest {
    private fun ask(kind: StateRequests.Kind, why: String, need: String = "A save state while playing", playAt: Long = 720) =
        StateRequests.Ask(StateRequests.Wanted("0123456789abcdef0123456789abcdef", kind, need, "Game", why),
            StateRequests.RingEntry("/f/0.raw", 0, true, playAt))

    @Test fun whyByKind() {
        assertTrue(whyLine(ask(StateRequests.Kind.Gameplay, "widescreen")).startsWith("We're making a widescreen code"))
        assertTrue(whyLine(ask(StateRequests.Kind.Gameplay, "60fps")).startsWith("We're making a 60 fps code"))
        assertEquals("We haven't seen this game past its start yet. A save state from real play helps us check it runs right.",
            whyLine(ask(StateRequests.Kind.Gameplay, "compat")))
        assertEquals("Black screen in level 2. A save state from your play helps us find the cause.",
            whyLine(ask(StateRequests.Kind.Problem, "problem", need = "Black screen in level 2.")))
    }

    @Test fun minutesIntoThePlay() {
        assertEquals("A save state from 12 minutes into this play", fromLine(ask(StateRequests.Kind.Gameplay, "compat", playAt = 720)))
        assertEquals("A save state from 1 minute into this play", fromLine(ask(StateRequests.Kind.Gameplay, "compat", playAt = 30)))
    }
}
