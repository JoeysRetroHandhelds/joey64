package com.joeysretro.joey64.launcher.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Review sheet M29 and M30 (approved 2026-11-11): the ◀ ▶ values and a long press's key-only actions. */
class RowActionsTest {
    @Test fun aLongPressOffersOnlyTheKeysATouchCantPress() {
        val hints = listOf(Hint("A", "Change"), Hint("Y", "Reset to default"), Hint("Start", "Reset tab"), Hint("L/R", "Tab"), Hint("B", "Back"))
        assertEquals(listOf(Hint("Y", "Reset to default"), Hint("Start", "Reset tab")), RowActions.of(hints))
    }

    @Test fun xComesFirstWhereTheHintsPutIt() {
        val hints = listOf(Hint("A", "Change"), Hint("X", "Clear"), Hint("Y", "Reset to default"), Hint("Start", "Reset page"), Hint("B", "Close"))
        assertEquals(listOf("X", "Y", "Start"), RowActions.of(hints).map { it.key })
    }

    @Test fun searchIsNotARowsAction() {
        assertEquals(emptyList<Hint>(), RowActions.of(listOf(Hint("A", "Play"), Hint("Y", "Search"), Hint("Y", "Edit search"))))
    }

    @Test fun shouldersAndTheDpadAreLeftOut() {
        assertEquals(emptyList<Hint>(), RowActions.of(listOf(Hint("L1/R1", "Folder up, down"), Hint("◀▶", "Change"), Hint("D-pad", "Move"))))
    }

    @Test fun aSteppedValueIsReadFromItsArrows() {
        assertEquals("100%", SteppedValue.parse("◀ 100% ▶"))
        assertEquals("10%", SteppedValue.parse("◀  10%  ▶"))
        assertEquals("8 a second", SteppedValue.parse("◀ 8 a second ▶"))
        assertNull(SteppedValue.parse("100%"))
        assertNull(SteppedValue.parse("Full only"))
        assertNull(SteppedValue.parse("◀ ▶"))
        assertNull(SteppedValue.parse("On  ›"))
    }
}
