package com.joeysretro.joey64

/**
 * The GPU-specific choices in one place (README order item 15, item 38's proposals a, b, c and e; Joey 2026-11-06:
 * "After the work order"): the GPU's family, the driver Auto picks for it, the settings that differ by GPU, and the
 * start-up trial that steps Auto's driver + renderer down on a GPU where they never draw. Pure logic, so GpuRulesTest
 * pins what the four GPUs we run get (Adreno 740 Thor/Portal, 830 Odin 3, 722 Duo, Mali-G52 Flip).
 */
object GpuRules {
    enum class Vendor { ADRENO, MALI, OTHER }

    /** model: the Adreno's number (740), 0 when not known or not an Adreno; source: where it came from, for the log. */
    data class Family(val vendor: Vendor, val model: Int, val name: String, val source: String = "") {
        override fun toString() = when (vendor) {
            Vendor.ADRENO -> if (model > 0) "Adreno $model (from the $source)" else "Adreno, model not known"
            Vendor.MALI -> "Mali"
            Vendor.OTHER -> "not Adreno or Mali"
        } + if (name.isNotEmpty()) " [$name]" else ""
    }

    /** Qualcomm's marketing names in the Vulkan name, to a model (README item 38: "Adreno (TM) A12" is a 613). */
    private val MARKETING = mapOf("A12" to 613)

    /**
     * vendorId: the Vulkan vendor (0x5143 Qualcomm, 0x13B5 Arm, 0 not known); vkName: the Vulkan device name; kgslModel:
     * /sys/class/kgsl/kgsl-3d0/gpu_model ("Adreno740v2"), when readable. The number in the Vulkan name wins, so the GPUs
     * already run keep what they had; kgsl, then the marketing names, only fill in a name without one.
     */
    fun family(vendorId: Int, vkName: String, kgslModel: String?): Family {
        if (vendorId == 0x5143 || "Adreno" in vkName) {
            Regex("""Adreno \(TM\) (\d{3})""").find(vkName)?.let { return Family(Vendor.ADRENO, it.groupValues[1].toInt(), vkName, "Vulkan name") }
            Regex("""Adreno\D{0,8}(\d{3})""").find(kgslModel.orEmpty())?.let { return Family(Vendor.ADRENO, it.groupValues[1].toInt(), vkName, "kgsl gpu_model") }
            Regex("""Adreno \(TM\) (\w+)""").find(vkName)?.groupValues?.get(1)?.let { MARKETING[it] }
                ?.let { return Family(Vendor.ADRENO, it, vkName, "marketing name") }
            return Family(Vendor.ADRENO, 0, vkName)
        }
        if (vendorId == 0x13B5 || "Mali" in vkName) return Family(Vendor.MALI, 0, vkName)
        return Family(Vendor.OTHER, 0, vkName)
    }

    enum class Driver(val id: String) { FAUX123("faux123"), TURNIP("turnip"), SYSTEM("system") }

    /** A choice and the rule that made it, for the log (item 38 e: a tester's report shows which rule picked). */
    data class Pick<T>(val value: T, val rule: String)

    /** Auto's driver for the system GPU's family. */
    fun autoDriver(f: Family): Pick<Driver> = when {
        // faux123's Turnip (Duo fixes) draws Mario Party 2's text boxes without the stray lines the general build
        // leaves, and passed the 82-game compat run (2026-11-04)
        f.vendor == Vendor.ADRENO && f.model == 722 -> Pick(Driver.FAUX123, "Adreno 722: Turnip faux123")
        // The stock Adreno 740 driver (676.53) fails to compile a parallel-RDP shader; Turnip runs it. Turnip Gen8's
        // device table knows every Adreno 6xx-8xx part (README item 38, offline coverage).
        f.vendor == Vendor.ADRENO && f.model in 600..899 -> Pick(Driver.TURNIP, "Adreno 6xx-8xx: bundled Turnip")
        f.vendor == Vendor.ADRENO -> Pick(Driver.SYSTEM, "Adreno outside 6xx-8xx: system driver")
        // Mali-G52 runs parallel-RDP at full speed on the system driver; malisx2 measured ~14% slower there.
        f.vendor == Vendor.MALI -> Pick(Driver.SYSTEM, "Mali: system driver")
        else -> Pick(Driver.SYSTEM, "other GPU: system driver")
    }

