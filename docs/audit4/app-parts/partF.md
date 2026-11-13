# Audit 4 app part F: Data files

Base: joey64-launcher cca5848 (launcher/phase0). joey64-data read from its `release` branch (what the app downloads),
taken out with `git archive release` into the scratchpad; neither repo's state was touched. Checks were run with
throwaway scripts (`python -I`): scratchpad `audit4\checkF.py`, `checkF2.py`, `checkF3.py`.

No High finding: every shipped and downloaded file parses with the app's own rules, and no parser can crash the app on
a malformed file except the one rare path in L1.

## Findings

### M1 Auto pak gives no Rumble Pak to common No-Intro copies of Rumble Pak games
- Where: app/src/main/assets/pakdb.tsv (built by devtools/make-pakdb.py); read by controls/ControlsStore.kt:115-129 (`PakDb`)
- What happens: Auto pak puts in a Rumble Pak only when the ROM's exact MD5 is in pakdb.tsv. The list comes from
  mupen64plus.ini, whose MD5s are GoodN64 dumps. Several No-Intro copies (the dumps the library and RetroAchievements
  hash) are not in the ini, so they get a Controller Pak. A player whose library holds those copies gets no rumble with
  Pak on Auto (the default), while the other regions of the same game do rumble. Examples checked by MD5:
  - Paper Mario (Europe) (En,Fr,De,Es), No-Intro 3b5c99f5e7dba06bf8237e58f6d4196b: missing. pakdb has the ini's
    (E) (M4) dump a9be6a493a680642d840f859a65816ca instead, which is not in the No-Intro table.
  - Fighters Destiny (France) 02689be7...: missing, while (USA), (Europe) and (Germany) are in.
  - 40 Winks (Europe) (Piko Interactive) b470b6bb...: missing, while (USA) is in.
  - GoldenEye 007 (Japan) (Switch Online) d41f3ef8...: missing, while (USA, Europe) (Switch Online) is in.
  - Wii Virtual Console copies (Mario Party 2 x3, Mario Tennis x2, Mario Tennis 64) and the LodgeNet copies of 15
    games (Star Fox 64, Super Smash Bros., OoT, MM, Kirby 64, Yoshi's Story, Paper Mario...) are all missing too.
  25 No-Intro games have release copies that disagree. Some of those are right on purpose (Super Mario 64 and Wave
  Race 64 before Shindou have no rumble; Top Gear Rally (USA) and Hybrid Heaven (USA/Europe) save only to a
  Controller Pak, which make-pakdb.py gives the Controller Pak on purpose).
- Evidence: `if (md5 != null && md5.lowercase() in rumbleGames) Pak.Rumble else Pak.Controller` (ControlsStore.kt:128);
  ini: `A9BE6A493A680642D840F859A65816CA Paper Mario (E) (M4) [!] Rumble=Yes`; n64-names.tsv:
  `3b5c99f5e7dba06bf8237e58f6d4196b	Paper Mario (Europe) (En,Fr,De,Es)`, not in pakdb.tsv. checkF2.py lists all 25.
- Fix: in make-pakdb.py also write each No-Intro MD5 (n64-names.tsv) whose game, by header game code or short title,
  has a Rumble=Yes copy in the ini and isn't a Controller-Pak-save game. Or add a game-code fallback (`@NMQ`) as
  n64-players.tsv has.

### L1 A failed write of the empty wanted list crashes the launcher
- Where: staterequest/StateRequests.kt:214-216, called from launcher/LibraryModel.kt:633-640
- What happens: when the wanted list answers 404, `wanted()` writes an empty file inside the catch block. If that write
  fails (storage full), the IOException leaves `wanted()`. The caller is `viewModelScope.launch(Dispatchers.IO)` in
  `LibraryModel.resumed()`, which catches nothing, so the launcher process dies on its next return from a game. It
  needs both a 404 (the file was taken off the release branch) and a full disk, so it is rare.
- Evidence: `} catch (e: FileNotFoundException) { ... f.parentFile?.mkdirs(); f.writeText("") }`;
  `viewModelScope.launch(Dispatchers.IO) { ... StateRequests.wanted(app) ... StateRequests.check(app, playedMd5) }`
  with no try.
- Fix: wrap the `writeText("")` in its own try/catch(IOException), and catch Exception around the state-request block
  in `resumed()` as the box-art run does.

### L2 The cover index and the wanted list are kept without a check, and pictures aren't checked against their MD5
- Where: launcher/boxart/JoeyCovers.kt:23-37 and 40; staterequest/StateRequests.kt:205-222; launcher/boxart/BoxArt.kt:154-160
- What happens: both downloads are written as the kept copy before anything parses them. A 200 reply that isn't the
  file (a proxy's page, a half-published branch) is then used for a week. The parsers handle it safely: the cover index
  gives no lines, the wanted list gives `{}`. But for that week none of Joey64's own covers are fetched and nobody is
  asked for a state. The cheat index already does this right: it parses before it keeps. Also, `index.tsv` records each
  picture's MD5, but the downloaded picture is never compared with it. Any bytes that decode are kept, and the row is
  marked `joey64:<recorded md5>`, so a wrong picture is never fetched again until the index changes.
- Evidence: `val text = String(get(base() + INDEX), Charsets.UTF_8) ... part.writeText(text) ... part.renameTo(f)` and
  the same in `wanted()`; `keep(covers.picture(row.md5), file(row.md5)); db.setArt(row.gameId, ArtKind.Downloaded.db, want, now)`.
  The release files are fine today: all 11 pictures match their recorded MD5, and wanted.json has 339 valid games.
- Fix: keep a new index or wanted list only when it parses to at least one entry (or is empty on purpose), else keep
  the old one. Compare the picture's MD5 with the index before `keep`.

### L3 A changed cheat index deletes every kept list, so games started offline lose their widescreen and 60 fps codes
- Where: cheats/CheatLists.kt:135-137; MainActivity.kt:731-732
- What happens: when the weekly index differs in any byte, every source folder in `files/cheatlists/` is deleted. Only
  the current game's lists come back at once. The others come back with the scan's prefetch, and only when Download
  cheats is on and the network stays up. The game screen reads the lists with `download = false`. So a game started
  before its lists are back runs without the Widescreen or 60 fps code the player picked, with only the log line
  "lists not downloaded". This happens after every joey64-data release that changes the index.
- Evidence: `val changed = keptAt == 0L || kept.readText() != text`, then
  `if (changed) dir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }`;
  `sourcesForGame(keys, download = false) ?: return ExtrasOn.NONE.copy(log = "lists not downloaded")`.
- Fix: delete only the lists of games whose entry in the index changed, or keep the old lists until their new copies
  are downloaded.

### L4 The shipped block list is copied on the main thread, and on GPUs that never use it
- Where: MainActivity.kt:753-765, called at 1424-1426 from `launchGame` (main thread, "On the main thread, with a surface")
- What happens: on a game's first start, `launchGame` reads the ROM header again (`headerKeys`) and inflates and writes
  the shipped list (up to 343 KB, A4BF9306-BF0CDFD1-45.txt) on the UI thread. That's a few milliseconds, not an ANR,
  but it is file work that audit 2 moved off the main thread into `prepareGame`. The core reads the list only with the
  compile thread (Mali, `GpuRules`: `if (mali) add(Setting("N64_DRC_THREAD", "1", ...))`, and `blk_init` returns
  early when `drc_thread_on() <= 0`). So on Adreno every game's copy, up to 9.3 MB in all, sits unused in
  `files/blocklists/`. If the rename fails, the `.part` file is left behind for good.
- Evidence: `assets.open("blocklists/$key.txt").use { i -> part.outputStream().use { o -> i.copyTo(o) } }`;
  `if (part.renameTo(to)) JLog.i(...)` with no delete on failure.
- Fix: copy in `prepareGame` (game I/O thread), only when the compile thread will be on, and delete the part file when
  the rename fails.

### L5 The game list still fetches the wanted list after "Ask for save states" is Off
- Where: staterequest/StateRequests.kt:152-153; launcher/LibraryModel.kt:638-639
- What happens: `resumed()` guards the first `wanted()` call with `asking(app)`, but then calls `check(app, playedMd5)`
  for every play, and `check` calls `wanted(ctx)` first. With asking Off, the app still asks GitHub for the list once a
  week after a play, and `decide` throws the answer away (`prefs.never`). There's no user data in the request, but it
  is network use the setting says has stopped.
- Evidence: `if (StateRequests.asking(app)) StateRequests.wanted(app)` then, unguarded,
  `if (playedMd5 != null) _askState.value = StateRequests.check(app, playedMd5)`; `fun check(...) { val wanted = wanted(ctx) ...`.
- Fix: return null at the top of `check` when `prefsOf(ctx).never`.

### L6 fixes.tsv gives Wave Race 64 (USA) (LodgeNet) the Shindou Edition's game
- Where: app/src/main/assets/boxart/fixes.tsv:1108 (generated block)
- What happens: every other USA, Europe and Japan copy of Wave Race 64 points at ScreenScraper game 5439. The LodgeNet
  copy points at 431627, the game line 1106 uses for the Japanese Shindou Edition, with region `ss`. So it probably
  shows the Shindou box. The other split ids (Bomberman 64 Japan, Pokemon Stadium Japan, SM64 Shindou, OoT GameCube) are
  real separate releases.
- Evidence: `ff67df97...	431627	jp	Wave Race 64 - Kawasaki Jet Ski (Japan) (Rev 2) (Shindou Edition)` and
  `365ac892...	431627	ss	Wave Race 64 - Kawasaki Jet Ski (USA) (LodgeNet)`; checkF3.py.
- Fix: check that cover; if it's Shindou's, add a hand-checked line `365ac892b951eed7b227f4a200b7ea53	5439	us` above
  the generated block.

### L7 gamedb/games.json breaks its own rules in a few places (contradicting entries)
- Where: gamedb/games.json (the app doesn't read it; it's the seed for the M3 profile DB)
- What happens:
  - Four 64DD disks have two entries each (Paint Studio beta, Talent Studio, Polygon Studio, Communication Kit). The two
    Paint Studio beta entries contradict each other: one is `"status": "parked", "devices": "Duo"` (boots with IPL_DEV,
    draws one frame), the other `"status": "open", "devices": "all"` (shuts down with only IPL_JPN).
  - Six `issues` entries have `"status": "fixed"` (Pokemon Snap, Blast Corps, Sin and Punishment, Mario Kart 64, Star
    Road Deluxe, SM64 Elise). The README allows only `open` or `parked`; fixed problems go in `fixed`.
  - Two issues have no `devices` (Cruis'n USA bench state, Super Smash Bros. CountPerOp), and Featherlight has neither
    `id` nor `disk`, so nothing can match it.
- Evidence: checkF.py output under "gamedb/games.json". The code lists match gamedb both ways: kSyncRdpGames,
  kSmootherGames, ExpansionPak.NEEDED_BY, GameCrop (CZL, NZL), GpuRules GLIDEN_GAMES.
- Fix: merge each disk's two entries, move the six to `fixed`, and add the missing `devices`. Give Featherlight a
  `crc` (its header CRCs) as the matching key.

## Checked and fine
- n64-names.tsv: 1,147 lines, all `md5<TAB>name`, lower-case MD5, no duplicates; `BoxArtTable.parse` takes every line (ScreenScraperPickTest.shippedTable).
- pakdb.tsv: 973 MD5s, all 32 lower-case hex, no duplicates, none skipped by `PakDb.load`; a read failure gives an empty set, logged, no crash.
- n64-players.tsv: 2,382 keys (CRC32 upper-case hex as RomReader writes `%08X`, and `@` + 3-character codes), no duplicates, none skipped. Values 1-4 plus 8 (nine 8-player games, Micro Machines and the like): the 4-players filter uses `>= 4` and `netPlaces` clamps to 2-4, so 8 is handled.
- fixes.tsv: 1,180 lines, no duplicate or conflicting MD5s, no line the parser drops (ScreenScraperPickTest.shippedFixes counts that), regions us/eu/jp/ss/wor all pass `[a-z]{2,3}`; 6 of Joey64's own covers also have a fixes line (the original game's cover), and `joeyCovers` runs first and replaces that cover if a lookup got there first.
- Blocklists: 78 files named `CRC1-CRC2-CC` exactly as `CheatLogic.keyOf` formats them (`%08X-%08X-%02X`); every line is `addr len sum` in hex, addresses in RDRAM, no page-0 entries (the core drops them anyway), lengths 4 to 0x4000 (the core's `MAXBLOCK*4`), at most 15,801 lines (under `BLK_MAX` 32768). The working tree has CRLF (autocrlf; the blobs are LF), and the core's `fscanf("%x %x %x")` skips the `\r`, so APKs built on Windows read the same.
- Shipped block list is keyed by the header and checked by the core against the code in RAM (a list from another copy only compiles the blocks that match); a 64DD disk has no key and gets none.
- Bundled drivers: bundled.json's two `file` names match `TURNIP` / `TURNIP_FAUX123` and the asset paths; both md5s match the files in assets/drivers (61baaeaf..., 46e1676b...); mesa.txt names both releases; `BundledDrivers.shownName` returns null on bad JSON (BundledDriversTest); the release build checks the md5s (`checkBundledFiles`).
- Cheat index (release dist/index.json): 903 keys, all `CRC1-CRC2-CC` or `CRC1-CRC2`; every game has `sources` with int counts; all source names pass `SOURCE_NAME` and are in `CheatLogic.SOURCES`; every listed file exists and no file is unlisted.
- Cheat lists: all 1,510 parse under `parseList`'s rules (name, codes array of strings, options as string pairs). Every code is `XXXXXXXX YYYY` (hex or `?`). Every `?` code has options or `needs_value`, and every option value is hex. No empty names, no cheats without codes.
- Cheat download handling: the index is parsed before it is kept, a kept index that won't parse is dropped (audit 3 p2 L2 fix is right), a list that won't parse is deleted in `forGame`, and replies are capped at 8 MB.
- wanted.json (release): 339 games, unique lower-case MD5s, every kind `gameplay`, every `need` and `title` set; `parseWanted` handles "", non-JSON and a missing `games` (StateRequestsTest). 40 MD5s are hacks/homebrew not in the No-Intro table, which is expected.
- boxart/index.tsv (release): 11 lines, both MD5s valid, each picture there and matching its recorded MD5. The blobs are LF, and the parser trims a `\r` anyway. Three PNGs have an alpha channel; only Super Mario Land 64's has partly see-through pixels (1.2%, none clear), which the JPEG re-encode flattens.
- Meta.json ring parse (`parseRing`): bad JSON gives null, paths with `..` are dropped; it's the host's own file.
- `ringFor` and `GameLauncher.intentFor` run on IO (LibraryModel 975/1004/1213/1229); `PakDb.load` in ControlsScreen runs in a LaunchedEffect on IO with a catch.
- gamedb code lists: kSyncRdpGames (7), kSmootherGames (4), NEEDED_BY (3), GameCrop (CZL, NZL) and GLIDEN_GAMES (Last Impact) each have their gamedb entry, and gamedb names no list entry the code lacks.
