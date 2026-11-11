# Audit 3, part 6: Joey64's own changes to the core (joey64-core ra-known2 @ 1281dd8 vs 15bada85)

Read only: everything was read with `git show` / `git diff` against `ra-known2`. Nothing was checked out or changed.
126 commits listed. Bulk library updates (GLideN64 sync, parallel-RDP/Granite, vulkan-headers, volk, freetype,
translations, angrylion removal) were skipped except where Joey64 edited them. The run-ahead findings matter only
with the dev switch `N64_RUNAHEAD`, which the app does not expose in its UI yet (CLAUDE.md). They are ranked with that in mind.

Summary: 1 High, 2 Medium, 7 Low.

---

## High

### H1. Normal save states (quit save, slots, recovery) are refused whenever an RSP task is still running at the frame's end
- **Where:** `libretro/libretro.c` `retro_serialize`, the `state_direct(0)` block (about lines 2290-2320 in ra-known2).
- **What happens:** `N64_STATE_DIRECT` is on by default (`on = !e || !e[0] ? 3 ...`). It applies to every
  `retro_serialize` made while the game thread waits in `new_vi` (`g_at_frame_edge`). That is always the case for the
  host's between-frame state requests (`serveStateRequest`), including while the game is paused. In that block:
  ```c
  if (g_dev.r4300.cp0.interrupt_unsafe_state && !(same && g_dev.r4300.cp0.interrupt_unsafe_state == INTR_UNSAFE_RSP))
  { ... return false; }
  ```
  `same` (`ra_same_instance()`) is false for a NORMAL-context save, so any unsafe state, an RSP task still running
  included, makes the save return false. Before, the job path waited for a safe point. The core's own comment puts
  this at about 5% of frame ends in SM64 (under parallel-RSP, the default with parallel-RDP). Other RSPs were not measured.
- **Who reaches it:** `GameStates.save` / `saveQuit` / `saveRecovery` → `NativeBridge.saveState` → `core.serialize`
  on the emulation thread. A false return gives `null`, and `save()` returns false with no retry
  (GameStates.kt:91, 229). The quit save (`saveQuit`) then keeps the older auto save, so Continue resumes from an
  older point and the progress since is lost. Undo load state also loses its snapshot (line 186). A
  game paused on such a frame fails every retry, because the state does not change while paused.
- **Fix:** take the direct path only when it can succeed. For a NORMAL context with `interrupt_unsafe_state` set, fall
  through to the old job path (or loop `co_switch(game_thread)` until a safe edge) instead of `return false`. Keep the
  refusal for run-ahead only.

## Medium

### M1. Compile thread: the worker can read smart-sync-protected RDRAM, so the SIGSEGV handler runs on the worker thread
- **Where:** `new_dynarec.c` `drc_front_done` (memcmp of `g_dev.rdram.dram + (start & 0x7FFFFF)` against `drc_snap`,
  on the worker), plus `rdp.cpp` `Smart::handler`.
- **What happens:** with `N64_DRC_THREAD=1` (MainActivity's default on Mali) and smart sync on (Mali: the
  `kSyncRdpGames` games such as Snap; Adreno keeps the thread off), a queued block whose page is also locked
  (PROT_NONE after a SyncFull) faults inside the worker's memcmp. The handler then calls
  `frontend->wait_for_timeline()` and `unlock_all()` from the compile worker, while the emulation thread blocks in
  `drc_finish_locked`. That wait is not async-signal-safe, and CommandProcessor is only ever driven from the emulation
  thread. On Mali the RDP commands run single-threaded on the emulation thread, which is blocked at that moment. It
  should resolve, since the GPU work is already submitted, but this was not built for that thread. `drc_service`'s
  snapshot `memcpy` also reads the protected mapping, but on the emulation thread, where it is a normal wait.
- **Fix:** read the live code in `drc_front_done` (and the snapshot in `drc_service`) through `g_rdram_alias` when it
  exists. That mapping is never protected. It is only a compare of code bytes, so no wait is needed.

### M2. Run-ahead on async parallel-RDP (Mali default) puts back RDRAM while the GPU copy-back may still write it (dev switch)
- **Where:** `savestates.c` load: `else if (savestates_ra_context && savestates_rdp_settle) savestates_rdp_settle();`
  together with `libretro.c` `ra_cow_allowed()` and `rdp.cpp` `smart_settle()`.
