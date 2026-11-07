package com.joeysretro.joey64.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RetroAchievements' `systemgames` answer, as rcheevos reads it (rc_api_process_fetch_games_list_server_response). */
class RaGameListTest {
    @Test fun keepsTheHashesOfGamesWithAchievements() {
        val json = """{"Success":true,"Response":[
            {"ID":10003,"Title":"Super Mario 64","ImageIcon":"/Images/1.png","ImageUrl":"x","NumAchievements":75,"NumLeaderboards":0,"Points":620,
             "SupportedHashes":["20B854B239203BAF6C961B850A4A51A2","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]},
            {"ID":99,"Title":"No set yet","ImageIcon":"","ImageUrl":"","NumAchievements":0,"NumLeaderboards":0,"Points":0,
             "SupportedHashes":["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]}]}"""
        assertEquals(setOf("20b854b239203baf6c961b850a4a51a2", "a".repeat(32)), RaGameList.parse(json))
    }

    @Test fun refusedOrBrokenAnswersAreNotLists() {
        assertNull(RaGameList.parse("""{"Success":false,"Error":"Unknown request"}"""))
        assertNull(RaGameList.parse("<html>"))
    }
}
