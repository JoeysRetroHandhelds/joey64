package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.data.GameCollection
import com.joeysretro.joey64.launcher.data.LibraryGame
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LibraryViewsTest {
    private val mk = LibraryGame(1, "Mario Kart 64", "a".repeat(32), crc32 = "3E5055B6", played = true, favourite = true)
    private val snap = LibraryGame(2, "Pokémon Snap", "b".repeat(32), crc32 = "CFE2CB31")
    private val hack = LibraryGame(3, "Mario Kart 64: Amped Up", "c".repeat(32), crc32 = "00000000")
    private val games = listOf(mk, snap, hack)
    private val facts = LibraryFacts(
        players = mapOf("3E5055B6" to 4, "CFE2CB31" to 1),
        listed = setOf(mk.md5, snap.md5),
        members = mapOf(7L to setOf(2L, 3L)),
        withSets = setOf("a".repeat(32), "b".repeat(32)),
    )

    private fun ids(v: LibraryView) = LibraryViews.show(games, v, facts).map { it.id }

    @Test fun eachViewShowsItsGames() {
        assertEquals(listOf(1L, 2L, 3L), ids(LibraryView.All))
        assertEquals(listOf(1L), ids(LibraryView.Favourites))
        assertEquals(listOf(2L, 3L), ids(LibraryView.Collection(7, "Party games")))
        assertEquals(emptyList<Long>(), ids(LibraryView.Collection(8, "Empty")))
        assertEquals(listOf(1L), ids(LibraryView.Filter(AutoFilter.FourPlayers)))
        assertEquals(listOf(3L), ids(LibraryView.Filter(AutoFilter.Hacks)))
        assertEquals(listOf(2L, 3L), ids(LibraryView.Filter(AutoFilter.NeverPlayed)))
        assertEquals(listOf(1L, 2L), ids(LibraryView.Filter(AutoFilter.HasAchievements)))
    }

    @Test fun countsListFavouritesThenCollectionsThenFilters() {
        val counts = LibraryViews.counts(games, listOf(GameCollection(7, "Party games")), facts)
        assertEquals(listOf("All games", "Favourites", "Party games", "4 players", "Hacks and translations", "Never played", "Has achievements"), counts.keys.map { it.label })
        assertEquals(listOf(3, 1, 2, 1, 1, 2, 2), counts.values.toList())
    }

    @Test fun searchMatchesEveryWordAnyCaseWithoutAccents() {
        assertEquals(listOf(2L), LibraryViews.search(games, "pokemon").map { it.id })
        assertEquals(listOf(1L, 3L), LibraryViews.search(games, "KART").map { it.id })
        assertEquals(listOf(3L), LibraryViews.search(games, "mario amped").map { it.id })
        assertEquals(emptyList<Long>(), LibraryViews.search(games, "   "))
    }

    @Test fun playerTableParsesTheShippedAsset() {
        val table = LibraryViews.parsePlayers(File("src/main/assets/n64-players.tsv").readText())
        // Mario Kart 64 (USA) and GoldenEye 007 (USA) take four, Super Mario 64 (USA) one (CRCs as the Thor's library has them).
        assertEquals(4, table["434389C1"])
        assertEquals(4, table["B6330846"])
        assertEquals(1, table["3CE60709"])
        // By game code: Super Smash Bros. (NAL) four, Super Mario 64 (NSM) one.
        assertEquals(4, table["@NAL"])
        assertEquals(1, table["@NSM"])
    }

    @Test fun aHackFallsBackToItsGameCode() {
        val facts = LibraryFacts(players = mapOf("434389C1" to 4, "@NAL" to 4, "@NSM" to 1), listed = emptySet(), members = emptyMap())
        val remix = LibraryGame(9, "Smash Remix", "d".repeat(32), crc32 = "12345678", headerId = "NALE")
        val starRoad = LibraryGame(10, "Super Mario Star Road", "e".repeat(32), crc32 = "87654321", headerId = "NSME")
        val unknown = LibraryGame(11, "Homebrew", "f".repeat(32), crc32 = "11111111", headerId = null)
        assertEquals(4, LibraryViews.playersOf(remix, facts))
        assertEquals(1, LibraryViews.playersOf(starRoad, facts))
        assertEquals(null, LibraryViews.playersOf(unknown, facts))
        assertEquals(listOf(9L), LibraryViews.show(listOf(remix, starRoad, unknown), LibraryView.Filter(AutoFilter.FourPlayers), facts).map { it.id })
    }
}
