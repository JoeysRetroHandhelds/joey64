# What other N64 emulators can teach us (review 2026-11-03)

## Ranked ideas
1. **Done (core 502015c, 2026-11-03): scan out at the active picture start; ~16 ms less lag, same speed.** **One frame less display lag** (from ares). Our core captures the frame at the VI interrupt, before the game points
   the display at the frame it just finished, so we likely show every frame ~16 ms late. Move the capture after the
   game's interrupt handler. Measurable with our poll->display stat. Small change, big win.
2. **Done (smart sync, then precise smart sync, default since core 3639398).** **Smart parallel-RDP sync** (from gopher64). Run async, remember where the GPU drew, wait only when the game reads
   there. Async speed with sync correctness: fixes the Flip's sync cost and makes Adreno safe on async. Needs page
   protection for our dynarec (the core's built-in framebuffer hooks are off under the dynarec). Bigger project.
3. **Per-game "smoother" mode** (Project64 counter factor 1, gopher64 CPU overclock). Mario Kart 64, Turok, Jet Force
   Gemini, SW Racer, Rogue Squadron etc. lag on real hardware; doubling CPU speed smooths them. Only on strong devices.
4. **Save type part done (core 8e8f5c7); CountPerOp etc. not yet.** **Unknown hacks inherit the base game's settings** (from parallel-n64): look up by cartridge ID when the hash is
   unknown, so hacks get the right save type and timing.
5. **Done (core 890dbdb, 2dc3683).** **Port missing upstream mupen64plus fixes**: Game Boy save truncation (with Transfer Pak work), Oct 2026 bounds
   checks (controller channels, savestates, 64DD sectors, netplay packets).
6. **Online multiplayer**: reuse the mupen64plus netplay protocol (our core has it) and RMG's netplay server; gopher64
   has P2P netplay as a second reference.
7. **Accessories**: gopher64 emulates the Voice Recognition Unit and Transfer Pak with Game Boy carts (GPL-3: ideas only).
8. **Accuracy fixes in ares to check against ours**: timer interrupt when COMPARE=0, COUNT reads, banked SRAM (Dezaemon 3D
   saves), CACHE opcode, LL/SC. ares is ISC licensed, so code can be reused.
9. **Test sets**: Dillonb/n64-tests, N64brew jam homebrew (libdragon), for accuracy and the homebrew roadmap item.

Licenses (Joey64 is GPL-3.0-or-later since 2026-11-05): ares ISC, gopher64 and RMG GPL-3 can be reused; Project64 is GPL-2 only (reimplement ideas, don't copy).

## Notes per project
Not on GitHub any more (404, no fork found by search): jgcodes2020/jgcen64, Kaizen-Emulator/Kaizen, simple64/simple64
(its author now makes gopher64), 1964emu/1964 (only a 2014 continuation, 1964-Emulation/1964, 0 stars).

## gopher64 (Rust, GPL-3.0, active, has an Android app + Play Store)
- Interpreter CPU (+ cache emulation, cache.rs), LLE RSP interpreter (vector unit via sse2neon on ARM), parallel-RDP fork.
- **Dirty-region RDP sync** (parallel-rdp/interface.cpp): parallel-RDP runs async; each triangle/rect marks the current
  color image (scissor area) in an RDRAM bitmap (8-byte granularity). On SyncFull it records a timeline signal.
  Any CPU read/write of RDRAM, PI/SI/SP DMA touching a dirty region -> wait_for_timeline(signal), clear bitmap.
  = async speed, sync correctness. Ours: sync on Adreno (~1-2 ms), async on Mali + per-game list (Snap etc).
  Catch for us: our dynarec reads RDRAM directly, so CPU reads can't be hooked like an interpreter; options are
  mprotect on dirty pages (fault handler waits, then unprotects) or only DMA hooks + the existing per-game list.
  License: reimplement the idea, don't copy (GPL-3 vs core GPLv2+).
- PI DMA: hardware-accurate alignment (& !1) plus the "first 128 bytes" unaligned-length rule (n64brew wiki).
  So it would not run Star Road / Last Impact either unless those builds avoid odd DMA.
- PI write: odd length >= 0x7f rounds up, length <= 0x80 subtracts dram_addr & 7 (n64brew Unaligned DMA). Compare ours.
- Android: libadrenotools fork (like us), N64Activity.kt, touch overlay.
- Extras we have on the roadmap: VRU (vru.rs + UI dialog), Transfer Pak with GB cart, SC64 cart emulation, netplay
  (P2P, netplay.rs 19 KB + ui/netplay.rs), RetroAchievements (rcheevos), CRT shader, CPU overclock.
- Its parallel-RDP fork patches: Turnip broken_present_fence workaround; push descriptor / descriptor buffer off
  on Android (later reverted after a Granite update). Only relevant when we update parallel-RDP/Granite.
  Checked our core: mupen64plus already has FB hooks (fb.c: protect_framebuffers remaps FB pages to read_rdram_fb /
  write_rdram_fb, pre_framebuffer_read on DMA), but they are disabled under EMUMODE_DYNAREC ("Dynarecs currently miss
  some of the read/writes"), and the parallel-RDP plugin's FBRead/FBWrite/FBGetFrameBufferInfo are empty stubs.
  So for the dynarec: host page protection (mprotect dirty FB pages; SIGSEGV handler waits for the RDP and unprotects),
  DMA paths through pre_framebuffer_read. Real project; payoff = Mali sync cost gone, Adreno async-safe.

## Project64 (C++, GPL-2.0 = same as our core, code reuse OK; has an Android port)
- Config/Project64.rdb: 1579 games, per-game keys. Counts: RDRAM Size 1181, Counter Factor 550, Unaligned DMA 301,
  Culling 267, Save Type 86, Clear Frame 86, SMM-* (self-modifying-code detection) ~290, ViRefresh 44, Delay SI 22,
  Fixed Audio 19, Fast SP 15, AudioResetOnLoad 72, AllowROMWrites 11.
- "Unaligned DMA=1" on 301 SM64-style hacks (Star Road, Missing Stars, Star Revenge, SM74...) = the same fix we
  ported from FZ, opt-in per game there. Our rule (unknown ROM -> unaligned) already covers hacks not in our ini;
  only 3 PJ64-marked games are in our ini and still align: Smash Remix 0.9.4 and two SM64 Spanish translations
  (PJ64 matches by header CRC, which hacks often share, so weak evidence). Not changed; test before marking.
- Idea: compare PJ64 Counter Factor / ViRefresh / Delay SI per game against mupen64plus.ini CountPerOp etc.
- Counter Factor vs our CountPerOp (CRC-matched, scratchpad/pj64cf.txt): 155 differences, 115 where PJ64 uses 1 and we
  use 2. Official ones: Mario Kart 64, Turok 1, Jet Force Gemini, SW Racer, Rogue Squadron, Quake II, Rayman 2, Re-Volt,
  MK Trilogy, Vigilante 8, NASCAR, FIFA 99, Battlezone... CF1 = CPU twice as fast per frame = an overclock: games that
  lag on hardware run smoother (and timing-sensitive ones can break). Not a compat fix. Idea: per-game "smoother"
  (CountPerOp=1 / overclock) on strong devices (Odin 3, Portal), off on the Flip; gopher64 has a CPU overclock option too.
  34 the other way (PJ64 2, ours 1: Smash Bros, Gauntlet Legends, Army Men, Nuclear Strike, Top Gear Hyper Bike...).
- RDRAM Size=4 in PJ64 for 3 games we run with 8 MB (see pj64cf.txt).

## ares (C++, ISC license = permissive, reuse OK; very active N64 work)
- **One frame of display latency** (commit "n64: reduce 1 frame of input latency", 2026-06): ares scanned out at
  vblank (line 0), but games set the new VI origin in their VI interrupt handler, so the previous frame was shown.
  Moved scanout to vstart. Our core: vi_vertical_interrupt_event() calls gfx.updateScreen() BEFORE raising MI_INTR_VI,
  i.e. before libultra's VI manager applies the osViSwapBuffer origin -> likely the same +1 frame (~16.7 ms).
  Fix idea: scan out after the handler (an event at the vstart line, or at the next point the CPU acks VI) and end the
  libretro frame there. Measure with our poll->display stat. TOP candidate (latency is a core goal).
- Other accuracy commits worth checking against mupen64plus: timer interrupt never firing when COMPARE=0; COUNT read
  returns up-to-date cycles; banked SRAM (Dezaemon 3D saves); flash rewrite; PI bus rewrite; in-block self-modifying
  code with JIT; CACHE opcode accuracy; SC/SCD don't clear LLbit; RTC first-boot speedup; GameCube controller support.
- Recompiler: sljit-based JIT with ARM64 support ("commit the PC at the end of every JIT block", "remove hardcoded 8 MiB
  limit"); ares RDP = parallel-RDP (ares/n64/vulkan).

## libretro/parallel-n64 (Parallel Launcher edition; the SM64-romhack community's emulator; very active)
- Per its README: unaligned-DMA toggle, IS Viewer logging, RTC time/savestate, GameCube controller, EverDrive-header
  save-type detection, ROMs > 64 MiB, SummerCart64 SD card emulation, GLideN64 changes for old SM64 hacks.
- 2026-06: overrides keyed on cartridge ID (not hash) so hacks inherit the base game's save type / CountPerOp.
  Idea for us: unknown ROM -> fall back to the cartridge ID's settings (our ini lookup is MD5, then header CRC).
- 2026-05: F3DEX3 microcode in GLideN64 (HackerSM64 family). Ours already has it (GLideN64/src/uCodes/F3DEX3.cpp).
- Lots of recent savestate, GLideN64 read-back and paraLLEl fixes; worth diffing when chasing a specific bug.
- **New since 11-03 (checked 2026-11-04, master e8cbdbb): an HLE graphics path for parallel-RDP.** `mupen64plus-video-angrylion/rdp_emit_*.c` (~26,000 lines of C89) walks the game's display list on the CPU (F3D, F3DEX2, S2DEX, ZSort, F3DDKR, T3DUX, Turbo3D, Rogue Squadron/Naboo, ...) and writes the RDP commands the microcode would have produced into a host buffer; `mupen64plus-video-paraLLEl/parallel_hle.cpp` hands that buffer to parallel-RDP's normal command path (`RDP::set_hle_cmd_buffer`, guest RDRAM never written). So geometry is HLE (fast, no RSP microcode run) and rasterising stays parallel-RDP (accurate framebuffer effects). For us the RSP is the top CPU cost (vector helpers 21-37% of the Portal emulation thread), so this could remove most of it in the games it covers. Risks: HLE geometry accuracy (clipping, lighting, per-game microcode quirks), it is new and large, and its notes show bugs still being found (OoT froze when a flush overflowed parallel-RDP's 32768-word command table). GPL-2 like our core, so it could be ported: the emitter files, the glue and the command-buffer redirect. Worth a measured try on the Flip (CPU-bound) with a compat run before anything else.
- Also new: a frontend temporal-upscaler interface (`RETRO_ENVIRONMENT_GET_HW_RENDER_UPSCALER_INTERFACE`): parallel-RDP hands motion vectors, depth and a translucency mask to the frontend with a jittered frame, for FSR2-style upscaling. A display option for later (Joey designs display options).
- **parallel-RDP speed work in parallel-n64, 2026-10-28 to 11-03 (checked 2026-11-04).** Their copy is a newer Granite base (COMMIT 65f4f4da, not in Themaister's public repos) that they have been stripping down to C89 and no C++ runtime. **Status 2026-11-04:** 1, 2, 3 and 5 are ported (joey64-core-pn64 branch pn64-ports: 9887a5b, 04f4e32, 05532e3, 9924334), bench queued on all four devices; 4 needs no port (our c1ebc71 already does the same); `ac9c6e8` not ported (it only helps upscaling with downscale). Original list:
  1. `ea0dd4e` upload only the RDRAM pages that differ (compare with the GPU's copy where RDRAM is mapped, cached and coherent, which is our phones' unified memory); `PARALLEL_RDP_FULL_RDRAM_UPLOAD=1` switch.
  2. `0818cbb` build the primitive streams straight into the mapped upload buffers: on unified memory, nothing is copied.
  3. `0cf0839` reuse the VI's intermediate images across frames (they were created and freed every frame).
  4. `18c014f` RDRAM readback: plain stores for 16-byte chunks the GPU wrote fully, skip untouched ones, masked store only for partial ones (compare with our NEON masked_memcpy, c1ebc71).
  5. `d8fd0cb` build last session's pipelines ahead from a saved list: fixes the first-launch shader compile (seen 2026-11-04: Flip OoT 109 fps on the first launch after the parallel-RDP update vs 139 warm).
  Also there: `ac9c6e8` VI downscale and deinterlace folded into the scale pass, `1907e9d`/`d9189e3` command thread runs commands in the ring (compare with our lock-free ring).

## RMG (mupen64plus front-end, GPL-3): netplay via rmg-netplay-server (mupen64plus netplay protocol, which our core
  has code for). Online multiplayer could reuse the protocol/server and play against RMG desktop users.
  Checked again 2026-11-05 (Joey asked about v0.9.1; our clone is that tag, d16e254): the first pass only read the netplay side. Not read before: `Source/3rdParty/mupen64plus-video-softrdp` (SoftRDP, PixelMechanic0, MIT): a threaded software RDP written from scratch in C17, passes parallel-RDP's conformance tests against angrylion, optional 2x internal resolution (about 4x the CPU work), presents through OpenGL 3.3, desktop only (Windows/Linux/macOS), up to 12 render threads. For us at most a fallback renderer for GPUs parallel-RDP can't use (the Adreno 610 runs GLideN64 today); its cost on our CPUs is being measured (below). Read 2026-11-05: the renderer is plain C17 with no SIMD code of its own (on x86 it only adds -mavx2 and leaves vectorising to the compiler), threads by scanline interleaving (every worker replays the whole command batch with its own RDP/TMEM state and draws its own rows; batches flush when a render target changes or a texture load reads pixels still being drawn), one worker per physical core by default with the emulation thread as worker 0. It builds unchanged for arm64 Android with NDK clang at its own -O3 (25 objects, `scratchpad/softrdp/build.sh`), and a test core runs it in angrylion's slot (core branch `softrdp-try` b7ca776, glue behind JOEY_SOFTRDP; app-srdp). Queued 2026-11-05 on the Flip (weakest CPU) and the Odin 3 (strongest) after their queues (`chain62_flip_srdp.sh`, `chain58_odin3_srdp.sh`): a picture check (frame 600 of OoT, SM64, GoldenEye saved as PNG) and `bench/srdp.txt`, uncapped, 6 games x 2: parallel-RDP vs the core's angrylion vs SoftRDP with auto and 4 workers. The question it answers: can a CPU RDP run full speed on a handheld whose GPU can't run parallel-RDP (today such GPUs get GLideN64). Also bundled: vosk-api (speech recognition for the VRU, on our extras list).
## ps2-native-recomp (exoteric33, MIT, read 2026-11-05, Joey asked): a PS2 static recompiler (built on ps2-web-recomp): translates a game's main-CPU code to C++ ahead of time and rebuilds the rest of the console in a runtime (GS as GPU compute, VU1 programs fingerprinted and translated, IOP interpreted, SPU2/IPU/kernel in C++). Desktop only (Windows, Linux, macOS; D3D12/Vulkan/Metal via wgpu), went public 2026-10-28, v0.0.1, 19 games tested, slower than PCSX2 so far. Not N64 and not an emulator we can take code from. Ideas that map to us, none new: finding code ahead of time and recording missed jump targets in test runs is the saved block list idea (item 17) taken further; translating the delay slot into the branch is what new_dynarec does; fingerprinting VU microcode to swap in translated versions is like recognising RSP microcode (HLE). The N64 version of the whole approach is N64Recomp / Zelda64Recomp (per-game ports, not an emulator); reviewed below.
## N64Recomp + N64ModernRuntime (Wiseguy/Mr-Wiseguy, MIT; read 2026-11-05, N64Recomp 598ce20, N64ModernRuntime e4342ae, Zelda64Recomp b65c482): a static recompiler that turns a game's MIPS code into C ahead of time, and a runtime (librecomp + ultramodern) that replaces libultra and the hardware with native code; Zelda64Recomp and the other recomp ports are built with it, drawing through RT64 (an HLE renderer). What it needs: an ELF with symbols for the game (a decompilation or a hand-made symbol file), so it works per game, not on any ROM; no use as a CPU path for an emulator that runs whatever ROM it is given. LiveRecomp (sljit) recompiles mod code at run time, MIPS CPU functions only, for the recomp ports' mod loader; our new_dynarec already does that job. RSPRecomp (the part that could touch our speed): turns one RSP microcode (given its IMEM offset, size and a list of indirect jump targets) into a C++ function that runs a whole task to its break; vector ops are ares's SSE code, through sse2neon on ARM. It only covers non-graphics microcode: SP_STATUS, DMA busy/full and DPC_STATUS read as 0, the semaphore is always free, DMAs finish at once, yielding is ignored, and any other COP0 register (the DP command registers a graphics microcode writes) stops the tool. Zelda64Recomp uses it for exactly two: aspMain (audio) and njpgdspMain (JPEG). For us: audio microcode already runs as HLE audio (no RSP at all, default since core 4432676), so the part it supports gives us nothing. Graphics microcode (F3DEX2, S2DEX...) is what our RSP time goes to, and AOT for it would need DP register writes, yields and DMA timing added; it would be one compiled function per known microcode, keyed by an IMEM hash, falling back to parallel-RSP's JIT. What it could win, from our own numbers: the NEON vector JIT cut the OoT emulation thread's instructions 8.7% and moved fps +0.7% (Portal) and 0 (Flip, also in the Mario Tennis dip), because the emulation thread waits on the GPU side more than half the time; an AOT microcode is the same kind of saving, larger at most by what clang's whole-function optimisation beats Lightning's per-block code. So: not now. It stays on the future roadmap ('ahead-of-time compiled RSP microcode'), worth building only once a device is shown CPU-bound inside the RSP (the Flip and Portal profiles show the GPU side as the limit).
## Dillonb/n64: LLE emulator, has a test-ROM suite (dillonb/n64-tests) + docs (n64.readthedocs.io) -> accuracy test set.
## cen64: RTL-level accuracy reference, too slow for handhelds. DaedalusX64: HLE speed for PSP/3DS/Vita, low value now.

## mupen64plus-core upstream (checked which recent fixes our core has, scratchpad/upcheck.py)
- Have: mempak players 2-4, transfer pak fix, large page sizes for dynarec cache (16 KB-page Android devices), TLB
  crash/halt fixes, dma_pi_write underflow, SSSV fixes, Pokemon Stadium SiDmaDuration, 64DD entries, Wipeout 64.
- Ported 2026-11-03/04 (core 890dbdb, 2dc3683), was missing: 902bc60f "Don't truncate Game Boy RAM files on their first write" (2026-10, Transfer Pak saves; data-loss
  risk for Pokemon Stadium GB saves) -> port. Also 2026-10 robustness: pif channel bounds (305d57a0), savestate event
  queue (4f71d580), dd sector bounds (cbab344e), netplay packet validation (ba95bab9). Cheap ports.

## MAME: N64 driver mostly dormant (last real N64 work 2023: interlace). Only Aleck64 arcade boards (ares has those too).
## n64.dev: community hub -> n64brew wiki (hardware docs, e.g. PI unaligned DMA, used by gopher64/mupen), libdragon,
   homebrew jam games (test set for the homebrew roadmap item).

## flycast (Dreamcast, C++, GPL-2.0+ = code reuse OK; very active, Android app with Vulkan, Swappy, adrenotools)
Checked 2026-11-03 (commit of 2026-10-31). Not N64, but the Android side is mature and several parts carry over:
- **Rollback netplay via GGPO with page-delta savestates** (core/network/ggpo.cpp, core/hw/mem/mem_watch.*): RAM is
  mprotected each frame, the SIGSEGV handler records which pages the frame touched, and a savestate stores only those
  pages (delta to the previous frame); a rollback copies them back. This is what makes rollback cheap enough to run
  several times a frame. Same trick as our smart parallel-RDP sync, so the RDRAM mapping and handler we now have are
  a base for it. N64 rollback is still open: no N64 emulator ships it; RSP/RDP state and parallel-RDP's GPU-side
  state (TMEM, pending work) must be saved too. Our roadmap's online multiplayer could start delay-based
  (mupen64plus netplay, same servers as simple64/RMG/gopher64), rollback later.
- **Built-in widescreen hacks** (core/cheats.cpp): a table of ~270 per-game memory patches keyed by game ID + region,
  applied automatically when "Widescreen game hacks" is on, never in RetroAchievements hardcore. Good model for our
  widescreen roadmap item (LaunchBox list -> table keyed by header game code + version).
- **Custom GPU driver as a zip with meta.json** (core/rend/vulkan/adreno.cpp): the format the Turnip releases
  (K11MCH1, Weab-chan, used by Yuzu/Eden/Winlator) already ship in; flycast unpacks it and reads `libraryName`.
  We take a bare .so: accepting these zips lets users drop in any newer Turnip without renaming.
- **Swappy for Vulkan** (shell/.../swappyvk.cpp): only with VK_GOOGLE_display_timing, 60 fps swap interval, off in
  fast-forward. We have our own timed pacing (N64_SWAP_INTERVAL=2); Swappy is the M4 plan, flycast shows it works.
- **Duplicate frames** on 120 Hz+ (DupeFrames): presents each frame refresh/60 times = our N64_SWAP_INTERVAL=1.
- **Auto frame skip** (pvr.AutoSkipFrame): skips rendering a frame when the GPU (or GPU and CPU) is behind. For us:
  skip the parallel-RDP upscale/scanout of a frame on slow Mali games; RDP commands must still run.
- **Audio**: Oboe LowLatency + **Exclusive** sharing mode, buffer trimmed under 40 ms by bursts. We request low
  latency but not exclusive (AAudio MMAP path where the device allows it): a cheap test for lower audio latency.
- Also there: RetroAchievements (rcheevos, hardcore mode), per-game VMU files, texture packs with preloading, a Lua
  scripting API, integer scaling, boxart fetching. Nothing new beyond our roadmap.
- Second pass (Android app side), more that applies to us:
  - **Config changes**: flycast's activity declares uiMode/screenLayout, ours didn't, so dark mode switching (also
    automatic at sunset), docking to a TV or a font/density change recreated the activity and stopped the game.
    Fixed in our manifest (all config changes handled in place). Also `appCategory="game"`, which Android Game Mode,
    the game dashboard and OEM handheld performance profiles look for.
  - **Per-game settings layered over global ones** (config saved per game ID, loaded on boot; hardcore mode then
    overrides some): the shape our M3 profile DB needs (device defaults < game defaults < user per-game < user global).
  - **Auto savestate**: saves on exit and loads on start (off by default), states carry a PNG thumbnail for the
    picker. For us: suspend/resume a game exactly where it was, the handheld "quick resume" feel.
  - **Rumble**: per-controller vibrator with an amplitude from the game's power value, a strength setting for the
    built-in motor, and a separate VibratorThread so the emu thread never calls into the vibrator service.
  - **Hardcore achievements** force the dynarec, stock clock and no widescreen hacks: the rules our
    RetroAchievements item needs (no cheats, no slow-motion, no state loading).
  - Pipeline cache, game scanner with boxart fetch, Discord presence, Lua: nothing new for us (parallel-RDP already
    keeps its own pipeline cache).

## Dolphin Android (GameCube/Wii, GPL-2.0+; checked 2026-11-03, Source/Android + Vulkan backend)
- **Temporary savestate on process death**: `onSaveInstanceState` (not for config changes) saves a state to a temp
  file; when Android killed the app in the background and the user comes back, the game resumes from it. We have
  no savestates yet, so a kill (common when switching to a browser for a guide) loses everything since the last
  in-game save. Needs: retro_serialize/unserialize in the host (on the emu thread), then this, then a quick-resume
  slot. High value for a handheld app.
- **Force max GPU clocks** (Adreno, libadrenotools `adrenotools_set_turbo`, a KGSL power-control property): we
  bundle the same library. Added as an experiment, `--es env N64_GPU_TURBO=1`, to measure on the Duo/Odin 3
  (heavy games, frame-time spikes) and against heat/battery. Measured (README): Odin 3 small gain in one game, Duo no gain; Portal check still open.
- **`preferMinimalPostProcessing`** on the emulation activity: a connected TV switches to its low-latency game mode
  (HDMI ALLM) for docked handhelds. Added to our manifest.
- `isGame` in the manifest (we now use `appCategory="game"`, its API 26+ form).
- Driver install from zip with meta.json (same format as flycast, GpuDriverHelper.kt): same idea as noted there.
- Input goes to native code in full (key + motion events, plus controller gyro/accelerometer via SensorManager on
  API 31+, per-device vibrator managers, hotplug on its own HandlerThread). Gyro aiming is a later idea (GoldenEye
  / Perfect Dark hacks with gyro patches); nothing for latency we don't do already.
- **Shader compilation (read 2026-11-04, Source/Core/VideoCommon VideoConfig/ShaderCache):** four modes: Synchronous (specialized shaders compiled when first needed: stutter), SynchronousUberShaders ("exclusive": one big shader for everything, never compiles per-game shaders), AsynchronousUberShaders ("hybrid": the ubershader draws while specialized shaders compile in the background), AsynchronousSkipRendering ("skip drawing": draws needing an uncompiled shader are left out until it is ready). "Compile shaders before starting" (`bWaitForShadersBeforeStarting`): at boot, compiles every pipeline in the saved UID list and waits, with cores - 2 threads, then drops to the play-time count (cores - 3, between 1 and 4). Ours: parallel-RDP already works like hybrid (a generic variant draws correctly while the specialized one compiles on its one pipeline worker thread); `PARALLEL_RDP_UBERSHADER=1` is exclusive, `PARALLEL_RDP_FORCE_SYNC_SHADER=1` is synchronous; Flip measured 2026-11-04 (README): exclusive -78% geomean, never an option on the Mali; Odin 3, Portal and Duo runs (with synchronous) queued. No skip-drawing mode, and none needed. No compile-before-start: the saved Vulkan pipeline cache only makes each compile faster; parallel-n64 `d8fd0cb` adds the saved list (ported 2026-11-04, 9924334, bench queued). GLideN64's "Cache GPU Shaders" is its compile-before-start, on by default, but no cache file exists on our devices. README items added.
- Swapchain: FIFO with vsync, else IMMEDIATE, then MAILBOX; identity transform preferred. Same as ours (we tried
  pre-rotation: no gain).

## PPSSPP Android (PSP, GPL-2.0+; checked 2026-11-03, android/ + Vulkan common code)
- **ADPF performance hint session** (jni/PerformanceHint.cpp, Android 13+): the frame thread reports its CPU time
  per frame against a half-frame target, so the scheduler stops parking it on slow cores / low clocks between
  bursts. Directly aimed at frame-time spikes like our match-start dip. Ours: app perf_hint.cpp, experiment
  `--es env N64_PERF_HINT=1` (Duo/Odin 3; the Flip is Android 11, no API). Measure, then default if it helps. Measured (README): no gain on the Odin 3 or Duo; leaning to remove.
- **Audio usage GAME** on the Oboe stream (shared mode, low latency). We used the default (media). Experiment
  `N64_AUDIO_USAGE_GAME=1`, measured together with exclusive mode. Result: no difference, removed.
- **External display presentation**: the game can render on a second display (HDMI, or a dual-screen handheld's
  bottom screen like the AYN Thor) through an Android Presentation with its own surface. Roadmap idea: game on the
  external/second screen, menus or touch controls on the built-in one.
- Power-save mode receiver (lowers work when battery saver is on), audio focus handling (pause when another app
  takes audio), `appCategory="game"` + `isGame`, all config changes handled in place (like our new manifest).

## Azahar (3DS, GPL-2.0+; checked 2026-11-03, src/android + renderer_vulkan)
- **Async presentation**: an optional present thread, so the emulation thread never blocks in acquire/present.
  For us pacing is vsync + audio on the emu thread, so a present thread only helps where present blocks long
  (worth a measurement on the Flip, where poll->display is highest). Idea, not started.
- Disk shader cache, `appCategory="game"`, uiMode in configChanges, a "turbo" key = temporary speed limit (our R3
  fast-forward). Nothing else new.

## Lemuroid (libretro frontend for Android, GPL-3; also runs Mupen64Plus-Next; checked 2026-11-03, Kotlin side)
- Closest to what we are (a libretro host). Its **savestate handling** is the practical guide for ours:
  slots with screenshot previews, an **auto-save state written on exit/background with the SRAM and restored on
  the next launch**, and two hard-won notes in its code: Mupen64Plus (and PPSSPP) set up state while rendering the
  first frame, so the auto-save is restored only after the first frame is rendered, and unserialize sometimes
  fails for no reason, so it retries. States from an incompatible core version are detected and reported.
- SavesCoherencyEngine: keeps SRAM and states consistent across devices (it syncs saves to cloud storage). A later
  idea for us (sync saves between the user's handhelds).
- Radial touch controls, TV (leanback) UI: not for us now.

## hakuX (original Xbox for Android, rfandango, GPL-3; checked 2026-11-03 at commit of 2026-10-31)
FEX JIT + native kernel + NV2A push buffer to Vulkan, Adreno/Turnip first. The most handheld-focused of the lot:
- **KGSL draw-context priority for Turnip** (vkdriver.c + tools/native/adrenotools-kgsl-context-priority.patch):
  Turnip leaves its GPU contexts at the kernel default (8, third of four ringbuffers), below SurfaceFlinger's GPU
  composition, which then preempts the emulator's GPU work ~100 times a second whenever the screen is composited
  on the GPU (HW overlays off, or a layer the display hardware can't take). They hook the driver's
  IOCTL_KGSL_DRAWCTXT_CREATE and set priority 7 (the compositor's ringbuffer, lowest there): measured 4-9 GPU hangs
  in 6 minutes -> 0, 2 late frames in 6 minutes. We use the same libadrenotools + Turnip. Worth porting as our own
  hook (their patch is GPL-3, our app GPLv2+, so write it ourselves or decide on the license first); measure late
  frames on the Duo/Odin 3 with "Disable HW overlays" on.
- **SGSR upscaling** (Snapdragon Game Super Resolution, Qualcomm's single-pass spatial upscaler tuned for Adreno)
  and FXAA as presenter options. For us: the final blit of parallel-RDP's image to the screen with SGSR instead of
  bilinear, sharper for free, most useful where upscaling is capped (Duo at 2x) or on the Flip at 1x.
- **Emulated vblank locked to the panel's vsync** (vsync.c: a Choreographer thread feeds each vsync time to the
  emulated GPU's vblank clock), so a frame is ready just before the presenter picks it up and two frames never land
  in one refresh. Our timed pacing (N64_SWAP_INTERVAL=2) is the same idea from the other side.
- **60/120 fps game mods** shipped per title (mods/<title id>.<n>, assembly patches, enabled per game from the
  library). For N64: the community 60 fps codes/patches (e.g. OoT, MK64, Star Fox 64 hacks) as an optional per-game hack (like widescreen,
  not a fix), with the CPU overclock the patch needs.
- Debug properties for A/B runs on the device (debug.hakux.*: driver, TU_DEBUG flags, present options): like our
  `--es env`, but changeable without relaunching.
- Savestates with a versioned schema per device (native/state/*.def + schema/): states survive core updates.

## ARMSX2 (PS2 for Android, PCSX2 fork with its own arm64 JIT, GPL-3; checked 2026-11-03 at commit of 2026-07-11)
- Mostly PCSX2's core; the Android shell is thin. What carries over is PCSX2's **GameIndex database** idea: a
  per-game file of fixes (renderer hacks, clamping, speed hacks, patches) keyed by serial and applied
  automatically, which is what our M3 profile DB is (by ROM hash/game code, per GPU).
- **Per-game overrides as nullable fields** (GameSpecificSettingsManager: null = use the global value), saved by
  game key; the simplest shape for user per-game settings over our defaults.
- Steam Controller over BLE/USB HID, RetroAchievements bridge, Discord, TV/DeX/Chromebook detection for layouts
  (DeviceProfiles is UI mode detection, not performance profiles).
- Nothing new for pacing, audio (cubeb) or thread placement on the Android side.

## Eden (Switch, yuzu lineage, GPL-3; checked 2026-11-03, git.eden-emu.dev, src/android)
- **Foreground service while a game runs** (ForegroundService.kt, type specialUse, with a "tap to resume"
  notification): Android is far less likely to kill the app in the background. Together with Dolphin's temporary
  savestate this covers "switched apps, came back, game gone". Cheap for us: a service + notification permission.
- **ReShade post-processing chain** (native_post_processing.cpp, HAS_RESHADE): a catalog of .fx effects, a chain
  the user orders, per-uniform values and presets, from the Android UI. Another model for our shader roadmap item
  next to RetroArch's slang presets.
- **Per-game Turnip/Freedreno environment** (NativeFreedrenoConfig: TU_DEBUG etc. set before the driver loads,
  saved per config): matches our profile DB plan (per game + GPU), e.g. a TU_DEBUG flag for a game that needs it.
- **Driver fetcher**: resolves driver zips from known GitHub release repos (K11MCH1 etc.) and installs them. For
  us: offer newer Turnip builds in-app instead of only the bundled one (downloads need the user's OK).
- Lossless Scaling frame generation (user supplies Lossless.dll, Vulkan layer), power/battery state updater,
  controller navigation hook for the whole UI (controller-only menus, our goal 3), all config changes in place,
  `appCategory="game"`.

## Low-end performance pass (checked 2026-11-03): PCSX-ReARMed, DaedalusX64, ROCKNIX / AmberELEC
### PCSX-ReARMed (PS1, libretro/pcsx_rearmed, GPL-2; the classic low-end ARM emulator)
- Our new_dynarec comes from the same Ari64 code, but the two have split far apart (PS1 MIPS I vs our 64-bit
  R4300, separate arm64 backends, 10k vs 12k lines), so fixes don't port as patches; ideas do.
- **Compiler thread** (emu_if.c, NDRC_THREAD): when the CPU reaches code that isn't compiled yet, it runs that
  code in the interpreter while a second thread compiles the block, so compiling never stalls a frame. Per-game
  off switch for games that break. Our core compiles on the emu thread: `N64_DRC_STATS=1` (core) now measures it.
  Odin 3: ~100 ms of compiling in the first 5 s after boot, then a few ms per 5 s in-game, worst single block
  1-2 ms, so harmless there; the Flip numbers decide whether to build it. Flip (README): worst single compiles 4-10 ms when entering new areas (visible hitches); build decision still open.
- Compiled-block list saved with savestates (new_dynarec_save_blocks/load_blocks) so a loaded state starts warm.
- SPU and GPU work on their own threads (options); for us the matching idea is HLE audio off the emu thread,
  unclear if N64 timing allows it.
### DaedalusX64 (PSP/Vita/3DS, GPL-2)
- **OS HLE** (OSHLE/patch*.inl): finds 129 libultra functions in RDRAM by signature and runs native versions:
  bcopy/bzero/memcpy, 64-bit divide/multiply helpers (`__ll_div`, `__ull_div` ...; software routines on the
  N64), `guMtx*`, `sinf/cosf/sqrtf`, plus thread, message-queue, interrupt and TLB functions. The pure leaf
  functions (copies, maths, matrix) are the safer subset; they skip CPU time, so games run with less emulated
  CPU load but timing shifts (Count advances less). Only worth it if the Flip turns out CPU-bound in such code.
- Idle-loop skip (branch to self + nop -> jump to the next event): mupen64plus already does this.
- Per-game hacks are for its own HLE renderer; roms.ini save types we already cover.
### ROCKNIX / AmberELEC (handheld Linux distros; mupen64plus standalone on RK3326/RK3566 up to SM8650)
- **GLideN64 "performance" profile** per game: LOD off, legacy blending, inaccurate texture coordinates, no
  color/depth copy to RDRAM, backgrounds mode 0, no RDRAM dithering, no texrect correction. Our fallback
  renderer settings for weak GPUs. Parked: GLideN64 gets no work (Joey 2026-11-04).
- GLideN64 built with `NEON_OPT` and `CRC_ARMV8` (NEON matrix/vertex code, hardware CRC32 for texture hashes).
  Our Android arm64 core built neither (HAVE_NEON was only set for armv7); now built in (core a4c1bce). Never measured: parked with the rest of GLideN64 (Joey 2026-11-04: GLideN64 is kept as fallback, no work on it).
- ThreadedVideo=1 for GLideN64 on every chip; RK3326/RK3566 default to Rice at native resolution; per-game
  choice of big or little CPU cores; per-game option to use simple64's mupen64plus-core fork (run with the cached interpreter); HLE RSP
  everywhere; whole stack built with -Ofast.

## GammaOS Next (TheGammaSqueeze; Android 14 firmware for handhelds; checked 2026-11-04)
Firmware, not an emulator: most of its speed work is at the system level, which an app can't do.
- **What doesn't carry over:** GPU overclocks baked into the kernel (Mali-G52 900 -> 1100 MHz, +23%; Mali-G76
  850 -> 950 MHz), governor/thermal tuning and "Max Performance" modes (all clocks pinned), a Lite build
  without Google services, a newer vendor GPU driver. Our app-side analogs are the ADPF hint and adrenotools GPU
  turbo experiments. DraStic-nano (their DS player, "major performance pass, smoother frame pacing") is closed;
  the public drastic-android-mod repo is only smali QoL patches (ESC = save state and quit, save on every exit
  path, A/B confirm in menus). No N64-specific work found; the emulator configs (mupen64plusae, RetroArch) sit
  in Git LFS archives in GammaOSNextDistribution, not read (needs a download).
- **gammaos-turnip** (Mesa fork, branch adreno-610, 2026-10): Turnip tuned for the Adreno 610 — 128-wide
  fragment waves when safe, sysmem vs GMEM chosen per render pass by measurement (like faux123's Adreno 722
  work), a UBWC fix for 16-bit images. parallel-RDP is almost all compute, so the fragment and render-pass
  changes would only touch our final blit. Idea worth checking: which wave size Turnip picks for parallel-RDP's
  compute shaders on our Adrenos, and whether forcing the other one (VK_EXT_subgroup_size_control) is faster.
- **azahar-nano** (his private 3DS build; no public source, checked 2026-11-04): the only public trace is
  gammaos-turnip, whose build script packages the driver as an adrenotools zip "for azahar-nano / drastic-nano",
  and whose commit messages are tested on 3DS games. What he did there: render to system memory for small
  passes (Metroid 95 -> 97% at 2x), 128-wide fragment waves down to 3 waves in flight (SM3DL scene pass 8.6 ->
  7.7 ms), measured sysmem/GMEM choice per pass with the losing mode re-sampled every 24 decisions (Luigi's
  Mansion 14.8 -> 17.8 fps), a fix for stale baked dynamic state (black screen), and a UBWC clear for new
  16-bit images that stopped whole-system freezes (13 in 35 runs -> 0 in 31). His release notes still say 3DS
  games won't run better overall. For us: all of this is draw/render-pass work; parallel-RDP is compute, so
  only the wave-size idea above applies. The UBWC freeze fix is worth remembering if a Turnip build ever hard
  freezes a 6xx device.
- **Ideas that do carry over:** Quick Resume (save state on any exit path, auto-resume on launch, a preview
  frame held over the handoff so there's no black flash) — already on our "never lose progress" item; a
  per-app performance mode toggle in an overlay menu (ours would be the per-game "performance" option).

## Checklist pass, projects not reviewed before (2026-11-04)
Triage by grep over each tree (present thread, Choreographer/Swappy, ADPF hints, affinity, Adreno/KGSL, timeline semaphores), then reading the hits.
- **WatermelonDS** (melonDS Android fork, 2026-10-16): one ADPF performance-hint session that covers the emulation thread *and* its worker threads (each worker registers itself; NDK API via dlsym, Java fallback), target updated with the fast-forward multiplier. Our `N64_PERF_HINT` experiment covers only the emulation thread; parallel-RDP's command worker and the RSP/audio side should be in the same session. Small change to try with the perf-hint item.
- **DroidDeck** (SteamOS-on-Android launcher, 2026-11-03): (1) pins the Adreno GPU at its top power level without root: a KGSL draw context with `KGSL_CONSTRAINT_PWR_MAX`, kept alive by a NOP submission every 25 ms (`waylandcomp/src/gpu_clock_pin.c`); the same idea as our `N64_GPU_TURBO` (adrenotools), worth testing on the GPU-bound Portal since the uncapped bench is GPU-limited there. (2) Reads the GPU clock without root by having the GPU copy its own cycle and always-on counters (`gpuclock/kgsl_clock.c`): a diagnostic for "is the GPU clocked down" during our runs. (3) Frame generation (Lossless Scaling layer and its own) — already on the README's maybe list.
- **XenDroid / XenDroid+, Vita3K / Vita3K-Plus**: Adreno hits are adrenotools driver loading (we bundle drivers already). Nothing new.
- **DuckStation, PCSX2, Cemu, RPCS3, xemu, shadPS4, melonDS, SeedlessDS, RetroArch**: no present thread, Swappy or ADPF code; affinity hits are thread naming/pinning on desktop. shadPS4 has a present thread (desktop Vulkan) — the same idea as the README's present-thread item (Azahar). This pass covered only the presenting/Android/thread items of the checklist; recompiler design (fastmem, block linking), idle-loop skipping, Vulkan pipeline caching and audio sync are still to read in every project.

## Checklist pass by item, every cloned project (started 2026-11-04)
Each item: grep over all 40 trees in scratchpad/emusrc, then reading the hits. Results so far:
- **Idle-loop skipping.** Ours (new_dynarec `do_cc`): only a branch to itself with a NOP delay slot jumps the cycle count to the next event. gopher64 (relative and absolute jumps to self + NOP), dillonb/n64 (`b -1; nop` and `j self; nop`) and parallel-n64 (same new_dynarec) do exactly the same; Project64's "PermLoop" is a little broader (any delay slot that does not change the branch's compare registers). libultra's idle thread is `b .; nop`, so most games are covered already. Broader idea from melonDS (and Dolphin's PPCAnalyst, which melonDS says it copies; Dolphin's JIT not read yet: sparse clone), melonDS (`ARMJIT.cpp IsIdleLoop`, copied from Dolphin) and PCSX2 (`WaitLoop`): a loop that writes no memory and where no iteration depends on the last (registers read before they are written are never overwritten) is a polling loop, so skip to the next event. That catches games that spin on a memory flag instead of the OS idle thread. Next step: a dev counter in new_dynarec for such loops, to see which of our games spend time in them before building the skip.
- **Pipeline caching.** Ours already loads/saves a Vulkan pipeline cache, and parallel-RDP builds rasterizer variants in the background with an ubershader fallback. Missing: building last session's pipelines ahead at launch (parallel-n64 `d8fd0cb`; Dolphin, DuckStation, Cemu and shadPS4 do the same with their own shader lists). Seen 2026-11-04: the first launch after a shader change ran OoT at 109 fps vs 139 on the Flip. Already on the parallel-n64 port list.
- **Recompiler and memory access.** Ours: new_dynarec reaches RDRAM through `ram_offset` with an inline range check, other addresses through `memory_map`/handlers; no signal-based fastmem. Signal-based fastmem with backpatching (a load faults outside RAM and the handler rewrites it to the slow path, so the fast path has no compare) is in Dolphin, PCSX2/ARMSX2, flycast, melonDS and DuckStation; no N64 emulator has it. parallel-n64 added a fastmem address-space window to libretro-common (`memshm_area_*`, 2026-11) but nothing in its N64 core uses it yet. Value for us unknown: measure how much of the dynarec's time is the inline check before building it. Block linking: ours links direct exits (conditional ones through a plain branch since cc66799); armGBA patches each direct exit on first use (EXIT_LINK). parallel-n64's new_dynarec got about a dozen correctness fixes 2026-10-28/29 (undefined behaviour GCC could see, traps checked inline, BREAK/COP2 through the interpreter, LUI scaling, long straight runs split): check them against our crash list (Last Impact ERET, OoT pause freezes).
- **Audio sync.** Ours already does dynamic rate control (P+I on the buffer fill, +/-1%) with a target latency that grows after underruns (audio_out.cpp). DuckStation and PCSX2 time-stretch audio (SoundTouch) so fast-forward and slowdowns keep sound instead of dropping it: an option for later, not speed.
- **GPU readback.** Ours: NEON masked copy (c1ebc71). parallel-n64 `18c014f`: plain stores for fully written chunks, skip untouched ones; no port needed (c1ebc71 already does the same, checked 2026-11-04).
- **Vulkan submission and sync.** Nothing N64-specific beyond parallel-n64's parallel-RDP changes (port list); the presenting side was covered in the earlier pass.
- **Build flags.** Measured ourselves 2026-11-04: ThinLTO, -mcpu ARMv8.2, -O3 and PGO are all noise on the Flip and Portal, so the core stays -O2. Others use LTO by default (ares, armGBA, Cemu, melonDS, cen64); nothing new.
- **armGBA** (MagnominusGit/armGBA, GBA/GB/GBC libretro core for Cortex-A35/A55 handhelds, MPL-2.0, checked at b75ad7a 2026-11-04). The useful part is its ARM recompiler:
  - **Idle loops that span blocks and calls** (`src/cpu/jit.c` idleProbe): at a cartridge PC where compiled code is about to run, the exact CPU runs up to 128 instructions; if it comes back to the same PC with the same CPU state twice, the two times round took the same cycles, and both made the same reads and writes (reads only from RAM, VRAM, the cartridge or the key register; writes only to RAM), it is an idle loop, and later arrivals at that start in that state skip to the next event. Places that keep failing are probed less often (backoff), and heads that never skip are dropped. The best idle-loop design found; it would catch N64 games that wait in a polling function instead of the OS idle thread. With the Dolphin/melonDS rule, this is the idle-loop item to try.
  - Self-modifying code: words a game keeps rewriting are compiled for up to 4 forms and pick one when run (struct Poly), instead of throwing the block away each time.
  - "Tiny routines": a timing sequence emitted once and called after, because on small-cache cores the code saved is worth more than the call (idea for new_dynarec's cycle-count stubs on the Flip's A55s).
  - Presenting, audio, threads, Vulkan: a plain libretro core, nothing new; LTO on.
- **Redo, 2026-11-04 (after the correction above).** Sparse clones widened to their CPU, memory and audio code: Dolphin (Core/PowerPC, AudioCommon, MemArena), PPSSPP (Core/MIPS, MemMap/MemFault, StereoResampler), ARMSX2 (pcsx2/arm64, vtlb, SPU2), Azahar (core/arm, audio_core), RetroArch (audio/). mupen64plus-ae cloned (33bf702, 2026-08-28). Ryujinx not cloned: its GitHub copies are DMCA-blocked and the continuation is hosted off GitHub; Switch-specific, little N64 overlap; skipped (Joey 2026-11-04). hakuX, Lemuroid, DroidDeck, ROCKNIX, AmberELEC are frontends/distros: the CPU items do not apply. Read, per item:
  - **Idle loops, read in full:** Dolphin `PPCAnalyst::IsBusyWaitLoop` (loop to itself, no other branches, integer/load instructions only, never writes a register it read before writing; JitArm64 then calls `CoreTiming::GlobalIdle`); melonDS `IsIdleLoop` (the same rule, credited to Dolphin); armGBA (two-lap probe, above). **PCSX2 `iR5900.cpp`, the most direct match because the PS2's EE is MIPS like the N64's R4300:** (1) "FF blocks": a block that branches to itself and only does immediate/register arithmetic, loads and mfc reads, never writing a register it already read (registers set from constants or loads excepted), does the same every time round, so it skips to the next event; (2) timeout loops: exactly `addiu rX, rX, -n` + `bne rX, zero, start` + nop, skipped by advancing the cycle count by rX x the loop's cycles (capped at the next event) and lowering rX to match. Both are the `WaitLoop` speedhack, on by default. PPSSPP: none (PSP games idle through HLE kernel waits). mupen64plus-ae: same core as ours (`b .; nop` only).
  - **Fastmem, read:** Dolphin JitArm64 (`JitArm64_BackPatch.cpp`: a load/store faults outside mapped RAM, the handler rewrites that access to the slow path); ARMSX2/PCSX2 vtlb (a 4 GB fastmem area of mapped pages, backpatching on x86 only: ARMSX2's arm64 `vtlb_DynBackpatchLoadStore` is a stub, "Not implemented"); PPSSPP (4 GB mapping, fault handler only stops the core: no backpatch). ARMSX2's EE JIT is PCSX2's x86 recompiler files built for arm64 (CMake lists x86/ix86-32/iR5900*.cpp under arm64), not a separate design.
  - **Audio sync, read:** RetroArch (`audio_driver.c` rate control delta, scaled by the resampling ratio), PPSSPP (`StereoResampler`, ratio from a 40 ms target buffer), Dolphin (granule mixer, fills gaps), Azahar and DuckStation (time stretch), mupen64plus-ae (Oboe + SoundTouch: tempo nudged by the queue size, pitch kept; also a speed factor). Ours is rate control like RetroArch/PPSSPP. Time stretch only matters below full speed or in fast-forward.
  - **Threads, read:** mupen64plus-ae runs the core thread at `THREAD_PRIORITY_URGENT_AUDIO` (option "high priority thread", on when there is more than one core). Ours pins affinity but never raises priority: new item to measure.
  - **Presenting, mupen64plus-ae:** core renders to a SurfaceTexture, a second GL pass draws it to the window; vsync off for latency (an `eglPresentationTimeANDROID` in the past where vsync cannot be turned off); `Surface.setFrameRate(59.94)`; Choreographer only for shaders that need vsync. Its parallel-RDP is from 2021-12 (b355d60), older than ours.
  - Pipeline caching, readback, RSP JIT, build flags: as above; nothing further found in the widened trees.

## Video pass, every cloned project (started 2026-11-04, after Joey asked why video code was not part of each review)
The first passes read each project's presenting/Android side, and several clones were sparse (Android and Vulkan-backend folders only), so shared video code was never read. Fetched 2026-11-04: Dolphin VideoCommon, PPSSPP GPU/ and Common/GPU/, ARMSX2 GS/ (+ MTGS, the x86 EE recompiler files), Azahar video_core, RetroArch video_driver + shader drivers, Eden (the clone had never been checked out: now src/android, video_core, core/arm, audio_core). Items, each read in every project with its own renderer: V1 shader compilation; V2 GPU threading and submission; V3 CPU reads of GPU results; V4 uploads and memory; V5 tile-GPU (mobile) specifics.

- **V1 Shader compilation (read).**
  - Modes found: synchronous (compile when first needed, stutter: PCSX2, Cemu without async, RPCS3 "recompiler", Dolphin "specialized"); skip the draw until ready (Dolphin "skip drawing", Cemu `async_compile`, Azahar `async_shader_compilation`, Eden/yuzu `use_asynchronous_shaders`, Vita3K `async_pipeline_compilation`, RPCS3 `async_recompiler`); a general shader while the specialized one compiles (Dolphin "hybrid ubershaders", RPCS3 `async_with_interpreter`, parallel-RDP's default); general shader only (Dolphin "exclusive ubershaders", RPCS3 `interpreter_only`, parallel-RDP `PARALLEL_RDP_UBERSHADER=1`).
  - Skip-drawing rules: Cemu never skips draws of 6 indices or fewer (full-screen quads that build textures the game keeps) or without a depth buffer, plus a per-game blacklist size (Splatoon 1600x1600); Azahar waits for draws of 6 vertices or fewer. Not needed for us: parallel-RDP's fallback draws correctly.
  - Compile before play: Dolphin (saved pipeline list, cores - 2 threads at boot), Cemu (its pipeline cache file, one thread per physical core up to 8), Vita3K (precompile with progress), Eden/yuzu and Azahar (`LoadDiskResources` / `LoadDiskCache` with worker threads), PPSSPP (its own list of shader IDs and pipeline keys, rebuilt at load; it avoids the raw Vulkan pipeline cache blob as "badly tested", pointing at zeux.io on validating it), DuckStation (compiles its whole fixed pipeline set at boot with a progress bar), GLideN64 (stored program binaries, loaded at start). parallel-RDP has none: the saved Vulkan pipeline cache only makes each compile faster; parallel-n64 `d8fd0cb` adds a saved list (port list).
  - Compile threads: Dolphin 1-4 in play, Cemu up to 8, PPSSPP a thread pool with pipelines grouped by shader pair (same pair on one thread helps NVIDIA); PPSSPP also queues a pipeline the moment the CPU records the draw, so compiling overlaps the rest of the frame. parallel-RDP: one pipeline worker thread.
  - N64: ares and gopher64 run parallel-RDP with its default (hybrid), like ours. GLideN64 (ours, mupen64plus-ae, RMG, Project64) saves its shader store only in `CombinerInfo::destroy()`, i.e. at a clean shutdown: the likely reason no store exists on our devices (tests force-stop the app; check what a normal exit does).
  - For us: (a) measure exclusive vs default parallel-RDP; (b) port the pre-build (d8fd0cb) and give it more threads at start; (c) GLideN64: save the store when the game is paused/backgrounded, not only at shutdown; (d) check our raw pipeline-cache save validates the header (PPSSPP's warning).
- **V2 GPU threading and submission (read).** Every big emulator splits emulation from GPU work the way our parallel-RDP ring does: DuckStation's video thread (on by default, the core thread may run up to 2 frames ahead, `MaxQueuedFrames`, with an optional spin when it must sync), PCSX2's MTGS, Dolphin's dual-core FIFO, PPSSPP's render thread (`MAX_INFLIGHT_FRAMES`), Eden/yuzu's scheduler worker that records and submits command chunks. Two extras: Dolphin's "backend multithreading" moves `vkQueueSubmit` and present to their own submission thread (like the README's present-thread item); DuckStation lets the CPU run ahead of the GPU by whole frames. Ours drains the ring at every scanout; whether that waits for the GPU or only for the worker, and what one frame of run-ahead would gain, is a profiling question: added to the README.
- **V3 CPU reads of GPU results (read).** The N64 problem behind OoT's wait every frame (the game reads its own depth buffer). Found:
  - **Dolphin, early command-buffer kicks** (`VertexManagerBase::OnEndFrame`): it records at which draw counts the CPU read the framebuffer this frame; next frame it submits the GPU work early, halfway between those points or every 250 draws (`iCommandBufferExecuteInterval`), so the GPU has finished by the time the CPU reads. Frames without reads keep one batch for the most CPU/GPU overlap. A more precise form of our adaptive waking (which only wakes eagerly after a wait): for parallel-RDP, remember the RDP command counts at which smart sync waited last frame and flush/kick the worker before them. Top idea from this item.
  - **PCSX2, spin during readbacks**: "Spin GPU" runs a small busy compute job on a second queue for 30 frames after a readback so the GPU does not clock down while it waits for the CPU, with calibrated timestamps to size the spin; "Spin CPU" busy-waits on the fence instead of sleeping. Our waits sleep on a condition variable, and phone GPUs drop clocks fast: try both (GPU spin overlaps our `N64_GPU_TURBO` idea, which did nothing on the Duo, but the CPU-side spin is untested).
  - Dolphin also: skip CPU framebuffer reads (`bEFBAccessEnable` off), defer copies to RAM (`bDeferEFBCopies`), cache reads in tiles (`iEFBAccessTileSize`). Eden: "reactive flushing" (write GPU results back only when the CPU reads them, i.e. our smart sync). DuckStation: a software renderer copy runs alongside the GPU so CPU reads come from it (per-game; too heavy for an N64 RDP).
- **V4 Uploads and memory (read).** Eden writes straight into host-visible GPU buffers on integrated GPUs and skips the staging copy (vk_buffer_cache.cpp), and sizes its memory budget for shared RAM; parallel-n64 `0818cbb` does the same for parallel-RDP's primitive streams and `ea0dd4e` uploads only changed RDRAM pages (both ported 2026-11-04, bench queued). Ours: the core logs "RDRAM in shared memory"; whether parallel-RDP imports it as GPU memory (VK_EXT_external_memory_host) on each of our drivers, which would make the RDRAM upload moot there, was checked 2026-11-04: no device imports it (all four log "not supported"), so RDRAM is copied on every device.
- **V5 Tile GPUs (read).** PPSSPP and flycast use transient, lazily allocated attachments; most use DONT_CARE load/store ops. parallel-RDP renders with compute shaders, so this only touches our VI/scale/present passes; nothing measurable expected. No action.
- **V6 Speed hacks and tricks per emulator (done; Joey asked 2026-11-04).** Read so far:
  - **PCSX2** (`SpeedhackOptions`): `IntcStat`, on by default, "huge speedup for some games, with almost no compatibility side effects": on a read of the interrupt status register, if more than 8 cycles have passed since the last event, jump the cycle count to the next event (HwRead.cpp `IntCHackCheck`). N64 version: the same on reads of MI_INTR, SP_STATUS, DPC_STATUS (games that poll for the RSP/RDP to finish) in our memory handlers; cheap to try as a dev switch with the idle-loop counter. (Update 2026-11-04, README: new_dynarec's read handlers can't change the cycle count, so this has to be done at compile time together with idle-loop skipping.) `WaitLoop` (the loop rules in the CPU pass), `vuThread` (VU1 on its own thread: the N64 analogue is the RSP, see Project64 below), `vu1Instant` (ours already runs RSP tasks at once), `EECycleRate`/`EECycleSkip` (CPU underclock: our CountPerOp).
  - **Project64**: `Game_RspMultiThreaded` runs the RSP on its own thread (RSPPlugin.cpp `RspThread`), free-running with no sync to the CPU beyond the SP semaphore; its game database enables it for no game, so it is a stub, not a proven trick. Also per game: `FastSP`, `DelaySI`/`DelayDP`, `32Bit` (compile 32-bit-only code paths), `RegCache`, `FuncLookupMode`, self-modifying-code methods (`SMM_*`), `CounterFactor`, `SyncViaAudio`/`FixedAudio`.
  - **PPSSPP**: `iDepthRasterMode` (on by default on Android, low quality): for games flagged `SoftwareRasterDepth` in its compat list, a NEON software rasterizer keeps a CPU copy of the depth buffer, so the game's depth reads (lens-flare checks) are answered without a GPU readback. The N64 version would answer OoT's every-frame depth read (15.9% of the Duo emulation thread waiting) without waiting for parallel-RDP: a depth-only rasterizer of the RDP triangle commands, per game. Large; listed in the README. Also `iSkipGPUReadbackMode` (skip, or copy to a texture instead of RAM), `iInflightFrames`, `bAutoFrameSkip`/`iFrameSkip`, `bSkipBufferEffects`, `bRenderDuplicateFrames` off (we do the same: present only new frames).
  - **Dolphin** (`GFX_HACK_*`): skip duplicate XFBs (we have it), immediate XFB (show a frame as soon as it is copied: like our scanout change), VI skip (drop presentation when behind full speed), EFB access/copy hacks (V3), fast texture sampling, no mipmapping, copy-to-VRAM off.
  - **DuckStation**: CPU overclock, CD read/seek speedup, video thread and queued frames (V2), skip presenting duplicate frames, optimal frame pacing and pre-frame sleep (sleep before the frame so input is read as late as possible: a latency trick worth a look), software renderer for readbacks (V3).
  - **RPCS3**: "Relaxed ZCULL sync" hands the game GPU occlusion results late instead of waiting for them. N64 version, much smaller than a depth rasterizer: per game, let CPU reads of the depth buffer (OoT's lens-flare check) skip the smart-sync wait and take the last finished frame's depth (one frame late, invisible for a flare). Also "Allow host GPU labels" (GPU writes sync labels straight into host memory), SPU loop detection, multithreaded RSX, write/read color/depth buffer switches.
  - **flycast**: auto frame skip: when the previous frame's render has not finished and the CPU ran below 85% speed, drop this frame's render instead of waiting (ta_ctx.cpp). For parallel-RDP the RDP work cannot be dropped (the game reads its results); only VI scanout/present could be. Also delayed frame swapping, threaded rendering.
  - **Eden/yuzu**: its own frame generation (`frame_gen`, optical flow, target rate; the README's frame-generation maybe item), `gpu_fence_behavior`, `skip_cpu_inner_invalidation`, async presentation, custom CPU ticks (underclock), "unsafe" CPU float options.
  - **GLideN64** (our fallback renderer; the libretro core's options): "Threaded Renderer" (off by default, our app never sets it: GL calls on their own thread, "improves performance but increases input lag"), copy color to RDRAM (default Async = double buffer), copy depth to RDRAM (default Software). Queued last with the rest of the GLideN64 work.
  - Cemu (GPU buffer cache accuracy, async compile), Azahar (async shaders, CPU clock percentage), Vita3K (async pipelines, precompile): nothing beyond the items above.
- **V7-V10 (added at Joey's question "anything else to look for in video").**
  - V7 frame skipping when behind: Dolphin VI skip, PPSSPP auto frameskip, flycast auto skip (above). For us only the VI scanout/present can be skipped, not RDP work; small gain, Flip only. Low priority.
  - V8 cheaper effects: PPSSPP lower resolution for effects and skip buffer effects; GLideN64 async copies (above). parallel-RDP has no separate effect passes beyond VI filters (the "Sharp" option already turns those off). Nothing new.
  - V9 driver notes: Dolphin `DriverDetails`: on Mali and Qualcomm Vulkan drivers, cached host memory is much slower than coherent memory for readbacks (time goes to kernel cache invalidation, `__inval_cache_range`), so Dolphin picks coherent there (`BUG_SLOW_CACHED_READBACK_MEMORY`); on Adreno, `vkCmdCopyImageToBuffer` from optimally tiled images allocates a staging image (`BUG_SLOW_OPTIMAL_IMAGE_TO_BUFFER_COPY`). parallel-RDP: when VK_EXT_external_memory_host is missing it keeps a GPU copy of RDRAM in `CachedCoherentHostPreferCached` memory on integrated GPUs and copies with a write mask; an older Duo run logged exactly that ("VK_EXT_external_memory_host not supported ... falling back to a slower path"). So on the Duo (and, checked 2026-11-04, on all four devices) RDRAM goes through upload and readback copies every sync, which makes the parallel-n64 page-diff upload and readback ports matter, and makes cached vs coherent memory worth a measurement. Per-GPU switches `PARALLEL_RDP_SUBGROUP` / `PARALLEL_RDP_SMALL_TYPES`: README item.
  - V10 async compute and half precision: parallel-RDP already uses an async compute queue for its work where the driver offers one, and its 8/16-bit arithmetic is the SMALL_TYPES switch (V9). Nothing else found.

## Targeted pass (2026-11-05, Joey: "now that we have something to look for")
Four questions from the measured barriers, read in the clones already in scratchpad/emusrc (no downloads).
1. Mali compute (the Flip's GPU is the limit). parallel-RDP upstream (Themaister) last changed 2024-11-09 (1cecd042); its changes since our 2022 copy are a Vulkan API refresh (sync2 barriers, 1.3 structs) and a noise fix, no shader speedups, but the update measured +7.9% on the Flip anyway: merged into gpu2 2026-11-05 (core a7a490f). PPSSPP's Mali code is render-pass bug workarounds, not compute. What the Mali itself says (malictr's new columns, one sample at 768 MHz, 96.5% busy): arithmetic 40%, load/store 28%, texture 6%, 37% of warps use every register (half occupancy), 28% diverged instructions. So no unit is saturated: latency, occupancy and divergence, not raw throughput. To look for next, per game (flip-neon2 collects it): register pressure and divergence in parallel-RDP's per-tile shaders; the VI filters (AA, divot, dither, gamma dither: all on, never measured on the Mali); running spirv-opt -O over parallel-RDP's SPIR-V (the Mali compiler may allocate fewer registers).
2. Readback waits: not a measured barrier. Relaxed depth removed OoT's wait entirely and gained nothing, as did early kicks and spins (Dolphin, PCSX2, RPCS3 ideas already tried). Nothing further to read here until a measurement points at it.
3. Adreno (the Duo's two speed levels). kgsl counters can't be read from outside the app on any of our Adrenos (`/sys/class/kgsl/kgsl-3d0/perfcounter` is 0 on Duo, Portal, Odin 3) and the Duo's gpubusy is frozen. DroidDeck's method (gpuclock/kgsl_clock.c): reserve an RBBM countable (any app may) and have the GPU copy it plus CP_ALWAYS_ON_COUNTER into memory with CP_REG_TO_MEM from a command buffer of its own; counters clear when another process uses the GPU, so samples that straddle a switch are dropped. Our GpuClockPin already owns a KGSL context in the app, so it can do this per frame window: GPU clock and (with a busy countable) GPU busy % for the Duo. The Duo also has a GPU bus-bandwidth governor (devfreq kgsl-busmon, gpubw_mon) whose vote is readable: logging it beside the runs (tools/duogpu.sh) to see whether the slow level is a low memory-bandwidth vote.
4. Heat (the Odin 3). No root, so frontends use Android's own hooks: GameManager.setGameState(gameplay, interruptible) on Android 13+ (ARMSX2, DroidDeck: tells OEM game modes a game is running), and a governor on the thermal status (DroidDeck's LSFG governor polls AThermal_getCurrentThermalStatus every 2 s and backs off its extra work). For us: step fast-forward speed or upscaling down before the device throttles.
