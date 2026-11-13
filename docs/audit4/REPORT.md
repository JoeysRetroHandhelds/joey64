# Audit 4: combined report

Base: cca5848 (main merged into launcher/phase0; main fast-forwarded to it). The audit was split between the two
sessions, as Joey said:
- app.md (Kotlin, UI, data files): seven parts, with their evidence in app-parts/.
- host.md (native host, bundled core, both Workers, release config).
- The JNI contract was checked from both sides: all 69 `external fun`s match.

Nothing is fixed. Every item waits for Joey's answer.

Totals, with the duplicates merged:

| | High | Medium | Low |
|---|---:|---:|---:|
| App (app.md) | 3 | 20 | 49 |
| Host (host.md) | 1 | 1 | 5 |
| Total | 4 | 21 | 54 |

Two host items are counted with the app's instead:
- host M2 (the state ring) is the same as app C-M1 / C-M4.
- host L5 is closed (rel3 is bundled).

How each was checked: **Thor** = reproduced on the Thor; **Read** = the code path read and confirmed by a second
reader; **Agent** = the part agent's quoted evidence only.

## High: data loss or a crash

| # | What happens | Where | Checked | Fix idea |
|---|---|---|---|---|
| 1 | **Joining a netplay room replaces your own save with the host's.** You join a friend's room; afterwards your save for that game is theirs. Only a save backup (if on) has yours. | host H1: `libretro_host.cpp:1002-1035`, core `main.c` open_*_file | Read (host and core); not yet run on a device | A joiner never writes save RAM back during a room session (a scratch save path) |
| 2 | **Another app can end the running game.** A bare start of the game screen while you play shuts the core down: the game closes with no quit save. | app A-H1: `MainActivity.kt:120` | Thor | With a game running and no ROM in the new start, leave the game alone |
| 3 | **A blank save can replace the real one in a picked data folder.** If the folder can't be read at game start, the game makes a new empty save, and that save is later pushed over the real one. | app B-H1: `DataFolders.kt:132-152` | Read | Tell "folder unreachable" from "no save yet"; never push over a copy this device never pulled |
| 4 | **Crop edges crashes once an Exact amount is saved.** Opening the popup crashes every time for 2, 6, 10, 14 or 18-32 lines. | app D-H1: `CropPopups.kt:70` | Read | `firstOrNull`, falling back to "Exact, n lines" |

## Medium

State requests (all new since audit 3):
- **Junk uploads can use up a game's places for good.** The server checks only the RZIP magic; once a game has
  "enough", every real app is told "have enough" and never asks again. (host M1, Worker side; app C-M2, app side:
  "sent" is marked on queue, and a 410 marks it forever.) Fix idea: the Worker checks the state is really this
  game's, and the app clears "enough" when the wanted list changes.
- **The state ring (84 MB a game) is never deleted, and a data folder move carries it into your folder.**
  (host M2 = app C-M1 / C-M4.) Fix idea: delete it after the popup is answered, and keep it out of `states/`.
- **The outbox cap deletes the newest Yes.** (app C-M3, Read) It shows "on its way" for a state it has just deleted.
- **On narrow screens the save-state ask never shows "What's sent".** (app E1-M5, Agent)

Game screen and saves:
- **Switching games deletes the recovery state before the new ROM is checked.** (app A-M1, Read)
- **A full disk crashes every start**, the launcher included. (app A-M2, Read)
- **A data folder move releases the old folder even when files were left there.** (app B-M2, Agent)
- **Picking a folder that already has a settings copy overwrites it with this device's settings** (the defaults on a
  first run). (app B-M3, Read)
- **The Transfer Pak's Game Boy save loses progress when you switch it away and back.** (app D-M2, Agent)

Netplay:
- **"Restart to use the new settings?" restarts inside a room** and breaks it for everyone. (app E2-M1, Read)
- **A netplay start that fails says nothing**, and the others wait 30 s. (app E2-M2, Agent)
- **Room cards and Host a room's covers have no TalkBack label.** (app E2-M3, Agent)

UI and settings:
- **Crop edges' side panel says Off for a game whose default is Normal.** (app D-M1, Thor)
- **Renaming or deleting a macro leaves a dead touch button.** (app D-M3, Agent)
- **Leaving the Library tab while a ROM folder is "Looking…" drops it silently**, and the 64DD BIOS import too.
  (app E1-M1, Read)
- **Tidy names is greyed with Names Off but still changes titles.** (app E1-M2, Agent)
- **Emulation's Reset tab says "every row" but leaves the six Speed rows.** (app E1-M3, Agent)
- **"Why it didn't start" cuts its reason to one line.** (app E1-M4, Agent)

Data:
- **Auto pak gives a Controller Pak to No-Intro copies of Rumble Pak games** (Paper Mario (Europe), GoldenEye 007
  (Japan) and others). (app F-M1, Agent)

## Low (54)

One line each, in app-parts/part*.md (A-L1..L4, B-L1..L7, C-L1..L8, D-L1..L5, E1-L1..L9, E2-L1..L9, F-L1..L7) and
host.md (L1-L4, L6). The ones most worth naming:
- **Two small crashes:**
  - the Turbo popup crashes when every turbo button is Tap to hold (D-L2);
  - two own cheats with the same name crash the Cheats page (E1-L3).
- Restoring a 64DD disk save backup reads ~150 MB into memory and can crash mid-restore (B-L1).
- Eight idle connections fill a public netplay room (host L1).
- A game jumping to a bad address mid-play shows "won't start", though its save is written (host L2).
- Another app's flag can turn the state ring on through a restart (A-L2).
- Audit 3 leftovers:
  - the zip/7z size cap covers RomFiles only, not GameLauncher's unpack (B-L2);
  - the driver copy is still on the main thread (A-L3).

## Audit 3 fixes

Every part checked the audit 3 fixes in its area. All are in place except the two leftovers above (B-L2, A-L3).

## Joey's answers needed

1. Which to fix before the tester build? Suggested:
   - all 4 High;
   - the state-request Mediums (they touch the live server and players' storage);
   - A-M2 (the full disk crash);
   - E2-M1 (the netplay restart);
   - the two small crashes (D-L2, E1-L3).
   The rest could go on the queue after the build.
2. **Netplay save (High 1):** should a joiner's save be untouched by a room (my suggestion), or kept as a separate
   "netplay save" you can look at afterwards?
3. **Junk uploads (host M1):** check the state in the Worker (inflate it and compare the ROM MD5 inside), or also
   lower "enough" counts until real testers have sent some?
4. **Wording**, in your words, for two new lines:
   - a game that stopped mid-play (host L2), instead of "won't start";
   - what "Restart to use the new settings?" shows in a netplay room (E2-M1): only Later with "Not in a netplay
     room", or nothing until the next start.
