# Code audit 3: ranked report (all eight parts)

App `launcher/phase0` at df01a53, core `ra-known2` at 1281dd8. Read only: **nothing is fixed yet. Fixes wait for Joey's "Fix all".**

All eight parts finished. Each part's file in this folder has the full write-up: quoted lines, call path, fix, and a
"checked and fine" list. Part 1: game start/quit, saves, states, cheats. Part 2: launcher screens. Part 3: launcher
data, library, storage, updater, lobby. Part 4: video, controls, achievements. Part 5: native host. Part 6: core.
Part 7: servers, tools, release config. Part 8: UI, accessibility, touch and controls.

**Who checked what:** ✓ means I re-read the cited code myself and the finding holds (**device** means I also saw it
happen on the Odin 3). · means the part's agent checked it with quoted lines and I haven't re-read it yet. Duplicates
found by two parts are merged.

Totals after merging: **High 10, Medium 34, Low 73** (Lows are listed by title below, details in the part files). H10 was a Medium in part 1; I raised it because it runs another app's native code.

## High

| # | Part | Where | What goes wrong | Fix | |
|---|---|---|---|---|---|
| H1 | 3, 1 | `DataFolderMove.kt:28-31`, `DataFolders.kt:30,121-138` | **Moving the data folder onto one that has a different `saves/<game>.srm` loses the user's save.** The conflict stays in `files/saves`, `DataFolders.set` clears the SrmSync stamps, and the next start's pull copies the folder's copy over it. The per-start backup runs after the pull. | Stamp conflicting names so the first sync pushes ours, or keep both and say so. | ✓ |
| H2 | 1 | `MainActivity.kt:98, 2343-2352, 1213` | **The second game in a process unpacks its ROM on the main thread, and a failure there crashes the app.** `gameLoaded` stays true, so Play after Quit to library goes through `switchGame` → `resolveRom` → `copyToCache` (up to 64 MB, or a zip/7z) with no catch. A missing URI or a full disk ends the app; the first game's path catches the same errors. | Run it on the game I/O thread with the same catch as `prepareGame`. | ✓ |
| H3 | 2 | `CheatsScreen.kt:181-184`, `GameCheats.kt:78-90` | **Two quick cheat changes can delete the game's whole cheats file.** Every toggle starts its own IO write with the same `.part` name. When two overlap, the second deletes the file the first just renamed in, then its own rename fails. Every cheat that was on and every typed code is gone. | One writer at a time (a Mutex or a single-thread dispatcher), latest wins. | ✓ |
| H4 | 6 | core `libretro.c:2318-2322`, `libretro_host.cpp:1009`, `GameStates.kt:91,229` | **Save states are refused whenever an RSP task is still running at the frame's end** (about 5% of frame ends in SM64, by the core's own count). The host doesn't retry, so a quit save keeps the older auto save (Continue loses the progress since), and slot, recovery and Undo-load saves fail the same way. A game paused on such a frame fails every time. | For normal saves, run the next frame(s) until a safe edge, or fall back to the old job path. | ✓ code (next: count failures on a device) |
| H5 | 4, 7 | `src/debug/res/xml/dev_network.xml` only | **Release builds can't reach RAOfflineProxy**: cleartext to 127.0.0.1 is allowed only in debug. With the proxy route, Joey64's queue keeps nothing, so every unlock in a release build is lost. All checks so far ran debug builds. | A main network config allowing cleartext for 127.0.0.1 / localhost only; one release check with the proxy. | ✓ |
| H6 | 3 | `PlayTogether.kt:241,255,478` | **The netplay lobby reads lines of any length from anyone who reaches the room's port.** `OutOfMemoryError` misses the catch and kills the launcher; idle sockets can tie up Dispatchers.IO's 64 threads. Public rooms list the address. | Capped line reader, few pre-HELLO sockets, HELLO timeout. | ✓ |
| H7 | 5 | `netplay_server.cpp:281-289, 650` | **The room thread can block forever in `send()`** (blocking client sockets, `sendAll` loops). A client that stops reading freezes every player, and `stop()` joins that thread from the main thread on quit: an ANR, and the quit save is skipped. | Non-blocking sockets with a send buffer cap; drop a client that falls behind. | ✓ |
| H8 | 5 | `netplay_server.cpp:373-381, 535, 563` | **Room server memory and descriptors grow without bound from network input.** `c.in` has no cap, TCP_SEND_SAVE takes a 32-bit size from the packet, connections are unlimited with no idle timeout, and UDP maps grow (one leaks in normal play after a player leaves). | Cap `c.in` and the save size, limit connections, time out idle ones, prune the maps. | ✓ |
| H9 | 7 | `MainActivity.kt:127-130` (exported) | **Any installed app can make Joey64 open a netplay room with a router port mapping, or join any server, in release builds.** The netplay extras skip the `devBuild` gate the other switches got in audit 2. | Accept them only from the launcher (a one-time token, or a non-exported alias). | ✓ |
| H10 | 1 | `MainActivity.kt:544, 1092-1101` | **The dev Vulkan driver folder is honoured in release builds.** Any `.so` in `Android/data/.../files/drivers/` is copied and loaded as the GPU driver. On Android 10 other apps with storage permission can write there, so native code from another app runs inside Joey64. | `devBuild` check in `devDriver()`. | ✓ |

