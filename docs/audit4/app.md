# Audit 4, app half (Kotlin, UI, data files)

Code: launcher/phase0 at cca5848 (main merged in; main fast-forwarded to it). Read only; nothing fixed.
Brief: BRIEF-app.md. Seven parts, each read by its own agent, then checked by the launcher session. Full evidence
(quoted lines, call paths, fixes, and "checked and fine" lists) is in app-parts/part<letter>.md; the IDs below point
there (A-H1 = part A's H1).

Totals as the parts reported them: High 4, Medium 21, Low 49 (B-M1 is the same as C-M1/C-M4, so 20 different
Mediums; one High, E2-H1, is native/core and goes to host.md).

How each was checked by me, on top of the part agent's own evidence:
- "Thor": reproduced on the Thor (ea2b5c6c, build mg-92675152, data restored to its backup afterwards).
- "Read": I read the quoted lines and the call path myself and agree.
- "Agent": the agent's quoted evidence only; not re-read by me.

## High

1. **A-H1 Another app can end the running game.** (Thor, corrected) MainActivity is exported. A start with no ROM
   skips the only guard (MainActivity.kt:120 needs a ROM) and runs the whole first-start setup again. On the Thor,
   `am start -n com.joeysretro.joey64/.MainActivity` during Duke Nukem 64 logged "core: still up from a start that
   failed, shutting it down first", then "the core shut down: the game stopped, closing the game screen": the game
   ended with no quit save and the screen went black. No native crash, as the agent expected; the harm is the lost
   play since the last save. Fix: in onCreate, when a game is loaded and the intent has no ROM, finish the new
   instance and leave the running game alone.
2. **B-H1 A blank save can replace the real one in a picked data folder.** (Read) If the folder can't be read when
   a game starts and there is no working copy (common after a move, a reinstall, or a Saves restore or delete),
   `pullFile` gets null from `openRead` and returns (DataFolders.kt:132-133); nothing stops the start
   (MainActivity.kt:1349). The game makes a new empty save, and once the folder is back `pushFile` writes it over the
   real one, since its stamp is unknown (DataFolders.kt:148-152). Fix: tell "folder unreachable" from "no save yet";
   don't start (or start without pushing) until the folder answers, and never push over a folder copy the device has
   never pulled.
3. **D-H1 The Crop edges popup crashes on an Exact amount.** (Read) `cropText` looks the amount up with
   `VideoChoices.crop.first { }` (CropPopups.kt:70), which only holds 0, 4, 8, 12 and 16. The popup calls it on its
   first draw with the current value (:146, :159), so once 2, 6, 10, 14 or 18-32 is saved, opening Crop edges
   crashes every time. Fix: use `firstOrNull` and fall back to "Exact, n lines".
4. **E2-H1 A netplay joiner's own save is replaced by the host's.** (Agent; native/core: passed to host.md) The core
   writes the host's save into save RAM after `loadSave()`, and `flushSave()` then writes it over the joiner's own
   .srm with no netplay check (libretro_host.cpp:1002-1035). Not yet run on a device: an md5 of the joiner's .srm
   before and after one session would confirm it.

## Medium

State requests (new since audit 3):
- **C-M1 / B-M1 The state ring is never deleted.** (Read) files/states/ring/<md5>/ (5 x 16.8 MB) is only removed by
  the host on the next start of that game with the ring on (state_ring.cpp:71-73). After any answer, a send, or the
  game leaving the wanted list, `ringFor` is false and it stays for good. Fix: delete the ring after the answer (once
  a Yes has copied it), when `check` finds nothing to ask, and in a start-up sweep.
- **C-M4 / B-M1 A data folder move carries the rings.** (Read) DataMover walks states/ recursively
  (DataMover.kt:80-89), so ~84 MB per ring goes through SAF into the picked folder, and a move back reports the
  .raw files as conflicts. Fix: keep the ring outside states/, or skip `states/ring` in the walk.
- **C-M2 A game is marked done forever, sometimes before the server takes it.** (Read) `answer(Yes)` adds the md5 to
  "sent" as soon as it is queued (StateRequests.kt:174), and a 410 or 404 "not wanted" calls `done` too
  (StateUpload.kt:76, :99). Nothing clears it, so drops (400/413, 12 tries, the outbox cap) and a count that goes back
  down never ask again. Fix: mark sent only on a 2xx; keep "enough" with the wanted list's date and clear it when the
  list changes.
- **C-M3 The outbox cap deletes the newest Yes.** (Read) `waiting()` is newest first and the cap uses
  `dropLast(MAX_WAITING)` (StateUpload.kt:51), which deletes all but the 5 oldest: with 6 waiting, the one just
  queued goes while "on its way" still shows. Fix: `drop(MAX_WAITING)`, with a test.
- **E1-M5 On narrow screens the save-state ask never shows "What's sent".** (Agent) StateRequestPopup.kt:78-80; its
  3-line text also clips at large font.

Game screen and saves:
- **A-M1 Switching games deletes the recovery state before the new ROM is checked.** (Read) MainActivity.kt:2487-2494
  stops the game and clears its recovery on gameIo before `resolveRom()`; a bad file from another app loses the play.
- **A-M2 A full disk crashes every start.** (Read) LogFiles.kt:172 writes the exit files with no try, and LAST_EXIT
  is saved only after the loop, so the same write fails at every start, launcher included.
- **B-M2 A data folder move releases the old folder even when files were left there.** (Agent) DataFolderMove.kt:95.
- **B-M3 Picking a folder that already has a settings copy overwrites it with this device's settings.** (Read)
  SettingsMirror.moved pushes without looking (SettingsMirror.kt:68-76); on a first run those are the defaults.
- **D-M2 The Transfer Pak's Game Boy save loses progress when switched away and back.** (Agent)
  GameBoyFiles.kt:197-208 keeps one older copy and re-reads the original file.

Netplay:
- **E2-M1 "Restart to use the new settings?" restarts inside a netplay room.** (Read) `askRestart`
  (MainActivity.kt:870) has no room check; the restart breaks the room for everyone.
- **E2-M2 A failed netplay start says nothing and the others wait 30 s.** (Agent)
- **E2-M3 Room cards and Host a room's covers have no TalkBack label.** (Agent)

UI and settings:
- **D-M1 The Crop edges side panel says Off for a game whose default is Normal.** (Thor + Read) VideoTab.kt:214 uses
  the stored value; the row (:227) and popup (:277) use GameCrop. The All games note at :353 has the same mistake.
- **D-M3 Renaming or deleting a macro leaves a dead touch button.** (Agent) Buttons find macros by name
  (MainActivity.kt:2632).
- **E1-M1 Leaving the tab while a ROM folder is "Looking…" drops it silently.** (Read) The catch-all in
  LibraryTab.kt:147-164 swallows the cancellation; the 64DD BIOS import (135-144) has the same shape.
- **E1-M2 Tidy names is greyed with Names Off but still changes titles.** (Agent) LibraryTab.kt:206, 375.
- **E1-M3 Emulation's Reset tab says "every row" but leaves the six Speed rows.** (Agent) SettingsScreen.kt:238, 251.
- **E1-M4 "Why it didn't start" cuts its reason to one line.** (Agent) ProblemPopups.kt:57-67; Details' File row too.

Data:
- **F-M1 Auto pak gives a Controller Pak to No-Intro copies of Rumble Pak games.** (Agent, md5s listed in part F)
  pakdb.tsv comes from GoodN64 md5s; Paper Mario (Europe), GoldenEye 007 (Japan) (Switch Online) and others are
  missing.

## Low

49 items, one line each in the part files: A-L1..L4, B-L1..L7, C-L1..L8, D-L1..L5, E1-L1..L9, E2-L1..L9, F-L1..L7.
Ones worth naming:
- A-L2: EXTRA_STATE_RING isn't in LAUNCHER_EXTRAS, so another app's flag rides into a trusted restart and turns the
  ring on.
- B-L1: restoring a 64DD disk save backup reads ~150 MB onto the heap; an out-of-memory error crashes mid-restore.
- B-L2: audit 3's p1 L6 zip/7z cap covers RomFiles.kt only; GameLauncher's unpack has none.
- D-L2: the Turbo popup crashes when every turbo button is Tap to hold (`first { }`, ControlsExtrasPopups.kt:105).
- E1-L3: two own cheats with the same name in a file crash the Cheats page (duplicate LazyColumn key).
- F-L1: after a 404, writing the empty wanted list on a full disk throws out of `resumed()`'s IO coroutine.
- C-L1 / A-L1 / E2-L2: "on its way" shows when nothing was queued; Restart now says auto slot but writes recovery.

## Cross-checks with host.md

- JNI: part A matched all 69 Kotlin `external fun`s to the native names, arguments and threads; NativeBridge's new
  setTransferPak matches MainActivity's call.
- host M1 (junk uploads fill "enough", then 410 forever): the client side is C-M2 above.
- host M2 (ring never deleted, DataMover carries it): the same as C-M1 / C-M4 above.
- E2-H1 (joiner save overwritten) is native and core: for host.md.

## Audit 3 fixes

Every part checked the audit 3 fixes in its area and found them in place, except B-L2 (p1 L6's archive cap covers
RomFiles only) and A-L3 (p1 L8 moved the driver stamp off the main thread, not the copy).
