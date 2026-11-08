package com.joeysretro.joey64.emulation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EmuSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun apply(vararg kv: Pair<EmuKey, String>, needsPak: Boolean = false) = EmuApply.of(EmuSettings(mapOf(*kv)), needsPak)

    @Test fun autoSetsNothing() {
        val a = apply()
        assertTrue(a.coreOptions.isEmpty())
        assertTrue(a.env.isEmpty())
        assertFalse(a.overclockSet)
    }

    @Test fun overclockSteps() {
        assertEquals(mapOf("mupen64plus-CountPerOp" to "0"), apply(EmuKey.Overclock to "normal").coreOptions)
        assertEquals(mapOf("mupen64plus-CountPerOp" to "1"), apply(EmuKey.Overclock to "2x").coreOptions)
        assertEquals(mapOf("mupen64plus-CountPerOp" to "1", "mupen64plus-CountPerOpDenomPot" to "1"), apply(EmuKey.Overclock to "4x").coreOptions)
        assertTrue(apply(EmuKey.Overclock to "normal").overclockSet)
    }

    @Test fun cpuCore() {
        assertEquals("dynamic_recompiler", apply(EmuKey.CpuCore to "recompiler").coreOptions["mupen64plus-cpucore"])
        assertEquals("cached_interpreter", apply(EmuKey.CpuCore to "interpreter").coreOptions["mupen64plus-cpucore"])
    }

    @Test fun expansionPakOffNeverForAGameThatNeedsIt() {
        assertEquals("True", apply(EmuKey.ExpansionPak to "off").coreOptions["mupen64plus-ForceDisableExtraMem"])
        assertTrue(apply(EmuKey.ExpansionPak to "off", needsPak = true).coreOptions.isEmpty())
        assertTrue(apply(EmuKey.ExpansionPak to "on").coreOptions.isEmpty())
        assertTrue(ExpansionPak.needed("NDOE"))
        assertTrue(ExpansionPak.needed("NZSJ"))
        assertTrue(ExpansionPak.needed("NPDP"))
        assertFalse(ExpansionPak.needed("NSME"))
        assertFalse(ExpansionPak.needed(null))
    }

    @Test fun thermalGoesToTheEnvironment() {
        assertEquals("1", apply(EmuKey.Thermal to "on").env["N64_THERMAL_GOV"])
        assertTrue(apply(EmuKey.Thermal to "off").env.isEmpty())
    }

    @Test fun allGamesHasNoExpansionPak() {
        val e = EmuSettings.resolve(mapOf(EmuKey.ExpansionPak to "off", EmuKey.Overclock to "2x"), null)
        assertEquals("on", e[EmuKey.ExpansionPak])
        assertEquals("2x", e[EmuKey.Overclock])
        assertEquals("off", EmuSettings.resolve(emptyMap(), mapOf(EmuKey.ExpansionPak to "off"))[EmuKey.ExpansionPak])
    }

    @Test fun storeKeepsAGamesOwn() {
        val s = EmuStore(File(tmp.root, "emulation"), null)
        s.saveAll(mapOf(EmuKey.Overclock to "2x", EmuKey.ExpansionPak to "off"))
        assertEquals(mapOf(EmuKey.Overclock to "2x"), s.all())
        s.saveGame("Banjo-Kazooie (USA)", mapOf(EmuKey.CpuCore to "interpreter"))
        assertEquals("interpreter", s.forGame("Banjo-Kazooie (USA)")[EmuKey.CpuCore])
        assertEquals("2x", s.forGame("Banjo-Kazooie (USA)")[EmuKey.Overclock])
        assertEquals(listOf("Banjo-Kazooie (USA)"), s.gamesWithOwn())
        s.saveGame("Banjo-Kazooie (USA)", emptyMap())
        assertTrue(s.gamesWithOwn().isEmpty())
    }

    @Test fun thermalMovesOverFromVideo() {
        val video = File(tmp.root, "video").apply { File(this, "games").mkdirs() }
        File(video, "all.json").writeText("""{"version":1,"thermal":"on","resolution":"2"}""")
        File(video, "games/Banjo.json").writeText("""{"version":1,"thermal":"off"}""")
        File(video, "games/Tooie.json").writeText("""{"version":1,"thermal":"on","aspect":"16:9"}""")
        val s = EmuStore(File(tmp.root, "emulation"), video)
        assertEquals("on", s.all()[EmuKey.Thermal])
        assertEquals("off", s.game("Banjo")!![EmuKey.Thermal])
        assertEquals("on", s.game("Tooie")!![EmuKey.Thermal])
        // Gone from Video; a game's file that held only it goes too, the rest stay.
        val all = JSONObject(File(video, "all.json").readText())
        assertFalse(all.has("thermal")); assertEquals("2", all.getString("resolution"))
        assertFalse(File(video, "games/Banjo.json").exists())
        assertEquals("16:9", JSONObject(File(video, "games/Tooie.json").readText()).getString("aspect"))
        // Opening it again changes nothing.
        EmuStore(File(tmp.root, "emulation"), video)
        assertEquals("on", s.all()[EmuKey.Thermal])
    }

    @Test fun autoPicks() {
        assertEquals("interpreter", EmuAuto.cpuCore("NGUJ"))
        assertEquals("recompiler", EmuAuto.cpuCore("NSME"))
        assertEquals("2x", EmuAuto.overclock("NGEE", true))
        assertEquals("normal", EmuAuto.overclock("NGEE", false))
        assertEquals("normal", EmuAuto.overclock("NSME", true))
        assertNull(EmuAuto.overclock("NGEE", null))
    }

    /** The popups' Auto lines copy the host's lists: they must stay the same. */
    @Test fun autoListsMatchTheHost() {
        val cpp = File("src/main/cpp/libretro_host.cpp").readText()
        fun list(name: String) = Regex("""$name\[\]\s*=\s*\{([^}]*)\}""").find(cpp)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toSet()
        assertEquals(EmuAuto.INTERPRETER_GAMES, list("kInterpreterGames"))
        assertEquals(EmuAuto.SMOOTHER_GAMES, list("kSmootherGames"))
    }
}
