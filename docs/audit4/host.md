# Audit 4, host half (native host, bundled core, Workers, release config)

Base: the merge cca5848 (main 0899577 into launcher/phase0 a1ff2db; main fast-forwarded to it). In these areas the
merge only adds main's libretro_host.cpp lines (per-port Transfer Pak, the bogus-address stop), NativeBridge's new
setTransferPak and core-version.txt rel3: no conflicts there, the host diff is line for line main's, and
MainActivity's call matches the new signature. Compared with audit 3's base 8419abe. Findings are ranked and checked
against the code; nothing is fixed until Joey answers.

## High

**H1. A netplay joiner's own save is replaced by the host's.** (Found by the app half's part E2; read and confirmed
here in the host and core rel3 cd9f4a0, not yet run on a device.) `libretro_host.cpp:1002-1035` (loadSave /
flushSave); core `mupen64plus-core/src/main/main.c:903, 920, 937, 954` and `netplay.c:755`.
- `loadGame` runs `loadSave()`, which reads the joiner's own `<save name>.srm` into save RAM and keeps a copy in
  `g_lastSave`.
- On the first frame the core opens its save files. On a joiner, each `open_*_file` calls `netplay_read_storage`,
  which fills `saved_memory` with the host's save. `saved_memory` is the same buffer the host app reads as
  RETRO_MEMORY_SAVE_RAM (`libretro.c:2279`).
- Every 120 frames, and on exit, `flushSave()` sees the buffer differs from `g_lastSave` and writes the host's save
  over the joiner's .srm. There is no `g_netplay` check on that path. SrmSync then pushes it to a picked data folder
  too.
- Scenario: you join a friend's Mario Kart 64 room. Afterwards your own Mario Kart save on this device is your
  friend's. The only copy left is in Back up saves, if it was on.
- Check on a device: the md5 of the joiner's .srm before and after one session.
- Fix idea: while in a room, a joiner never writes save RAM back. Point `g_savePath` at a scratch file for the
  session, or skip `flushSave` when `g_netplay` is set and this device isn't player 1.

## Medium

**M1. Junk uploads can use up every wanted game's places for good.** `server/reports/src/state.js:63-96`.
The server checks only the first 8 bytes ("#RZIPv\x01#") and the size. The wanted list is public (joey64-data release).
Scenario: a script posts 1 KB-20 MB files that start with the RZIP magic for each listed md5, 10 a day from each /24,
until each game has `enough` (3-10). After that every real player's upload gets 410 "have enough", and the app marks
that game done and never asks again (`StateRequests.done`), even after we delete the junk. The 200 a day for the whole
server slows this down but doesn't stop it. It also lets one person block real uploads for the rest of the day.
Fix idea: inflate the first RZIP chunk in the Worker (DecompressionStream) and require RASTATE/`M64+SAVE` whose ROM
MD5 field matches `md5` (to check: the core's state header has the ROM MD5, uppercase); and keep a
"don't ask again" from a 410 on our side, so we can open places again.

**M2. The state ring is never removed, and a data-folder move carries it off.** This is the Frontend's code, but it
depends on the host's ring. `MainActivity.kt:3317`, `StateRequests.kt:143`, `DataMover.kt:80-111`.
- Each wanted game played with the ring on leaves `files/states/ring/<md5>/`, five 16.8 MB raw states (84 MB) plus
  meta.json. The host clears it only when that game next starts with the ring on (`state_ring.cpp` removeOldRing).
- After Yes (the state is copied to the outbox), Not now, Don't ask for this game or Don't ask again, the ring is
  never started again for that game, so the 84 MB stay for good. A player who tries ten wanted games keeps about 840 MB.
- Settings › Saves › Data folder moves `states/` recursively, so the ring's .raw files go into the player's picked
  folder. They become orphans there: the host keeps writing the internal path, and meta.json names the internal
  paths. A later move back reports them as conflicts or failures by name (`states/ring/<md5>/0.raw`).
- Fix idea: delete the ring once the popup is answered or nothing is asked (keep it only while a Yes is being
  queued), and have DataMover skip `states/ring`.

## Low

**L1. Eight idle connections fill a public room.** `netplay_server.cpp:59` (kMaxClients 8), `:704` (60 s to register).
Audit 3's H8 fix capped connections, but there is no cap per address. Anyone who has the room's address from the
public list can open 8 connections, renewed every minute, and real joiners are turned away. On those connections
registration can be retried with no limit, guessing the 32-bit place keys (too slow to matter in a room's life, but
free). Fix idea: at most 2 unregistered connections per address, and close a connection after 3 wrong keys.

**L2. The game jumping to a bogus address mid-play shows "won't start".** main `libretro_host.cpp:571`. The line
sets `g_coreShutDown` (1), which the library shows as the "<game> won't start" Problem screen, also for a game that
has run for an hour. The save is written: the normal unload runs `flushSave` (`:1768`). Fix idea: a second code for
"stopped while playing" and its own line (Joey's wording).

**L3. Closing a room with players waits 400 ms while holding the room lock.** `netplay_server.cpp` stopLocked. It
runs on the quit path (`libretro_host.cpp:2515`). It is only 400 ms, but no other start() or stop() can run in that
time. No change needed unless the quit path is ever timed.

**L4. The state route's limits can overshoot by a few.** `state.js:93-112`: "have enough", the day counts and the
storage total are read, then the row is written, with no transaction. Posts that run at the same time all pass. The
overshoot is a few states, inside the 8 GB cap's margin.

**L5. (Closed after the merge.)** `core-version.txt` records rel3 (ddbd5f) and joey64-launcher's jniLibs now has
it too (the Frontend, after cca5848; checkBundledFiles passes). rel3 has netplay and N64_NETPLAY_KEY (checked in the
binary). Kept here for the release pass: any other build checkout must have rel3 bundled.

**L6. Out-of-date comment.** `vk_context.cpp` probe: "the game's instance stays at the core's 1.1". The core has
asked for a 1.2 instance since vk12, as the lines below it say.

## Checked, no finding
- `state_ring.cpp`/`.h`:
  - files are written atomically (tmp + rename) and kept to 5;
  - the worker writes every queued state before it stops;
  - meta.json escapes the paths, and quitAt is written only at the end.
  - The md5 case is the same for writer and reader (launcher md5s are lowercase).
- Core rel3 (cd9f4a0) includes rel2's netplay merge. "angrylion" in the binary is only the RDP plugin option's name
  (`Makefile.common:397`: not built).
- `setTransferPak`'s new array signature matches main's NativeBridge and MainActivity call.
- Dev intent extras (`env`, `opts`, `renderer`, `driver`, netplay) are still gated to dev builds (audit 3 H9).
  `EXTRA_STATE_RING` from another app only turns the internal ring on.
- Netplay since audit 3:
  - the keyed registration accepts only the place's key or the local game;
  - keys are never logged;
  - the host-left marking runs before close.
- `vk_context.cpp` probe's 8-bit storage check (extension or 1.2 core).
- `checkBundledFiles`'s netplay string check: `\bN64_NETPLAY\b` doesn't match inside N64_NETPLAY_KEY, so each word
  is checked on its own.
- Licences: xbrz.txt is removed along with xBRZ, which was built out of the core in rel1. There are no new
  dependencies.
- server/rooms: only its README changed since audit 3.
- server/reports:
  - the D1 migration 0005 matches schema.sql;
  - an R2 file is removed when its row isn't written;
  - the address hash is cleared after its day.
