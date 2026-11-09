package com.joeysretro.joey64

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GpuRules must give the four GPUs we run exactly what the old checks in MainActivity gave them (README order item 15). */
class GpuRulesTest {
    private val adreno = 0x5143
    private val arm = 0x13B5

    private fun env(f: GpuRules.Family) = GpuRules.settings(f).associate { it.key to it.value }

    @Test fun thorAndPortal740() {
        val f = GpuRules.family(adreno, "Adreno (TM) 740", null)
        assertEquals(740, f.model)
        assertEquals(GpuRules.Driver.TURNIP, GpuRules.autoDriver(f).value)
        // with Turnip loaded the name is Turnip's
        val t = GpuRules.family(adreno, "Turnip Adreno (TM) 740", null)
        assertEquals(mapOf("N64_SMOOTHER" to "1", "mupen64plus-parallel-rdp-synchronous" to "True", "N64_RDP_PREBUILD" to "0"), env(t))
        assertEquals(8, GpuRules.upscaleTop(t).value)
        assertEquals("parallel", GpuRules.autoRenderer(true, true, true, t).value)
        assertEquals("gliden64", GpuRules.autoRenderer(true, true, false, f).value)
    }

    @Test fun odin3830() {
        val f = GpuRules.family(adreno, "Adreno (TM) 830", "Adreno830v2")
        assertEquals(GpuRules.Driver.TURNIP, GpuRules.autoDriver(f).value)
        assertEquals(mapOf("N64_SMOOTHER" to "1", "mupen64plus-parallel-rdp-synchronous" to "True", "N64_RDP_PREBUILD" to "0"), env(f))
        assertEquals(listOf("N64_KGSL_PRIO"), GpuRules.beforeDriver("Odin3").map { it.key })
        assertTrue(GpuRules.beforeDriver("Retroid Pocket 5").isEmpty())
    }

    @Test fun duo722() {
        val f = GpuRules.family(adreno, "Turnip Adreno (TM) 722", null)
        assertEquals(GpuRules.Driver.FAUX123, GpuRules.autoDriver(GpuRules.family(adreno, "Adreno (TM) 722", null)).value)
        assertEquals(mapOf("mupen64plus-parallel-rdp-synchronous" to "True", "N64_RDP_PREBUILD" to "0"), env(f))
        assertEquals(2, GpuRules.upscaleTop(f).value)
    }

    @Test fun flipMaliG52() {
        val f = GpuRules.family(arm, "Mali-G52 MC2", null)
        assertEquals(GpuRules.Vendor.MALI, f.vendor)
        assertEquals(GpuRules.Driver.SYSTEM, GpuRules.autoDriver(f).value)
        assertEquals(mapOf("N64_EARLY_ACQUIRE" to "30", "PARALLEL_RDP_SINGLE_THREADED_COMMAND" to "1", "N64_RDP_RING_LAZY" to "0",
            "N64_DRC_THREAD" to "1", "N64_DRC_JCACHE" to "0"), env(f))
        assertEquals("parallel", GpuRules.autoRenderer(true, true, false, f).value)
        assertEquals(8, GpuRules.upscaleTop(f).value)
    }

    @Test fun rk3566LittleCoresGetGliden() {
        // the RK3566 report (2026-11-09): four Cortex-A55s, so stock Mali goes to GLideN64
        val rk = (0..3).joinToString("\n\n") { "processor\t: $it\nCPU implementer\t: 0x41\nCPU part\t: 0xd05\n" } + "\nHardware\t: Rockchip RK3566"
        val f = GpuRules.family(arm, "Mali-G52", null)
        assertTrue(GpuRules.littleCoresOnly(rk))
        assertEquals("gliden64", GpuRules.autoRenderer(true, true, false, f, GpuRules.littleCoresOnly(rk)).value)
        // a dev driver stays the user's choice
        assertEquals("parallel", GpuRules.autoRenderer(true, true, true, f, true).value)
        // the Flip (T618: 6x A55 + 2x A75) keeps parallel-RDP; unreadable cpuinfo changes nothing
        val flip = (0..5).joinToString("\n") { "CPU part\t: 0xd05" } + "\n" + (6..7).joinToString("\n") { "CPU part\t: 0xd0a" }
        assertEquals(false, GpuRules.littleCoresOnly(flip))
        assertEquals(false, GpuRules.littleCoresOnly(null))
        assertEquals(false, GpuRules.littleCoresOnly(""))
        assertEquals("parallel", GpuRules.autoRenderer(true, true, false, f, GpuRules.littleCoresOnly(flip)).value)
    }