- **What happens:** `savestates_rdp_settle` is set only when `parallel_smart_on()` is true (smart sync, which needs the
  synchronous option). On async parallel-RDP (Mali's default) nothing waits for the GPU at a run-ahead take or put-back.
  Once set it also stays set, and `smart_settle` returns at once when `!Smart::enabled`. The extra frames' draws are
  copied into RDRAM by parallel-RDP's worker through `g_rdram_alias` whenever their fences signal. That can happen after
  `ss_copy_rdram` has put the real frame's bytes back, which leaves extra-frame pixels and depth in the real
  timeline's RDRAM. That is the same drift the code's own comment mentions for OoT. Dev only (`N64_RUNAHEAD`).
- **Fix:** with run-ahead on async parallel-RDP, drain the GPU before the take and the put-back
  (`frontend->wait_for_timeline(frontend->signal_timeline())`), or allow run-ahead only with smart sync.

## Low

### L1. A copy-on-write run-ahead state loaded by anything else is loaded with stale RDRAM (dev switch)
`savestates.c`: when `ss_cow.armed` and the buffer is the COW one but the context is not 2, the code logs
"copy-on-write state loaded outside run-ahead" and still runs `ss_copy_rdram` from a buffer whose RDRAM part holds
only the copied pages (the rest is whatever the buffer held before). The host loads it only through the run-ahead
put-back today (libretro_host.cpp:1519-1522), so nobody hits it now. A future netplay rollback or rewind that reuses
`raState` would. Fix: refuse the load (`return 0`) instead of logging.

### L2. `ra_side` / `ra_hle` are applied to whichever same-instance state is loaded next, not the one they were saved with
`libretro.c` `retro_unserialize`: `ra_side.valid` is set by the last RA save and put back on any RA-context load. The
host takes and loads one buffer per frame, so this is right today. A second buffer (rollback netplay) would pair
the wrong RSP registers with a state. Fix: store a generation number in the state's spare bytes, or inside
`ra_side`, and check that it matches.

### L3. `retro_serialize` doesn't check `size`
The direct path and the job path write `16788288 + 1024 + 4 + 4096` bytes straight into `data`
(`save->data = curr = save->mempointer`), whatever `size` is. The host always passes `serialize_size()`, so this is
only a guard. Fix: `if (size < retro_serialize_size()) return false;`.

### L4. `setjmp` used in a form the C standard does not allow
`new_dynarec.c` `drc_worker`: `if (async && setjmp(drc_bail_jb))`. C11 7.13.1.1 allows `setjmp` only as a whole
controlling expression, or compared with a constant, or under `!`. Clang accepts it, and `out0`/`async`/`r` are not
changed between setjmp and longjmp, so it works in practice. Fix: `if (async) { if (setjmp(drc_bail_jb)) {...} else {...} }`.
A bailed compile also leaves other compiler globals mid-pass (`stubcount`, literal pool). They appear to be reset at
the start of each compile, and `out` is restored.

### L5. parallel-RDP `init()` doesn't wait for a running pipeline-cache saver thread
`rdp.cpp` `init()` does `device.reset(new Device)` without `wait_pipeline_cache_saver()`. The saver thread holds the
old `Device*`. Today `deinit()` (which joins it) always runs first: `context_destroy` → `parallel_deinit`. A second
`parallelRomOpen` → `parallel_init` without a destroy would free the Device under the saver thread. Fix: call
`wait_pipeline_cache_saver()` at the top of `init()`.

### L6. Signal handlers do non-async-signal-safe work by design
`Smart::handler` calls `CommandProcessor::wait_for_timeline` (mutex/condvar) and `sem_post`. The COW handler calls
`memcpy` and `mprotect`. Safe only because the faulting thread never holds a lock those calls need. Every RDP-side
read goes through `g_rdram_alias` (parallel-RDP's RDRAM pointer, TexKeys `mem`, Dup's hash), which keeps it true today.
Keep it a rule for new code: nothing inside parallel-RDP or the compile worker may touch the primary RDRAM
mapping (see M1).

### L7. `ss_cow_copy` marks the page copied before it opens it
`copied[p] = 1` comes before `mprotect(..., RW)`. A second thread writing the same page in that window would see
`copied` and be passed to Smart's handler, then to the default handler, and crash. Only the emulation thread writes
the primary RDRAM mapping today: parallel-RDP's copy-back goes through the alias, RSP and DMA run on the emulation
thread. So this can't happen now. Fix: `mprotect` first, then set `copied[p]` (the order Smart's relock helper already uses).

---

## Checked and found fine
- **retro_unserialize size check** (2c9fc76/05562df): magic plus the version-specific body (44 + 16788244 + 1024
  [+4 from 1.1] [+4096 from 1.2]) is checked before the in-place read. The loader then reads only fixed sizes from that
  body, and the event queue must have its terminator (port of upstream 4f71d580). PIF channel offsets are checked
  `< PIF_RAM_SIZE` and passed with the remaining length.
