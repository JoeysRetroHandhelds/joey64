# What other N64 emulators can teach us (review 2026-11-03)

## Ranked ideas
1. **One frame less display lag** (from ares). Our core captures the frame at the VI interrupt, before the game points
   the display at the frame it just finished, so we likely show every frame ~16 ms late. Move the capture after the
   game's interrupt handler. Measurable with our poll->display stat. Small change, big win.
2. **Smart parallel-RDP sync** (from gopher64). Run async, remember where the GPU drew, wait only when the game reads
   there. Async speed with sync correctness: fixes the Flip's sync cost and makes Adreno safe on async. Needs page
   protection for our dynarec (the core's built-in framebuffer hooks are off under the dynarec). Bigger project.
3. **Per-game "smoother" mode** (Project64 counter factor 1, gopher64 CPU overclock). Mario Kart 64, Turok, Jet Force
   Gemini, SW Racer, Rogue Squadron etc. lag on real hardware; doubling CPU speed smooths them. Only on strong devices.
4. **Unknown hacks inherit the base game's settings** (from parallel-n64): look up by cartridge ID when the hash is
   unknown, so hacks get the right save type and timing.
5. **Port missing upstream mupen64plus fixes**: Game Boy save truncation (with Transfer Pak work), Oct 2026 bounds
   checks (controller channels, savestates, 64DD sectors, netplay packets).
6. **Online multiplayer**: reuse the mupen64plus netplay protocol (our core has it) and RMG's netplay server; gopher64
   has P2P netplay as a second reference.
7. **Accessories**: gopher64 emulates the Voice Recognition Unit and Transfer Pak with Game Boy carts (GPL-3: ideas only).
8. **Accuracy fixes in ares to check against ours**: timer interrupt when COMPARE=0, COUNT reads, banked SRAM (Dezaemon 3D
   saves), CACHE opcode, LL/SC. ares is ISC licensed, so code can be reused.
9. **Test sets**: Dillonb/n64-tests, N64brew jam homebrew (libdragon), for accuracy and the homebrew roadmap item.

Licenses: Project64 GPL-2 and ares ISC can be reused; gopher64 and RMG are GPL-3 (reimplement ideas, don't copy).

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

## RMG (mupen64plus front-end, GPL-3): netplay via rmg-netplay-server (mupen64plus netplay protocol, which our core
  has code for). Online multiplayer could reuse the protocol/server and play against RMG desktop users.
## Dillonb/n64: LLE emulator, has a test-ROM suite (dillonb/n64-tests) + docs (n64.readthedocs.io) -> accuracy test set.
## cen64: RTL-level accuracy reference, too slow for handhelds. DaedalusX64: HLE speed for PSP/3DS/Vita, low value now.

## mupen64plus-core upstream (checked which recent fixes our core has, scratchpad/upcheck.py)
- Have: mempak players 2-4, transfer pak fix, large page sizes for dynarec cache (16 KB-page Android devices), TLB
  crash/halt fixes, dma_pi_write underflow, SSSV fixes, Pokemon Stadium SiDmaDuration, 64DD entries, Wipeout 64.
- Missing: 902bc60f "Don't truncate Game Boy RAM files on their first write" (2026-10, Transfer Pak saves; data-loss
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