## Medium

**Data and saves**
- M1 (1) ✓ `GameStates.saveQuit` (`GameStates.kt:148-156`): a failed quit save still deletes the oldest kept quit save
  and renames the rest; Restart now for settings pushes one out too. Fix: take the state first, rotate only on success.
- M2 (3) ✓ Removing a ROM folder during a library read leaves games that never play (`LibraryModel.kt:480-484`:
  `cancel()` without join). Fix: `cancelAndJoin()`, and drop rows of unlisted trees.
- M3 (3) ✓ A folder's grant is released while another role uses it (`DataFolderMove.kt:35`, `LibraryModel.kt:1059`).
  Fix: one `releaseIfUnused`.
- M4 (3) ✓ Moving off an unreadable data folder "succeeds" with 0 files and drops the grant (`DataFolderMove.kt:26-35`,
  `TreeSpace` listing failure reads as empty). Fix: check it's readable first.
- M5 (2) · Leaving Settings › Saves (L1/R1 or B) during a data-folder move leaves it half done: the cancelled scope throws
  CancellationException, which is swallowed; the DataStore copy, settings mirror and old grant aren't updated.
- M6 (2) · Delete save and Restore a backup go ahead when the "backup kept first" failed (`SaveBackups` swallows it).
- M7 (2) · In game, closing Cheats can give the game the old cheats: `sendCheats` reads the file before the last
  background write lands (code read ✓; timing not shown on a device).
- M8 (4) ✓ Transfer Pak: an unreadable picked Game Boy save is still marked as copied (`GameBoyFiles.kt:196`), so the
  old save plays with no message and the next start doesn't retry; disk errors there throw into game start.
- M9 (4) ✓ RAOfflineProxy dying mid-game loses every unlock after it (`RaBridge.kt:362-367`: no answer isn't queued on
  the proxy route).

**Crashes and hangs**
- M10 (4) ✓ Saving or clearing the RetroAchievements sign-in can crash the app (`RaBridge.kt:73,293,310`: IO scope with
  no handler; `SignInStore.save` encrypts with no catch).
- M11 (5) · A failed netplay loadGame leaves the room, router mapping and pause thread running; the next netplay start in
  the process assigns to a joinable `std::thread` (`libretro_host.cpp:90`) and terminates. (Line 90 ✓; whether a failed
  start really skips `npPauseStop` not re-read.)
- M12 (5) ✓ A failed `vkQueueSubmit` is ignored (`vk_context.cpp:848`); on device lost the fence wait never returns,
  `stop()` then hangs the main thread, and save RAM since the last flush is lost.