- **Wrong magic unlocks `savestates_lock`** (0044899). retro_unserialize now returns the load's own result.
- **PJ64 state RDRAM size** is limited to 4/8 MB (port).
- **TLB-table skip** (generation mark in the old flashram bytes): `tlb_new_gen` uses a monotonic counter, and a load
  rolls `g_tlb_lut_gen` back only to a generation whose tables it has just copied, so no generation ever names two
  different tables. The skip is limited to `savestates_ra_context`, and file states always take the full copy and full flush.
- **Per-page TLB remap on RA loads** drains the compile thread first (`new_dynarec_tlb_drain`), then drops and maps
  pages as TLBWI does.
- **FPCR rounding after a load** (`new_dynarec_load_rounding`) for both the m64p and PJ64 loaders.
- **COW arm/disarm ordering with smart sync**: settle before arming, settle before disarming, and `ra_cow_drawn` runs
  before `set_pages` marks a page dirty, so every page smart sync can protect while armed is already copied. Smart's
  relock helper marks a page before protecting it (6777ab9).
- **g_rdram_alias** (memfd): both mappings checked, fallback to the heap on failure, both unmapped in `release_mem_base`.
- **Command ring lazy/adaptive waking**: wait flags plus seq_cst counters (Dekker-style) for reader, writer and drain.
  No lost wakeup, and the lazy wait is bounded by the 0.5 ms timed wait. Teardown kicks the worker.
- **Compile thread handshake**: JOB → FRONT_DONE → GRANT → IDLE under `drc_mx`, the code bytes are compared again
  before pass 9, and `using_tlb` is checked. `invalidate_all_pages` → `drc_drain` is reached only from the emulation
  thread outside `drc_compile_sync`, so there is no self-deadlock. Snapshot length is clamped to RDRAM's end.
- **Jump cache**: every hash-table loss or replace bumps `jc_gen`. Cleared on init and on every state load.
- **Cart-ROM code** (19eff8c): `cart_rom_source` checks the bounds against `rom_size`. `verify_dirty` returns "changed"
  instead of the old `assert(0)` NULL memcmp.
- **PI DMA alignment** (`cart_addr_mask`): odd cart addresses stay inside `cart_rom_dma_write`'s bounds check
  (`cart_addr + length < rom_size`, plus the per-byte `dram_size` check).
- **Save type by game ID**: a fixed 3-byte compare on the header. 4K EEPROM when the ID isn't listed.
- **64DD**: `get_disk_data` checks offset+length against the disk size for all three formats, and the sector length is
  computed before the seek. A D64 LBA above MAX_LBA is refused. The D64 expanded size is now stored. The disk save path
  is length-checked against PATH_MAX. The IPL is picked by region with a length check. A disk-only start
  (`num_info == 1`) no longer reads `info[1]`. A failed start asks the frontend to shut down instead of returning from the coroutine.
- **VI**: the VI event is removed by type (7a8c4ba). The scanout event is in the handler table (count 17) and is saved
  and loaded with the event queue. MTC0 Count/Status and the MI mask store no longer end a frame mid-instruction.
- **State return** (`state_job_done` → `retro_return`): the job loop in retro_serialize/unserialize sees completion,
  and the next retro_run resumes inside `gen_interrupt`. A no-op with GLideN64's threaded renderer.
- **Netplay (this branch)**: only the hardened UDP input parsing is here: packet length checked, player < 4, event
  count against the length (9 bytes each), a pending-event cap, plugin values checked, and raw-input commands matched by
  tx/rx length. The netplay branch itself is not in ra-known2.
- **Repeat-frame skip**: the hash range is clamped to `frontend_rdram_size`, it reads the alias, and `frame_valid`
  stays set until the frontend reads it.
- **Pipeline cache and list**: `.tmp` + rename on save. The list is checked by magic, version, record size, count ≤ 8192
  and checksum. The cache blob is validated by Granite's UUID/hash. The saver thread is joined before the device goes.
- **HLE audio under parallel-RSP**: `hleInitiateRSP(..., nullptr)`, CycleCount unused. `ra_side.rsp` (2048) and
  `ra_hle.buf` (0x3000) are size-checked against the plugins' sizes before use.
- **Transfer Pak** (ports): GB ROM/RAM bank mirroring, RTC start time, trailing data kept in the .sav.
- **Texture packs / texture keys**: dev env only, no UI. The RDRAM reads in `hiresKey`/LoadBlock/LoadTLut check
  `memSize`. The 2D slots `place2d(w + 2, h + 2)` cover `write2d`'s gutter (rows oy-1..oy+h, cols ox-1..ox+w). Eviction
  races are guarded by the documented seq_cst Dekker handoff.
- **Vulkan version cap** (2ef8b55): Granite is never told a version higher than the loader's.
