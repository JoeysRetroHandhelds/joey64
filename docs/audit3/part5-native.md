# Audit 3, part 5: native host (app/src/main/cpp)

Scope: our files in `C:\GithubProjects\joey64-launcher\app\src\main\cpp` (launcher/phase0, df01a53): libretro_host.cpp,
vk_context.*, vk_look.*, vk_post.*, egl_context.*, audio_out.*, rewind.*, rewind_test.*, achievements.cpp,
netplay_server.cpp, upnp.cpp, libra.*, gpu_counters.cpp, gpu_clock_pin.cpp, thermal.cpp, perf_hint.cpp, joeylog.cpp,
display_opts.h, utf16.h, third_party/libadrenotools/src/hook/kgsl_priority_hook.c. Read only, nothing changed.

Context that shapes the findings: the room server's port is opened on the router by NAT-PMP/UPnP (upnp.cpp), so
anything a netplay packet can do, a host on the internet can do. `NativeBridge.stop()` runs on the main thread
(MainActivity.kt:3004 onDestroy, :850, :2348). A game screen whose loadGame failed leaves `gameLoaded` false
(MainActivity.kt:1327-1328), so the next game starts in the same process; after a successful game the next one gets a
fresh process (switchGame/RestartActivity).

Totals: 2 High, 6 Medium, 7 Low.

---

## High

### H1. The room thread can block forever in send(), freezing every player and hanging the host's quit
- Where: netplay_server.cpp:281-289 (`sendAll`), callers :311, :325, :330, :336, :360; accepted sockets are blocking
  (:535-546, no O_NONBLOCK, no SO_SNDTIMEO); stop() joins the thread (:650); libretro_host.cpp:2363 calls
  `NetplayServer::stop()` from JNI `stop` on the main thread, and :1666 at the end of the run loop.
- What happens: `sendAll` loops on a blocking `send()` until every byte is out. The room serves all players from this
  one thread (UDP inputs too). A client whose receive window fills stops it dead: e.g. a client that sends
  `TCP_RECEIVE_SAVE "eep\0"` many times (each is queued in `waitingSaves`, :383-387, and answered with the whole save,
  :333-338) and never reads; or a real joiner whose Wi-Fi drops while the host sends it a 128 KB FlashRAM/.srm save
  (TCP keeps retrying for minutes). Then no input is relayed, so every game in the room waits, and when the host
  quits, `NetplayServer::stop()` writes the wake pipe (only seen by `poll`, not by a blocked `send`) and `join()`s
  forever on the main thread: ANR, and the run loop never reaches its quit save/`flushSave`.
- Who can reach it: any device that can open a TCP connection to the room port, including the internet once
  UPnP/NAT-PMP opened it.
- Fix: make client sockets non-blocking with a per-client output buffer flushed on POLLOUT (drop a client whose
  buffer passes a cap), or at least SO_SNDTIMEO and close the client on timeout.

### H2. Room server memory and descriptors grow without bound from network input (remote OOM / fd exhaustion)
- Where (netplay_server.cpp):
  - `c.in` (:563) has no cap. `TCP_SEND_SAVE` takes a 32-bit size from the packet (:376) and waits until that many
    bytes are buffered (:377): a client that sends `1, "x\0", FF FF FF FF` and streams data grows the vector to
    gigabytes. `TCP_SEND_SAVE`/`TCP_RECEIVE_SAVE` with no NUL (:374-375, :384-385) also buffer forever.
  - `saves[ext]` (:379): any client can add entries under any name and size.
  - `accept` (:535) takes unlimited connections, never times out an idle one; each costs an fd and a pollfd.
  - UDP: `lastAsked[p][id]` (:455) gains an entry per distinct `id` in a 12-byte `UDP_REQUEST_KEY_INFO`;
    `missing` (:464) gains one per distinct `count` asked for in adaptive mode and is only erased by `put`;
    `fillLeft` (:302-306) inserts `delay + 1` entries at whatever `count` a request names for a player who left, and
    `inputs[p]` is only trimmed in `gotInput` (:434), which never runs for that player again. The last one is also a
    plain leak in normal play: after a player leaves, every request from the others adds entries for the rest of
    the session.
  - `peers` (:290-293) grows with every distinct source address (spoofable over UDP).
- Effect: the host's process (the game itself) is killed by the low-memory killer or runs out of fds (then save
  writes such as `flushSave`'s fopen fail): progress since the last save is lost.
- Fix: cap `c.in` (e.g. 1 MB, larger only for an expected save from the local host), cap save size and count, cap
  clients (players + a few) with an idle timeout, and bound the UDP maps (ignore ids not registered, counts far from
  `hi[p]`, trim `inputs` for left players, cap `peers`).

---

## Medium

