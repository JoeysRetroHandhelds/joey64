package com.joeysretro.joey64.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameCropTest {
    private val unset = VideoSettings.resolve(emptyMap(), null)

    @Test fun ocarinaGetsNormalInEveryRegion() {
        for (code in listOf("CZLE", "CZLJ", "NZLP", "NZLG", "czl")) assertEquals(code, 8, GameCrop.of(code, unset))
    }

    @Test fun otherGamesKeepCropOff() {
        assertNull(GameCrop.lines("NSME", unset))
        assertEquals(0, GameCrop.of("NSME", unset))
        assertEquals(0, GameCrop.of(null, unset))
        assertEquals(0, GameCrop.of("", unset))
    }

    @Test fun thePlayersChoiceWins() {
        val allOff = VideoSettings.resolve(mapOf(VideoKey.Crop to "0"), null)
        assertEquals(0, GameCrop.of("CZLE", allOff))
        val gameWide = VideoSettings.resolve(emptyMap(), mapOf(VideoKey.Crop to "12"))
        assertEquals(12, GameCrop.of("CZLE", gameWide))
        val allThin = VideoSettings.resolve(mapOf(VideoKey.Crop to "4"), null)
        assertEquals(4, GameCrop.of("NZLP", allThin))
    }
}
