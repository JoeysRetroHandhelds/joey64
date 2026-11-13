package com.joeysretro.joey64.touch

import com.joeysretro.joey64.controls.N64Button
import com.joeysretro.joey64.controls.PlayerInput
import com.joeysretro.joey64.launcher.gamesettings.GameSettingsModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

class TouchTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun near(a: Float, b: Float, e: Float = 0.01f) = assertTrue("$a vs $b", abs(a - b) <= e)
    private fun placedOf(kind: TouchKind, w: Float, h: Float, portrait: Boolean = false, list: List<TouchControl> = if (portrait) TouchLayouts.PORTRAIT else TouchLayouts.LANDSCAPE) =
        TouchLayouts.place(list.first { it.kind == kind }, w, h, portrait)

    /** At the drawing's own size (832 x 374, one unit a pixel) every control is where the mockup draws it. */
    @Test fun landscapeIsTheMockups() {
        val a = placedOf(TouchKind.A, 832f, 374f); near(a.cx, 774f); near(a.cy, 282f); near(a.w, 64f)
        val z = placedOf(TouchKind.Z, 832f, 374f); near(z.cx, 697f); near(z.cy, 308f)
        val l = placedOf(TouchKind.L, 832f, 374f); near(l.cx, 70f); near(l.cy, 29f)
        val st = placedOf(TouchKind.Stick, 832f, 374f); near(st.cx, 100f); near(st.cy, 262f); near(st.w, 150f)
    }

    /** On a 16:9 screen the right side keeps its distance from the right edge (mockup screen 2). */
    @Test fun sixteenNineKeepsTheRightSideOnTheRight() {
        val a = placedOf(TouchKind.A, 1920f, 1080f)
        val u = 1080f / 374f
        near(a.cx, 1920f - (832f - 774f) * u, 0.5f)
        near(placedOf(TouchKind.Stick, 1920f, 1080f).cx, 100f * u, 0.5f)
    }

    /** Portrait keeps the controls above the bottom edge: on the 1080 x 2400 phone, the mockup's places. */
    @Test fun portraitIsTheMockups() {
        val u = 1080f / 374f
        val a = placedOf(TouchKind.A, 1080f, 2400f, portrait = true)
        near(a.cx, 322f * u, 0.5f); near(a.cy, 590f * u, 0.5f)
        val start = placedOf(TouchKind.Start, 1080f, 2400f, portrait = true)
        near(start.cy, 774f * u, 0.5f)
    }

    @Test fun movedControlLandsWhereDropped() {
        val c = TouchLayouts.LANDSCAPE.first { it.kind == TouchKind.Z }
        val moved = TouchLayouts.moveTo(c, 300f, 200f, 2400f, 1080f, false)
        val p = TouchLayouts.place(moved, 2400f, 1080f, false)
        near(p.cx, 300f, 0.5f); near(p.cy, 200f, 0.5f)
        assertFalse(moved.fromRight)
        val right = TouchLayouts.moveTo(c, 2000f, 900f, 2400f, 1080f, false)
        assertTrue(right.fromRight)
        near(TouchLayouts.place(right, 2400f, 1080f, false).cx, 2000f, 0.5f)
    }

    @Test fun settingsKeepTheirRowsAndLayouts() {
        val lay = TouchLayouts.LANDSCAPE.map { if (it.kind == TouchKind.Z) it.copy(size = 120, opacity = 40) else it } +
            TouchLayouts.newControl(TouchKind.Macro, 2400f, 1080f, false, "Fast text")
        val t = TouchSettings(show = TouchShow.Always, opacity = 80, haptics = false, hideAfter = 10, stick = TouchStick.LeftHalf,
            sensitivity = 120, deadZone = 4, fullAt = 90, slide = false, landscape = lay)
        val back = TouchSettings.fromJson(JSONObject(t.toJson().toString()))
        assertEquals(t, back)
        assertEquals(TouchLayouts.PORTRAIT, back.layout(true))
    }

    @Test fun stickDeadZoneFullSpeedAndGate() {
        val s = TouchSettings(deadZone = 8, fullAt = 85, sensitivity = 100)
        assertEquals(0f to 0f, TouchMath.stick(5f, 0f, 100f, s))
        val (x, y) = TouchMath.stick(85f, 0f, 100f, s); near(x, 1f); near(y, 0f)
        val (x2, _) = TouchMath.stick(200f, 0f, 100f, s); near(x2, 1f)
        // Full tilt on a diagonal reaches the N64's 70, 70 of 80.
        val (dx, dy) = TouchMath.stick(100f, -100f, 100f, s); near(dx, 70f / 80f, 0.02f); near(dy, -70f / 80f, 0.02f)
        // Sensitivity 200%: half the travel is full tilt.
        val (hx, _) = TouchMath.stick(45f, 0f, 100f, s.copy(sensitivity = 200)); near(hx, 1f)
    }

    @Test fun dpadEightWays() {
        assertEquals(setOf(N64Button.DUp), TouchMath.dpad(0f, -50f, 50f))
        assertEquals(setOf(N64Button.DRight, N64Button.DDown), TouchMath.dpad(40f, 40f, 50f))
        assertEquals(emptySet<N64Button>(), TouchMath.dpad(2f, 2f, 50f))
    }

    private class Events : TouchInput.Listener {
        val log = ArrayList<String>()
        var presses = 0
        override fun onAction(c: TouchControl, down: Boolean) { log += "${c.kind}${if (down) " down" else " up"}" }
        override fun onPress() { presses++ }
    }

    private fun input(s: TouchSettings = TouchSettings(), list: List<TouchControl> = TouchLayouts.LANDSCAPE): Pair<TouchInput, Events> {
        val e = Events()
        return TouchInput(e).also { it.setup(list, 832f, 374f, false, s) } to e
    }

    private fun bit(b: N64Button) = 1 shl b.retroBit

    /** Mockup screen 1: the stick pushed up-right while A is held, two fingers at once. */
    @Test fun stickAndAAtOnce() {
        val (t, e) = input(TouchSettings(stick = TouchStick.Fixed))
        t.down(0, 100f, 262f)
        t.down(1, 774f, 282f)
        t.move(0, 140f, 222f)
        assertEquals(bit(N64Button.A), t.buttonBits())
        val (x, y) = t.stick()!!
        assertTrue(x > 0.5f && y < -0.5f)
        assertEquals(2, e.presses)
        t.up(1)
        assertEquals(0, t.buttonBits())
        assertNotNull(t.stick())
        t.up(0)
        assertNull(t.stick())
    }

    @Test fun recentreMakesTheTouchTheCentre() {
        val (t, _) = input(TouchSettings(stick = TouchStick.Recentre))
        t.down(0, 130f, 262f)   // right of the stick's centre
        assertEquals(0f to 0f, t.stick())
        val (fx, _) = input(TouchSettings(stick = TouchStick.Fixed)).first.also { it.down(0, 130f, 262f) }.stick()!!
        assertTrue(fx > 0f)
    }

    @Test fun anywhereOnTheLeftHalf() {
        val (t, _) = input(TouchSettings(stick = TouchStick.LeftHalf))
        t.down(0, 300f, 150f)  // no control there
        t.move(0, 300f, 100f)
        assertTrue(t.stick()!!.second < -0.5f)
        assertEquals(300f to 150f, t.drawState().bases.values.single())
        val (r, _) = input(TouchSettings(stick = TouchStick.LeftHalf))
        r.down(0, 600f, 150f)  // right half: nothing
        assertNull(r.stick())
    }

    @Test fun slideBetweenButtons() {
        val (t, _) = input(TouchSettings(slide = true))
        t.down(0, 712f, 232f)          // B
        assertEquals(bit(N64Button.B), t.buttonBits())
        t.move(0, 774f, 282f)          // onto A
        assertEquals(bit(N64Button.A), t.buttonBits())
        val (off, _) = input(TouchSettings(slide = false))
        off.down(0, 712f, 232f); off.move(0, 774f, 282f)
        assertEquals(bit(N64Button.B), off.buttonBits())
    }

    @Test fun cButtonsByTheNearestOne() {
        val (t, _) = input()
        t.down(0, 752f, 104f)
        assertEquals(bit(N64Button.CUp), t.buttonBits())
        t.move(0, 782f, 134f)
        assertEquals(bit(N64Button.CRight), t.buttonBits())
    }

    @Test fun menuAndAddedControlsAreActions() {
        val list = TouchLayouts.LANDSCAPE + TouchLayouts.newControl(TouchKind.FastForward, 832f, 374f, false)
        val (t, e) = input(list = list)
        t.down(0, 112f, 72f); t.up(0)
        t.down(1, 416f, 187f); t.cancel()
        assertEquals(listOf("Menu down", "Menu up", "FastForward down", "FastForward up"), e.log)
        assertEquals(0, t.buttonBits())
    }

    @Test fun hiddenControlsTakeNothing() {
        val list = TouchLayouts.LANDSCAPE.map { if (it.kind == TouchKind.A) it.copy(hidden = true) else it }
        val (t, _) = input(list = list)
        t.down(0, 774f, 282f)
        assertEquals(0, t.buttonBits())
        assertTrue(t.holdsNothing(0))
    }

    @Test fun touchButtonsWorkWithTapToHold() {
        val p = PlayerInput(com.joeysretro.joey64.controls.ControlsSet.defaults().players[0].copy(latch = setOf(N64Button.Z)))
        p.touchBits = bit(N64Button.Z)
        assertEquals(bit(N64Button.Z), p.buttons())
        p.touchBits = 0
        assertEquals(bit(N64Button.Z), p.buttons()) // held by the tap
        p.touchStick = 0.5f to 0f
        assertEquals(0.5f to 0f, p.stick())
        p.release()
        assertNull(p.touchStick)
    }

    @Test fun gameFileGoesWhenItMatchesAllGames() {
        val store = TouchStore(File(tmp.root, "touch"))
        store.saveGame("Mario", TouchSettings(opacity = 80))
        assertEquals(listOf("Mario"), store.gamesWithOwn())
        assertEquals(1, store.changeCount("Mario"))
        store.saveGame("Mario", store.all())
        assertTrue(store.gamesWithOwn().isEmpty())
        assertEquals(TouchSettings(), store.forGame("Mario"))
    }

    @Test fun gameSettingsCountsAndResetsTouch() {
        TouchStore(File(File(tmp.root, "controls"), "touch")).saveGame("Mario", TouchSettings(stick = TouchStick.Fixed))
        val m = GameSettingsModel(tmp.root)
        val ch = m.summary("Mario").changes
        assertEquals(listOf("Touch controls · Stick"), ch.map { it.name })
        m.reset("Mario")
        assertTrue(m.summary("Mario").changes.isEmpty())
    }

    /** Code audit 4, D-M3: Macro buttons follow a rename and go with a delete, in every file that reads those macros. */
    @Test fun macroButtonsFollowRenamesAndDeletes() {
        val store = TouchStore(tmp.newFolder("touch"))
        fun mac(name: String) = TouchControl(TouchKind.Macro, 10f, 10f, 40f, 20f, macro = name)
        val a = TouchControl(TouchKind.A, 300f, 100f, 40f, 40f)
        store.saveAll(TouchSettings(landscape = listOf(a, mac("Jump"), mac("Spin"))))
        // A game with its own macros that still has "Jump" keeps its button.
        store.saveGame("Mario", TouchSettings(landscape = listOf(mac("Jump"))))
        store.macrosChanged(listOf("Jump", "Spin"), listOf("Hop", "Spin"), macrosFor = { s -> if (s == null) listOf("Hop", "Spin") else listOf("Jump") })
        assertEquals(listOf(null, "Hop", "Spin"), store.all().landscape!!.map { it.macro })
        assertEquals(listOf("Jump"), store.game("Mario")!!.landscape!!.map { it.macro })
        store.macrosChanged(listOf("Hop", "Spin"), listOf("Hop"), macrosFor = { s -> if (s == null) listOf("Hop") else listOf("Jump") })
        assertEquals(listOf(null, "Hop"), store.all().landscape!!.map { it.macro })
        // The default layout (no file of its own) is left alone.
        assertNull(TouchSettings().withMacros(mapOf("Hop" to "X"), setOf("Spin"), emptySet()).landscape)
    }
}