### M1. Netplay packets are unauthenticated: injection, kicks and UDP reflection
- Where: netplay_server.cpp:436-518 (UDP), :341-404 (TCP).
- What: any UDP sender becomes a peer (`addPeer`, :441/:447/:453) and every new input is then sent to all peers
  (`put`, :141), so spoofed sources turn the room into a reflector; `UDP_REQUEST_KEY_INFO` (12 bytes) is answered
  with up to 509 bytes to the claimed source (:478). Any sender can post inputs for any player index 0-3
  (:440-449; `cc` far ahead also makes :434 trim the real inputs away), set the de-sync bit with one mismatching
  `UDP_SYNC_DATA` (:501-508, shows "out of step" to everyone), mark players paused and change their names
  (:481-496). Over TCP any client can kick a player with `TCP_DISCONNECT_NOTICE` (:396-399) using the ids that
  `TCP_GET_REGISTRATION` hands to everyone (:321-327), and replace the saves joiners receive with `TCP_SEND_SAVE`
  (:373-381; the log says "from player 1" but nothing checks it is the local host).
- Effect: anyone on the internet who finds an open room port can disrupt or reflect traffic through it.
- Fix: accept UDP only from addresses of registered TCP clients (bind each player index to the address its
  registration came from), accept `TCP_SEND_SAVE` and `TCP_SEND_SETTINGS` only from the local (loopback) client,
  `TCP_DISCONNECT_NOTICE` only for the sender's own id.

### M2. A failed netplay loadGame leaves the room, router mapping and pause thread running; the next netplay start crashes
- Where: libretro_host.cpp:2019-2030 (room opened, `npPauseStart` thread started) before the load; failure returns at
  :2056, :2087, :2089 without undoing them; JNI `stop` returns at once when `!g_running` (:2353), and `start()` was
  never called; `npPauseStart` assigns `g_npPauseThread = std::thread(...)` (:90).
- What: after a failed start (unreadable ROM, load_game refused, Vulkan init failed) the room keeps its TCP/UDP port,
  Upnp keeps renewing the router mapping every minute, and the pause-notice thread keeps sending. Because
  `gameLoaded` stays false the next game runs in the same process; if it is a netplay game, assigning a new thread to
  the still-joinable `g_npPauseThread` calls `std::terminate`: a native crash.
- Fix: on every failing return after :2019, call `npPauseStop(); NetplayServer::stop(); g_netplay = false;` (or have
  JNI `stop` do that cleanup when `g_running` is false).

### M3. Router mapping removal runs on the main thread (ANR risk on quitting a room)
- Where: libretro_host.cpp:2363 (JNI `stop`, main thread) -> netplay_server.cpp:654 `Upnp::close()` ->
  upnp.cpp:245-251 joins the worker, which first finishes whatever it is in (initial discovery: `upnpDiscover` 2 s
  plus SOAP calls; a renewal) and then removes the mapping: NAT-PMP `natpmpMap(..., 0)` for TCP and UDP with up to
  2 s each (:54-68, :72-88, :222-225), or two UPnP SOAP deletes (:226-229).
- What: with a router that is slow or silent (common right when a room ends because the connection went bad) the
  main thread waits several seconds in onDestroy. Also, because `Upnp::open` keeps the lobby's mapping for the same
  port (:238), the game's stop closes the lobby's mapping too; and `Upnp::open/close` touch `g_thread` from the main
  thread, the emulation thread and PlayTogether's `upnpQueue` (PlayTogether.kt:433) without a lock, so two closes at
  once can `join()` the same thread concurrently (`std::system_error` -> terminate).