- M13 (5) · Router mapping removal (`Upnp::close`, joins discovery) runs on the main thread when a room's game quits:
  several seconds on a slow router, ANR risk.
- M14 (5) ✓ Network and preset text goes to `NewStringUTF` unchecked (`libretro_host.cpp:1871,1886,1922,2266,2293`);
  an emoji in a player name, or a name cut at 40 bytes mid-character, aborts under CheckJNI. `utf8To16` exists.
- M15 (5) · Rewind is capped by count, not bytes: 5 min at every frame is about 18,000 moments, ~1.4 GB, which the
  low-memory killer ends on 2-4 GB devices.
- M16 (6) ✓ Compile thread (Mali default): `drc_front_done`'s memcmp runs on the worker and reads the main RDRAM view,
  which smart sync protects (Snap on Mali), so the SIGSEGV handler runs and waits on the GPU from the worker. Read
  through `g_rdram_alias`. (The agent also named drc_service's memcpy; that one runs on the emulation thread.)
- M17 (6) · Run-ahead on async parallel-RDP never waits for the GPU before RDRAM is put back (dev switch only).

**Main-thread work (ANR / jank)**
- M18 (1) ✓ The whole ROM is MD5-hashed on the main thread when the intent has no MD5 (every frontend start):
  `earlyMd5`, `applyControls`, `reloadControls`, `startSession`.
- M19 (1) · With a picked data folder, the cheats file is read over SAF on the main thread at every start.
- M20 (2) · Your own image (Sides / Overlay) is copied on the main thread (cloud providers: ANR) and silently replaces an
  image of the same name other games use.

**Network and servers**
- M21 (5) · Netplay packets are unauthenticated: anyone who reaches the port can inject inputs, kick players, replace the
  saves joiners get, fake pause notices, and use the room for UDP reflection (12 bytes in, up to 509 out). This is
  mupen64plus netplay's design; the router mapping is what exposes it.
- M22 (7) ✓ A report over 64 KB is never sent and blocks that install's reports for good (413 treated as "try tomorrow").
- M23 (7) ✓ Reports server: the per-install cap is the poster's own choice, so one address can use up D1's free daily writes.
- M24 (7) ✓ Rooms server: IPv6 posters skip the address check (list rooms for any IPv4, replace a real room, fill the list).
- M25 (7) ✓ The GPL source offer names only the app repo, not the bundled core; both repos are private.

**Settings screens**
- M26 (2) **device** Y (reset one row) on Video, Audio and Emulation undoes every other change made since the tab opened
  (Odin 3: Volume 90% and Mute On both went back when Y reset Mono). `LaunchedEffect(target)` keeps the first `reset`.
- M27 (2) ✓ Picking another folder in Your own RetroArch preset / RetroArch overlay keeps listing the old folder
  (`SlangPopups.kt:108`: `produceState` only scans while `value == null`).
- M28 (2) · X in the "<what> for" game lists removes a game's own settings without asking. The mockup says "X removes
  them": **Joey's call**.

**UI, touch, accessibility (part 8)**
- M29 **device** ◀ ▶ value rows can't be changed by touch (Volume: tap, tap on the value, swipe all do nothing). This
  includes Touch controls' own Opacity, Sensitivity and Dead zone, for people with no controller.
- M30 · (device: Reset tab ✓) Many actions exist only on X, Y, Start or L1/R1, and the hint row can't be tapped: share or
  delete a screenshot, remove a macro, reset a row or tab, adjust a shader, save or rename a preset, move a netplay
  player. A TV remote loses the same ones.
- M31 ✓ A stray tap or B on the host's room popup closes the room for everyone (`NetplayScreens.kt:474`), hint "B Back".
- M32 · Place the game (RetroArch overlay / own image) takes keys only: no drag, pinch or touch way out.
- M33 · Touch targets well under 48 dp (rows ~30, tabs ~26, chips ~29); no accessibility semantics (locked rows announced
  as buttons, tabs don't say which is selected, no labels on covers without art, pop-ups never announced).
- M34 · Contrast: greyed rows' explanations 2.1-2.8:1; light mode's focus highlight 1.4-1.5:1 (needs 3:1).

## Low (titles; details and fixes in the part files)
- **Part 1:** RZIP chunk Int overflow crashes the state thread; cheat source names from the network unchecked (../);
  cheats file replace not atomic; session logs carry other players' IPs and paths; other apps can pick the save name;
  no size limit on ROM unpacking; Back while loading still loads and counts a failed GPU trial; bundled driver stamp
  written when the rename failed; disk saves can be pushed half-written.
- **Part 2:** a cheat change before the file is read overwrites it; a corrupt cheat index crashes Cheats and Video;
  VideoTab's extras producer has no catch; Copy's "Replace and copy" is focused; recovery's "Not now" deletes the state
  (approved wording, Joey to judge); Saves page actions can run twice; Saves page exceptions crash the launcher; small
  main-thread reads in composition; Pick a game does nothing when it fails; "<what> for" lists show sanitised names;
  Transfer Pak ignores a wrong pick silently; Share logs twice breaks the zip; unfetched badges can tie up the IO pool.
- **Part 3:** BIOS import keeps every IPL copy in memory; non-UTF-8 zip names fail every scan; `swapIn` kill window;
  update retry deletes its own part file; no APK digest check; room md5 not checked on the client; GO player number
  unchecked; `hosting()` race; box-art jobs catch only IOException; replies read with no size cap.
- **Part 4:** RaBridge.init reads keystore and DataStore on the main thread; `serverCall` answers only IOExceptions;
  host-override receiver (accepted design); a damaged overlays index.json throws at game start; Picture popups decode on
  the main thread; names from settings files not re-cleaned; SAF copies uncapped; rename deletes before writing (presets,
  profiles); `PictureImages.copyIn` deletes before the rename.
- **Part 5:** swapchain rebuild never retried; surfaceDestroyed doesn't wait for the emulation thread; room/Upnp flags
  check-then-set; failed rewind unpack leaves a half-XORed base; failed GLES loadGame leaks the context; 32-bit size
  checks on JNI arrays; room loop checks the wrong pollfd after a removal.
- **Part 6:** COW run-ahead state loaded elsewhere uses stale RDRAM; `ra_side` not tied to its state; `retro_serialize`
  doesn't check size; `setjmp` form; parallel-RDP init doesn't join the cache saver; signal handlers not
  async-signal-safe (by design); `ss_cow_copy` marks before opening.
- **Part 7:** no check that core/drivers exist and match at release; hard-coded driver names (meta.json says 1.2.5);
  crash-text scrub stops at a space; RA host-override receiver; other launcher extras taken from any app; rooms reads
  the body before its size check; workflows paste upstream text into issues; GLideN64 v2 vs xBRZ v3 licence (**Joey's
  call**); ScreenScraper login XOR only (decided).
- **Part 8:** hint row clips at large fonts; full-screen popups have no back for touch; no swipe in the screenshot
  viewer; light-mode gold/green just under 4.5:1; same action, different keys and words; in-game overlays outside
  SharedElementsTest; InfoRow label clips; diagram pills fixed size; description line only follows focus; rail is icons
  only; options users expect with no row (run-ahead, save to slot N / save and exit hotkeys, frame skip, Hardcore and
  Leaderboards, texture packs).

## Needs Joey
- "Fix all" to start fixing (none done).
- M28 (X removes a game's settings without asking, as the mockup says), the recovery popup's "Not now" wording, and the
  GLideN64 / xBRZ licence question.
- Touch fixes for M29, M30 and M32 add new touch controls (tap zones on ◀ ▶, tappable hints or a long-press menu, drag in
  Place the game): these need a mockup first, per the menus rule.
