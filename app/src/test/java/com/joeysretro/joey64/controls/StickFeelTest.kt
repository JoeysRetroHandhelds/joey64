package com.joeysretro.joey64.controls

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sqrt

class StickFeelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val h = (1 / sqrt(2.0)).toFloat()
    private fun n64(v: Float) = Math.round(v * 80)

    @Test fun defaultsAreTheOldDeadZone() {
        val f = StickFeel()
        for ((x, y) in listOf(0.55f to 0f, 0.3f to -0.4f, h to h, 0.05f to 0f, 1f to 0f)) {
            assertEquals(PlayerInput.deadZone(x, y, 10).first, f.apply(x, y, 10).first, 1e-5f)
            assertEquals(PlayerInput.deadZone(x, y, 10).second, f.apply(x, y, 10).second, 1e-5f)
        }
    }

    @Test fun theHostSendsEightyForFullTilt() {
        // The core at 150% sensitivity: value * 120 / 32768.
        val sent = PlayerInput.toRetroStick(1f)
        assertEquals(80, Math.round(sent * 120.0 / 32768).toInt())
        assertEquals(120, Math.round(PlayerInput.toRetroStick(StickFeel.MAX) * 120.0 / 32768).toInt())
    }

    @Test fun rangeScalesFullTilt() {
        assertEquals(n64(1.2f), n64(StickFeel(range = 120).apply(1f, 0f, 0).first))
        assertEquals(40, n64(StickFeel(range = 50).apply(1f, 0f, 0).first))
    }

    @Test fun theOctagonGateGivesTheN64Diagonal() {
        val (x, y) = StickFeel(gate = true).apply(h, -h, 0)
        assertEquals(70, n64(x)); assertEquals(-70, n64(y))
        assertEquals(80, n64(StickFeel(gate = true).apply(0f, 1f, 0).second))
        // Round, as before: 57 each way.
        assertEquals(57, n64(StickFeel().apply(h, h, 0).first))
    }

    @Test fun curvesBendSmallTiltsOnly() {
        val lin = StickFeel().apply(0.5f, 0f, 0).first
        assertTrue(StickFeel(curve = StickCurve.Precise).apply(0.5f, 0f, 0).first < lin)
        assertTrue(StickFeel(curve = StickCurve.Quick).apply(0.5f, 0f, 0).first > lin)
        assertEquals(1f, StickFeel(curve = StickCurve.Precise).apply(1f, 0f, 0).first, 1e-5f)
    }

    @Test fun antiDeadZoneStartsPastTheGamesOwn() {
        val f = StickFeel(antiDeadZone = 20)
        assertEquals(0f, f.apply(0.05f, 0f, 10).first, 1e-6f)          // inside our dead zone: still
        assertTrue(f.apply(0.11f, 0f, 10).first >= 0.2f)              // just past it: 20% already
        assertEquals(1f, f.apply(1f, 0f, 10).first, 1e-5f)
    }

    @Test fun invertAndSwap() {
        assertEquals(0.5f to -0.5f, StickFeel(invert = StickInvert.UpDown).apply(0.5f, 0.5f, 0).let { it.first.r() to it.second.r() })
        assertEquals(-0.5f to 0.5f, StickFeel(invert = StickInvert.LeftRight).apply(0.5f, 0.5f, 0).let { it.first.r() to it.second.r() })
        assertEquals(0.3f to 0.6f, StickFeel(swap = true).apply(0.6f, 0.3f, 0).let { it.first.r() to it.second.r() })
    }
    private fun Float.r() = Math.round(this * 1000) / 1000f

    @Test fun triggerThreshold() {
        val p = PlayerInput(ControlsSet.defaults().players[0].copy(feel = StickFeel(triggerThreshold = 30)))
        p.setTriggers(0.35f, 0f)
        assertEquals(1 shl N64Button.Z.retroBit, p.buttons())
        p.setTriggers(0.25f, 0f)
        assertEquals(0, p.buttons())
    }

    @Test fun leftStickAndDpad() {
        val p = PlayerInput(ControlsSet.defaults().players[0].copy(stick = StickSource.LeftStickDPad))
        p.setAxes(0.55f, 0f, 0f, 0f)
        assertEquals(0.5f, p.stick().first, 1e-5f)
        p.key(KeyEvent.KEYCODE_DPAD_UP, true)
        assertEquals(0f to -1f, p.stick())
        assertEquals(0, p.buttons()) // the D-pad no longer presses the N64 D-pad
        assertTrue(p.uses(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test fun feelIsSavedAndPerGameRowsDiffer() {
        val store = ControlsStore(tmp.root)
        val ed = ControlsEditor(store, null) { it }
        val feel = StickFeel(range = 110, curve = StickCurve.Precise, gate = true, invert = StickInvert.Both, swap = true, antiDeadZone = 15, triggerThreshold = 70)
        ed.set(0, ed.shown.players[0].copy(feel = feel))
        assertEquals(feel, ControlsEditor(store, null) { it }.shown.players[0].feel)

        val game = ControlsEditor(store, "GoldenEye 007 (USA)") { it }
        game.set(0, game.shown.players[0].copy(feel = feel.copy(curve = StickCurve.Linear, range = 90)))
        assertEquals(setOf(StickFeel.CURVE, StickFeel.RANGE), game.changed(0))
        game.resetRow(0, StickFeel.CURVE)
        assertEquals(setOf(StickFeel.RANGE), game.changed(0))
        game.resetRow(0, "StickFeel")
        assertTrue(game.changed(0).isEmpty())
    }
}