- Fix: hand `Upnp::close()` to a background thread (the room's own or a detached one) and guard `g_thread` with
  `g_mutex` (swap it out under the lock, join outside).

### M4. A failed Vulkan submit hangs the emulation thread forever (and then the main thread)
- Where: vk_context.cpp:598 resets the frame fence, :848 and :948 ignore `vkQueueSubmit`'s result; the next use of that
  sync index waits with no timeout (:467, :477, :484, :493 `vkWaitForFences(..., UINT64_MAX)`).
- What: when a submit fails (VK_ERROR_DEVICE_LOST after a GPU hang, which happens with custom Turnip builds), the
  fence was reset and never signals, so the emulation thread blocks at the next frame for good. JNI `stop` then
  joins it on the main thread (libretro_host.cpp:2364): ANR, the quit save and `flushSave` never run (save RAM is
  only flushed every 120 frames, :1648).
- Fix: check the submit result; on failure (or a finite wait timing out, e.g. 2 s like `capture`) mark the device lost,
  skip further GPU work, flush the save RAM and let the UI report the failure.

### M5. Network and preset bytes go to NewStringUTF unchecked
- Where: libretro_host.cpp:2293 `netplayPlayers` (`g_npRoster`, built from the room's packet as raw bytes at :116-136;
  names cut at 40 bytes, :68 and netplay_server.cpp:213, can split a UTF-8 character), :1886 `slangStatus` and
  :1871 `jstrings` (librashader error text with file paths, `#pragma parameter` names and descriptions from the
  user's .slangp/.slang files).
- What: utf16.h's own note: a 4-byte UTF-8 sequence (an emoji in a player name or a preset description) aborts the
  app under CheckJNI (debug builds), and invalid or cut-off sequences are not modified UTF-8, which ART's
  counter can step over the terminating NUL with. A remote room (or a player's emoji name relayed by it) reaches the
  joiner's `netplayPlayers` every second.
- Fix: return these through `utf8To16` + `NewString` as achievements.cpp's `jstr` does, and cut names on a character
  boundary.

### M6. Rewind has no memory cap in bytes
- Where: rewind.cpp:134-141 trims only by count (`max_`); `heldBytes_` is tracked but never limits; libretro_host.cpp:2218
  takes any `maxMoments`.
- What: How far back goes to 5 min and Steps to every frame: 18,000 moments. At the measured 15-82 KB a moment (CLAUDE.md:
  1 min at every 2 frames 30-145 MB) that is up to ~1.4 GB of native heap, plus the whole base state and progress. On
  the 2-4 GB devices the app targets the low-memory killer ends the game and its unsaved progress.
- Fix: also drop the oldest moments while `heldBytes_` passes a cap derived from the device's memory (e.g. a fraction of
  `ActivityManager.MemoryInfo.totalMem`), and show that cap in the popup's memory line.

---

## Low

### L1. A swapchain that fails to rebuild is never retried (black screen until the next surface change)
- vk_context.cpp:405-407 leaves `swapchain_` null on failure; `hasWindow()` is `swapchain_ != null` (vk_context.h:38),
  so the run loop only sleeps (libretro_host.cpp:1269-1270) and `applySurfaceIfChanged` only rebuilds on a surface
  change. Rebuilds happen on OUT_OF_DATE (:504, :858) and the unthrottled switch (:463). Also present errors other than
  OUT_OF_DATE (SURFACE_LOST) are ignored (:857). Fix: when a surface exists but the swapchain doesn't, retry
  `createSwapchain` every few hundred ms.

### L2. surfaceDestroyed doesn't wait for the emulation thread to let go of the surface
- libretro_host.cpp:1766-1771 only queues the change; frames keep being presented to the old window until the next
  loop pass (MainActivity.kt:2306). The VkSurface/EGLSurface hold their own references, so this is normally only
  failed presents, but Android's contract is to stop using the surface before surfaceDestroyed returns. Fix: have
  setSurface(null) wait (bounded) until the emulation thread has detached.

### L3. Room/Upnp start-stop flags are check-then-set from two threads
- netplay_server.cpp:646-653 (`if (!g_running) return; g_running = false;`) runs from JNI `stop` (main) and the run
  loop's end (emulation thread, libretro_host.cpp:1666); upnp.cpp `g_thread` likewise (see M3). Unlikely ordering
  today (the main thread's call comes first by far), but a double stop would double-close fds and double-delete
  `g_room`. Fix: `if (!g_running.exchange(false)) return;` plus a mutex around the stop body.

### L4. Rewind: a failed unpack leaves a half-XORed base
- rewind.cpp:38-56 XORs records into `dst` as it goes and returns false midway; rewind.h:102 then clears the moments
  but keeps `hasBase_` and the damaged `base_`, which the next packed moment is the difference from, so a later step
  back loads a damaged state into the core. Only on an internal inconsistency (zlib output always matches). Fix: on
  failure also set `hasBase_ = false`.

### L5. Failed GLES loadGame leaks the EGL context and leaves it current on the main thread
- libretro_host.cpp:1968-1970 makes the context current on the main thread; the failing returns (:2056 etc.) skip the
  `eglMakeCurrent(..., EGL_NO_CONTEXT)` at :2099, and the next start's `g_egl.init()` (egl_context.cpp:13-38)
  overwrites `display_/context_/pbuffer_` without destroying them. Fix: `g_egl.destroy()` on those returns.

### L6. 32-bit size checks on JNI arrays
- libretro_host.cpp:1837 `(jsize)w * h * 4` and :1911 `w * h` are int multiplications that overflow for huge
  dimensions, after which `Image::set` copies `(size_t)w*h*4` bytes from a shorter array. Kotlin passes sizes that
  match its own arrays, so not reachable today. Fix: compute in int64 and compare.

### L7. Room loop checks the wrong pollfd after removing a client
- netplay_server.cpp:558-577: after `clients.erase(... i)`, `i--` makes the next client use the removed client's
  `fds[i + 3]` revents. Harmless now (recv is MSG_DONTWAIT, EAGAIN is ignored), but a client can miss a poll round.
  Fix: build a fd->client map or iterate a copy of indices.

---

## Checked and found fine
- Option map: `g_optionOverrides` is written on the UI thread only before `start()` (setRenderer, setOption from
  applyControls/applyGpuSettings/onCreate, loadGame); in game only through `setOptionsLive` -> mutex-protected queue ->
  `applyLiveOptions` on the emulation thread between frames (libretro_host.cpp:317-334, :1267); fast-forward writes
  it on the same thread (:1321-1333). reloadControls uses the live path (MainActivity.kt:2572).
- Between-frame requests: state save/load/capture/reset handoff (`runStateRequest`/`serveStateRequest`) holds
  `g_stateMutex` through the serve, timeouts clear the request under the same lock, the request lives on the caller's
  stack only while it waits; paused requests are served without a frame; netplay refuses them while the core waits.
- Save state data: `loadState` refuses data under 44 bytes or without `M64+SAVE` before the core sees it
  (:1012-1017); the bundled core also checks the full body length (joey64-core ra-known2 libretro.c:2390-2409) and
  `initializing`. ACHV progress goes to `rc_client_deserialize_progress_sized` (bounds checked by rcheevos).
- Save RAM: written to `.tmp`, fflush + fsync, rename; failure keeps the old file and reports the size (:933-949);
  short or missing files load what is there.
- Audio ring: single producer (emulation thread: write/writeFast/push), single consumer (AAudio callback);
  acquire/release on `r_`/`w_`, full/empty tests correct (writeFast needs 2 free samples), callback never blocks;
  stream reopen/close under `streamMutex_` with the callback stopped first; target max 150 ms < 200 ms ring.
- Rewind worker: handoff under `m_`, `drain()` before the emulation thread touches `base_`, count trimming, decode
  bounds (`w + l > nw`, `p + l*8 > size`, tail length), uncompress length checked.
- Netplay UDP parsing bounds: KEY_BATCH (`k + 9 <= n`), SEND_KEY_INFO (n >= 11), REQUEST (n >= 12), PAUSE_NOTICE name
  length `min(d[9], n - 10)`, SYNC (n >= 5, map trimmed to 8), player index `d[1] < 4` everywhere; TCP register index
  0-3; reply buffers (sendInputs 5 + 56*9 = 509 <= 512, roster <= 179 bytes, registration 24 bytes).
- Joiner side packet parsing (pause-notice thread, libretro_host.cpp:115-145): roster loop bounded by `k + 4 + l <= n`,
  echo sequence ring index `s % 16` after the age check.
- upnp.cpp: NAT-PMP waits bounded (natpmpAsk), outside port restricted to the code range, half-made UPnP mapping
  undone, URLs freed; `g_outside` written under `g_mutex`.
- JNI refs: `GetObjectArrayElement` results deleted in loops (setOptionsLive, setCheats, jparams, jstrings),
  Release* paired with every Get*Chars/Elements (JNI_ABORT for read-only), RaBridge class held as a global ref,
  `Env` attaches/detaches native threads only when not already attached, exceptions cleared after Java calls.
- Input/macro/turbo globals: atomics or `g_macroMutex`; port/index arguments range-checked in every setter.
- Display options: all fields atomic; sides/overlay images copied under the Image mutex, generation counter read
  first (a missed generation only uploads on the next frame).
- Vulkan capture: buffer sized from the image, 2 s fence timeout then queue idle before freeing; picture/crop/sharp
  images waited idle before reallocation; librashader chain rebuilt only after `vkQueueWaitIdle` (vk_context.cpp:638).
- GLES capture bounded by the FBO size; crop only when the frame fits the FBO.
- achievements.cpp: RDRAM reads clamped to the RAM size under `g_ramMutex`; the RA token is put in the login event
  for Kotlin and never logged (`loginDone` logs only ok/error); strings to Java through `utf8To16`.
- libra.cpp: API loaded once under a mutex, presets/chains freed on every path, preview context separate and released.
- joeylog.cpp: fixed 2048-byte buffer with vsnprintf, file writes under a mutex, flushed per line.
- kgsl_priority_hook.c, gpu_counters.cpp, gpu_clock_pin.cpp, perf_hint.cpp, rewind_test.*, N64_STATE/N64_RAM_DUMP/
  N64_STATE_SAVE_TO/N64_LAG_PROBE: only active through env switches, which MainActivity accepts from intents only in
  dev builds (`devString`, MainActivity.kt:69); the KGSL hook acts only with N64_KGSL_PRIO set (GpuRules on the Odin 3).
- Per-process statics (thermal governor read once, run counters never reset) don't leak into a second game because a
  successful game is always followed by a fresh process (MainActivity.kt:98, :2342-2368).