    @Test fun lastImpactGetsGliden() {
        // SM64 Last Impact by its MD5 (either case); plain SM64 and an unknown game have no per-game renderer
        assertEquals("gliden64", GpuRules.gameRenderer("70059d4d7ba7ac0f0741b44aec8b44dc")?.value)
        assertEquals("gliden64", GpuRules.gameRenderer("70059D4D7BA7AC0F0741B44AEC8B44DC")?.value)
        assertEquals(null, GpuRules.gameRenderer("20b854b239203baf6c961b850a4a51a2"))
        assertEquals(null, GpuRules.gameRenderer(null))
    }

    @Test fun newFamilies() {
        // a marketing name gets its model, so Turnip
        assertEquals(613, GpuRules.family(adreno, "Adreno (TM) A12", null).model)
        // kgsl fills in a name without a number; the name's number wins over kgsl's
        assertEquals(650, GpuRules.family(adreno, "Adreno (TM) A1", "Adreno650v2").model)
        assertEquals(740, GpuRules.family(adreno, "Adreno (TM) 740", "Adreno999v1").model)
        assertEquals(GpuRules.Driver.SYSTEM, GpuRules.autoDriver(GpuRules.family(adreno, "Adreno (TM) 530", null)).value)
        val other = GpuRules.family(0x1010, "PowerVR B-Series BXM-8-256", null)
        assertEquals(GpuRules.Vendor.OTHER, other.vendor)
        assertEquals(mapOf("N64_RDP_RING_LAZY" to "0", "N64_DRC_JCACHE" to "0"), env(other))
        assertEquals("gliden64", GpuRules.autoRenderer(true, false, true, other).value)
    }

    private class MapStore : GpuTrial.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        override fun keys(): Set<String> = m.keys.toSet()
    }

    @Test
    fun oldRecordsAreDropped() {
        val st = MapStore()
        GpuTrial(st, "1", "Mali-G52 MC2").apply { decide(); begin(); drew() }
        GpuTrial(st, "2", "Mali-G52 MC2").apply { decide(); begin() } // a new install: a new record, the old one goes
        assertEquals(setOf("trial|2|Mali-G52 MC2"), st.keys())
    }

    @Test fun trialStepsDownAfterTwoFailedStarts() {
        val st = MapStore()
        fun t() = GpuTrial(st, "1", "Adreno (TM) 999")
        assertEquals(0, t().decide().level); t().begin()            // dies
        assertEquals(0, t().decide().level); t().begin()            // dies again
        assertEquals(1, t().decide().level); t().begin(); t().cancel() // the game didn't load: doesn't count
        assertEquals(1, t().decide().level); t().begin()
        assertEquals(1, t().decide().level); t().begin()
        assertEquals(2, t().decide().level); t().begin(); t().drew()
        assertEquals(2, t().decide().level)
        repeat(3) { t().begin() }                                   // good stays good
        assertEquals(2, t().decide().level)
        // a new install tries Auto again
        assertEquals(0, GpuTrial(st, "2", "Adreno (TM) 999").decide().level)
    }

    @Test fun trialGoodOnFirstFrame() {
        val st = MapStore()
        val t = GpuTrial(st, "1", "Mali-G52 MC2")
        t.decide(); t.begin(); t.drew()
        t.begin(); t.begin(); t.begin()
        assertEquals(0, GpuTrial(st, "1", "Mali-G52 MC2").decide().level)
    }

    @Test fun trialStopsAtTheLowestLevel() {
        val st = MapStore()
        fun t() = GpuTrial(st, "1", "x")
        repeat(10) { t().decide(); t().begin() }
        assertEquals(GpuTrial.TOP, t().decide().level)
    }
}