    /**
     * Auto's renderer: parallel-RDP needs Vulkan 1.1 with 16-bit storage buffers (without them it fails to start and the
     * core crashes: Adreno 610), and a driver known to run it: a custom one, or stock Mali. Else GLideN64.
     */
    fun autoRenderer(vulkan11: Boolean, storage16: Boolean, customDriver: Boolean, f: Family,
                     littleCoresOnly: Boolean = false): Pick<String> = when {
        !vulkan11 -> Pick("gliden64", "no Vulkan 1.1: GLideN64")
        !storage16 -> Pick("gliden64", "no 16-bit storage buffers: GLideN64")
        customDriver -> Pick("parallel", "custom driver: parallel-RDP")
        // RK3566 (4x Cortex-A55, 1-core Mali-G52; Joey 2026-11-09, "Yes, add the rule"): parallel-RDP held full speed in
        // no game, the Mali driver's own CPU side took 39% of the CPU; GLideN64 SM64 84 vs 44 fps uncapped, OoT 65 vs 57.
        // The Flip (A75 big cores) keeps parallel-RDP.
        f.vendor == Vendor.MALI && littleCoresOnly -> Pick("gliden64", "stock Mali, only little CPU cores (A53/A55): GLideN64")
        f.vendor == Vendor.MALI -> Pick("parallel", "stock Mali: parallel-RDP")
        else -> Pick("gliden64", "stock driver not known to run parallel-RDP: GLideN64")
    }

    /** Arm's little cores by /proc/cpuinfo "CPU part": Cortex-A32, A35, A53, A55. */
    private val LITTLE_PARTS = setOf(0xd01, 0xd04, 0xd03, 0xd05)

    /** True when /proc/cpuinfo lists cores and every one is a little core (an RK3566's four A55s); false when unreadable. */
    fun littleCoresOnly(cpuinfo: String?): Boolean {
        val parts = Regex("""CPU part\s*:\s*0x([0-9a-fA-F]+)""").findAll(cpuinfo.orEmpty()).map { it.groupValues[1].toInt(16) }.toList()
        return parts.isNotEmpty() && parts.all { it in LITTLE_PARTS }
    }

    /** An environment variable (core or host switch) or a core option this GPU gets by default; --es env/opts still win. */
    data class Setting(val key: String, val value: String, val coreOption: Boolean, val rule: String)

    /** Before the driver loads (the KGSL hook reads it then); by device. */
    fun beforeDriver(deviceModel: String): List<Setting> = buildList {
        // KGSL GPU priority 1 for the bundled Turnip's contexts on the Odin 3 only (Joey 2026-11-07): +3.6% uncapped
        // there (odin3-gpu2, 13 of 14 games up); flat on the Portal (portal-gpu2). --es env N64_KGSL_PRIO= turns it off.
        if (deviceModel == "Odin3") add(Setting("N64_KGSL_PRIO", "1", false, "Odin 3: KGSL priority 1"))
    }

    /** After the driver is chosen, by the family of the GPU in use (the custom driver's, else the system's). */
    fun settings(f: Family): List<Setting> = buildList {
        val mali = f.vendor == Vendor.MALI
        val adreno = f.vendor == Vendor.ADRENO
        // Early acquire on Mali (Joey 2026-11-05): after 30 new frames in a row the next frame acquires its swapchain
        // image early. Flip compat 82/82 (flip-early30); no gain on the 120 Hz Adrenos, where GoldenEye judders with it.
        if (mali) add(Setting("N64_EARLY_ACQUIRE", "30", false, "Mali: early acquire"))
        // Single-threaded RDP command processing on Mali (README item 36): Flip +3.7% uncapped, compat 82/82
        // (flip-st). Adreno keeps the ring (Duo mixed: Banjo -6.4%, Paper Mario -5.7%).
        if (mali) add(Setting("PARALLEL_RDP_SINGLE_THREADED_COMMAND", "1", false, "Mali: single-threaded RDP commands"))
        // Lazy waking of parallel-RDP's command worker (core, on unless 0) stays on for Adreno only (Joey 2026-11-07):
        // Portal +4.4%, Duo +3.0%, compat 82/82 on both. Other GPUs were not measured with it.
        if (!adreno) add(Setting("N64_RDP_RING_LAZY", "0", false, "not Adreno: lazy RDP worker off"))
        // The recompiler's compile thread on Mali only (2026-11-08, Joey: "your call"): Flip MK64 race start worst
        // frame 88-90 -> 45 ms, speed -0.4% (flip-drcthread4). Adreno lost on worst frames (odin3-drcthread4).
        if (mali) add(Setting("N64_DRC_THREAD", "1", false, "Mali: recompiler compile thread"))
        // Smoother timing (CountPerOp=1) for the games that lag on hardware, strong devices only (Joey 2026-11-06;
        // compat passed on the Odin 3 and Portal): Adreno 730 and up. The host picks the games (kSmootherGames).
        if (adreno && f.model >= 730) add(Setting("N64_SMOOTHER", "1", false, "Adreno 730+: smoother timing"))
        // parallel-RDP synchronous on Adreno: async there gives black screens (Yoshi's Story, Resident Evil 2, Cruis'n
        // USA) or halts (New Tetris), and sync costs ~1-2 ms a frame. On Mali sync can cost a third of the frame
        // rate, so async plus kSyncRdpGames.
        if (adreno) add(Setting("mupen64plus-parallel-rdp-synchronous", "True", true, "Adreno: parallel-RDP synchronous"))
        // No pipeline pre-build on Adreno (Joey 2026-11-07): with the driver cache cold (after a driver update or a cleared
        // cache) building the saved list first stalled ~0.6 s at start in every game on the Portal (portal-pbfirst),
        // on-demand compiling 63-170 ms; warm launches wait 0 ms either way. Mali keeps it.
        if (adreno) add(Setting("N64_RDP_PREBUILD", "0", false, "Adreno: no pipeline pre-build"))
    }

    /**
     * parallel-RDP's highest upscale before the storage buffer range: 4x on the Adreno 722 drops most geometry on both
     * Turnip builds tried (and runs at 40-55 fps), 2x is right; elsewhere 8x.
     */
    fun upscaleTop(f: Family): Pick<Int> =
        if (f.vendor == Vendor.ADRENO && f.model == 722) Pick(2, "Adreno 722: 2x") else Pick(8, "8x")
}

/**
 * The start-up trial (README item 38 b): Auto's driver + renderer on a GPU are a trial until a game has drawn a frame
 * with them. A start counts as failed when it dies or quits before a drawn frame; after two failed starts in a row the
 * next start steps down one level: 0 Auto's pick, 1 the system driver (its renderer by the usual rule), 2 GLideN64 on
 * the system driver. Kept per install (an update tries Auto again) and per system GPU. Only Auto's choice is tried:
 * a driver or renderer picked in Settings or by a dev switch is the user's.
 */
class GpuTrial(private val store: Store, install: String, systemGpu: String) {
    /** SharedPreferences in the app; a map in the tests. Writes must be on disk before put returns (a crash follows). */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
        fun keys(): Set<String>
    }

    private val key = "trial|$install|$systemGpu"

    private data class State(val level: Int, val fails: Int, val good: Boolean)

    private fun read(): State {
        val p = store.get(key)?.split(',')
        return State(p?.getOrNull(0)?.toIntOrNull() ?: 0, p?.getOrNull(1)?.toIntOrNull() ?: 0, p?.getOrNull(2) == "good")
    }

    private fun write(s: State) = store.put(key, "${s.level},${s.fails},${if (s.good) "good" else "trial"}")

    /** The level this start runs at, after stepping down if the last two starts failed; and what happened, for the log. */
    fun decide(): Pick {
        // Records of earlier installs or system drivers never apply again (the key changed): drop them.
        for (k in store.keys()) if (k.startsWith("trial|") && k != key) store.put(k, null)
        var s = read()
        var stepped = false
        if (!s.good && s.fails >= FAILS && s.level < TOP) { s = State(s.level + 1, 0, false); write(s); stepped = true }
        val what = when {
            s.good -> "known good at level ${s.level}"
            stepped -> "stepped down to level ${s.level} after $FAILS starts without a drawn frame"
            s.level == TOP && s.fails >= FAILS -> "level ${s.level} (the lowest) has failed ${s.fails} times"
            else -> "trial at level ${s.level}, ${s.fails} failed start(s) so far"
        }
        return Pick(s.level, what)
    }

    data class Pick(val level: Int, val what: String)

    /** Just before the game loads: counts the start as failed until [drew]. */
    fun begin() { val s = read(); if (!s.good) write(s.copy(fails = s.fails + 1)) }

    /** The game didn't load (no ROM, the core refused it): not the GPU's doing, so the start doesn't count. */
    fun cancel() { val s = read(); if (!s.good && s.fails > 0) write(s.copy(fails = s.fails - 1)) }

    /** A frame was drawn: this level is good on this GPU from now on. */
    fun drew() { val s = read(); if (!s.good) write(State(s.level, 0, true)) }

    companion object {
        const val FAILS = 2
        const val TOP = 2
    }
}
