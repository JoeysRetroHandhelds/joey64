# Audit 3, part 1: game start/quit, saves, states, backups, cheats, logs (Kotlin)

Scope: `app/src/main/java/com/joeysretro/joey64/*.kt` (MainActivity, NativeBridge, GameOverlay, StatsOverlay, LogFiles,
RomFiles, RestartActivity, RecentlyPlayed, Controllers, GpuRules), `states/`, `saves/`, `audio/`, `emulation/`,
`onscreen/`, `cheats/`, plus the storage code the brief weights (`launcher/storage/DataFolders.kt` (SrmSync),
`DataMover.kt`, `DataFolderMove.kt`, `FileCopy.kt`, `TreeSpace.kt`, `FileSpace.kt`) and the native `stop` /
`runStateRequest` in `libretro_host.cpp` to follow the call paths. Commit df01a53. Nothing was changed.

Totals: High 2, Medium 4, Low 9.

---

## High

### H1. Changing the data folder can overwrite the game's current save with an older one of the same name
- Files: `launcher/storage/DataFolderMove.kt:28-35`, `launcher/storage/DataMover.kt:72-76`, `launcher/storage/DataFolders.kt:28-31, 121-141` (`SrmSync.pullFile`), `launcher/screens/SavesTab.kt:197-205`.
- What happens: `DataMover.move` keeps a file in the old place when the new folder already has a *different* file with
  that name (`conflicts += shown`, "both kept"). `DataFolderMove.to` then switches to the new folder anyway
  (`DataFolders.set(context, tree)`), whatever the result was. `DataFolders.set` calls `SrmSync.forget`, which clears every stamp.
  On the next start of that game, `pullFile` finds a working copy with `rec = -1`. Both "in step" checks need `rec >= 0`,
  so it falls through to `openRead` of the new folder's copy and renames it over `files/saves/<name>.srm`.
- How a user gets there: the user has played a game on Joey64's own storage, then picks a folder that already holds
  `saves/<same name>.srm`. Examples are a RetroArch folder (RetroArch's default saves are `saves/<rom>.srm`, with the same names), a folder used
  on another device, or a card used before. The move reports "Moved · N files left behind". The next Play loads the
  folder's older save and the core writes over the copy that was left behind. The latest session's progress is gone. Save
  backups don't cover it: `SaveBackups` runs after the pull, so it backs up the folder's old copy. The same happens on a
  picked → picked move, and there `RomFolders.release` also drops the grant on the old folder.
- Evidence: `DataFolderMove.kt` sets the folder unconditionally after `DataMover.move`. `SavesTab.moveData` only shows
  the count. `pullFile`: `if (f.isFile && rec >= 0 && ...)` guards both skip branches, then `part.renameTo(f)`.
- Fix: don't switch when a Saves-kind file conflicts; ask the user which copy to keep. Or, after a move, mark each
  conflicting working copy as "newer" (keep a stamp that differs from its mtime) so the pull pushes it, or at least
  backs both up, instead of overwriting it.

### H2. Every second game in a process unpacks its ROM on the main thread, and a failure there crashes the app
- File: `MainActivity.kt:98` → `afterLauncherSaved` → `switchGame` `:2342-2369`, `resolveRom()` at `:2352` → `copyToCache` `:1213`, `displayName` `:1199`.
- What happens: `gameLoaded` is process-wide and never cleared. So after the first game, every Play from the library
  (the same process) and every frontend hand-over goes through `switchGame`. `switchGame` calls `resolveRom()` on the
  main thread, which copies the whole ROM (up to 64 MB, or a 64.9 MB disk) through SAF or the FileProvider and unzips
  or un-7zs it. Code audit 2 moved exactly this work to `gameIo` in `prepareGame`, but this path was missed.
  `prepareGame` also wraps it in `try/catch (Exception)`; `switchGame` has no catch. A `FileNotFoundException` or
  `SecurityException` from `openInputStream` or the `displayName` query (a ROM on a removed SD card, a stale frontend
  URI), or an `IOException` from a full cache, is uncaught on the main thread and crashes the process. The first game
  in a process shows "won't start" for the same input.
- Fix: run `resolveRom` on `gameIo` inside the same try/catch as `prepareGame`, then call `RestartActivity.restart`
  from `runOnUiThread`. On failure, call `notStarted(...)`.

---

## Medium

### M1. The whole ROM is MD5-hashed on the main thread at game start (and again when in-game Controls closes)
- File: `MainActivity.kt:2486` (`applyControls`, called from `launchGame` on the main thread), `:2566` (`reloadControls`), `:869-875` (`earlyMd5` from `setupGpu` in `onCreate`), `:1501` (`startSession`), `:2505` (`romMd5`, up to `RomReader.MAX_HASHED` = 64 MB).
- What happens: when the intent has no `EXTRA_MD5` (every frontend start, and every game that `switchGame` restarts
  from a frontend URI), the default Pak (Auto) makes `PakDb.auto` evaluate the lazy `md5`. That reads and hashes up to
  64 MB on the main thread after the surface is up. `reloadControls` does it again each time the pause menu's Controls
  closes. `earlyMd5` hashes a handed-over ROM in `onCreate`. With reports on, `startSession` hashes it a second time.
  On A55-class handhelds this is hundreds of ms to over a second of frozen UI per start.
- Fix: hash once in `prepareGame` (it's on `gameIo`), carry the result in `Prepared`, and reuse it in `applyControls`,
  `reloadControls`, `startSession` and the trial's `gameRenderer`.

### M2. The dev Vulkan driver folder is honoured in release builds (native code loaded from shared storage)
- File: `MainActivity.kt:544` (`val dev = devDriver()`, no `devBuild` check), `:1092-1101`.
- What happens: any `.so` in `Android/data/com.joeysretro.joey64/files/drivers/` (or `files/drivers/`) is copied to
  `gpu_driver/dev/` and loaded with libadrenotools in every build. CLAUDE.md lists it as a dev feature. On Android 10
  (minSdk 29), apps with storage permission can still write other apps' `Android/data` folders, so another app could get
  native code run inside Joey64. On any version, a stray file there silently replaces the driver and turns off Auto's
  trial (`autoOnly` false). This is dev-only code that ships in release (brief item 6).
- Fix: gate `devDriver()` on `devBuild`, or read only internal `files/drivers/` in release.

### M3. A picked data folder's cheats file is read over SAF on the main thread at every start
- File: `MainActivity.kt:1300` → `applyCheats` `:1190-1197` → `GameCheats(this, saveName).read()` (`cheats/GameCheats.kt:259`).
- What happens: `launchGame` runs on the main thread. `GameCheats` builds a `TreeSpace` for the picked folder and runs a
  provider query to list `cheats/` plus an open and read. On an SD-card folder with many files this is a main-thread
  provider round trip at every start. It is the kind of file work code audit 2 moved to `prepareGame`.
- Fix: read the cheat lines in `prepareGame` and carry them in `Prepared`, as `extras` already are.

### M4. A failed quit save still deletes the oldest kept quit save and renames the rest
- File: `states/GameStates.kt:148-156` (`saveQuit`), called from `MainActivity.quitToLibrary` `:2292` and `restartForSettings` `:834`.
- What happens: with Quit saves to keep > 1, `saveQuit` first deletes Auto k..Auto 10, then shifts Auto → Auto 2 → ...,
  and only then takes the state. If `NativeBridge.saveState` fails, it moves only Auto 2 back to Auto. The oldest kept
  quit save is already gone and the others keep their shifted names. `runStateRequest` fails at once in a netplay room
  that is waiting (`g_netplay && (g_framesRun == 0 || g_netplayWaitingFor != 0)`) and after the 10 s timeout. So a quit
  while a room waits, or with a stuck emulation thread, costs a quit save every time. A Restart now for settings also
  rotates (and evicts) a real quit save each time.
- Fix: take the state first (`NativeBridge.saveState` + wrap), and rotate only once there is data to write.

---

## Low

- **L1. Crafted or damaged RZIP state crashes the state thread**: `states/StateFormat.kt:129-133`. Here `n = le32(...).toInt()`
  near `Int.MAX_VALUE` makes `at + n` overflow to a negative number, so `at + n > bytes.size` passes. `inflater.setInput`
  then throws `ArrayIndexOutOfBoundsException`, and only `DataFormatException` is caught. Path: `GameStates.loadBytes` on
  `joey64-states` (also the launcher's import). Fix: compare as Long (`at.toLong() + n > bytes.size`) and catch
  `RuntimeException`.
- **L2. Cheat source names from the network index aren't checked**: `cheats/CheatLists.kt:100, 142, 146-147`.
  `File(dir, "$src/$key.json")`, with `src` taken from `index.json`'s `sources` keys, is written by `writeWhole`. A `../`
  in a source name writes outside `files/cheatlists/`. It needs the joey64-data repo or the transport to be compromised.
  Fix: accept only `[A-Za-z0-9_-]+` source names in `parseIndex`. Downloads also have no size cap (`readBytes`).
- **L3. Cheats file replace isn't atomic**: `cheats/GameCheats.kt:84-85` deletes `<save>.json` before renaming the
  `.part`. A kill or rename failure in between loses the user's cheats, and `read()` doesn't fall back to `.part`. Fix: use
  `FileCopy.writeChecked` or GameStates' `.old` swap.
- **L4. Session logs carry other players' addresses and personal paths**: `LogFiles.kt:146-150` logs every intent extra
  with its value. That includes `NETPLAY_JOIN` ("host:port:player", another player's public IP), `NETPLAY_NAME`, and
  `SOURCE_URI` / data URIs that show folder names. These logs are what Share logs sends. Fix: log extra keys only, or
  mask the netplay host and the URI paths.
- **L5. Release builds accept a save name from other apps**: `MainActivity.kt:85-93`. MainActivity is exported, and
  `EXTRA_ROM_PATH` is only checked to be under `cacheDir`. Another app can pass `cache/current.rom` (the last unpacked
  ROM) with any `EXTRA_SAVE_NAME`, so one game's save RAM, auto state and quit saves get written under another game's
  name. It needs a malicious installed app. Fix: also require a token only Joey64 sets (for example a nonce stored in
  prefs by `switchGame` / `restartForSettings`).
- **L6. ROM unpacking has no size or memory limit**: `RomFiles.kt:55-115`. A huge "ROM" handed over by VIEW fills
  `cacheDir`. `SevenZFile` is built without a memory limit, so a crafted 7z can throw `OutOfMemoryError` on `gameIo`;
  that's an `Error`, not caught by `prepareGame`'s `catch (Exception)`, so the process dies. Fix: cap the output at
  ~128 MB and set `setMaxMemoryLimitKb`.
- **L7. Back pressed while the game is loading still loads the game and counts a failed GPU trial**: `MainActivity.kt:1242-1247`
  checks only `destroyed`, not `isFinishing`, so a finishing screen still runs `launchGame`. That calls `trial.begin()`
  and `loadGame`, and `onDestroy` then stops the game before any frame, so `trial.cancel()` is never called. Quitting a
  netplay room before its first frame does the same. On an install that has never drawn a frame, two of these step Auto
  down to the system driver and then GLideN64. Fix: skip `launchGame` when `isFinishing`, and `trial?.cancel()` in
  `onDestroy` when no frame was drawn and the user quit.
- **L8. The bundled driver's stamp is written even when the rename failed**: `MainActivity.kt:1113-1114`
  (`tmp.renameTo(dst)` result ignored, then the stamp is written). A failed rename leaves an old or missing `dst`
  stamped as current. The copy (tens of MB) also runs on the main thread in `onCreate` once per update.
- **L9. Disk saves can be pushed torn**: `SrmSync.pushFile` (`DataFolders.kt:148-160`) streams `.ndd.disk_save` every
  30 s while the core may be writing it in place. The checksum is taken from the bytes read, so a torn copy passes the
  check and sits in the data folder until the next push. The working copy wins at the next pull, so this is low. Fix:
  push disk saves only while paused or on exit, or compare mtime before and after the copy.

---

## Checked and found fine
- Start path: `surfaceCreated` → `prepareGame` on `joey64-gameio` (try/catch, `cartFailed` volatile, `destroyed` check) → `launchGame`; surface lost while preparing (`prepared` kept, launched on the next surface).
- `notStarted` / `shutdownWatch` close the screen on a failed start, a core SHUTDOWN or netplay ends; `trial.cancel()` there.
- onDestroy: `NativeBridge.stop()` joins the emulation thread before the srm push is queued on `gameIo`; requests after `stop` return at once (`runStateRequest` checks `g_running`); a double `stop` is a no-op.
- `SrmSync` `pull`/`push`/`pushAll` are `@Synchronized`, so the state thread's 30 s `srmTick` and onDestroy's `gameIo` push can't interleave; a process killed by `RestartActivity` before the push is fixed by the next pull ("working copy is newer, pushing it first").
- `SrmSync.pushFile` / `FileCopy.writeChecked`: `.joey64-part` read back and CRC-checked, old file moved aside and put back on failure.
- Quit to library: quit save, recovery clear and recently played all on the state thread, then `finish` / `finishAffinity`; `startLoadFailed` keeps the auto slot after a failed Continue load.
- `restartForSettings`: auto slot written before `stop` and the process restart; `LOAD_STATE` only when it was written.
- `GameStates.writeWhole` (`.part`, `.old`, put back on failure) and `readWhole` falling back to `.old`; recovery state via `.part` + rename.
- Undo load/save keep in-memory copies (`@Volatile`), state thread only.
- `StateFormat.read`: RASTATE block sizes checked against the buffer as Long; `unrzip` total capped at 64 MB.
- `SaveBackups`: written through `.part`, `.nomedia`, keep count, unchanged saves skipped (byte compare; disk zip CRC/size in the extra field); `DiskZip.write` detects a save that changed while zipping.
- `SaveManager.restore`/`delete`: back up first, drop the working copy and its stamps on a picked folder.
- `AppBackup.restore`: zip slip blocked (`[^/\\]+`, no `.`/`..`, fixed depth), data entries written checked, settings files via `.part`; RA sign-in not in backups.
- `SettingsMirror`: mutex, SHA-checked, `readsWhole` before bringing a copy in, written checked.
- `TreeSpace`: a failed listing isn't cached (no "name (1)" duplicates); `"wt"` truncate mode; exists check before rename.
- `DataMover`: a file leaves the old place only after a checked copy; same content counts as already there.
- `RestartActivity` not exported; FileProvider not exported; debug receivers registered only in debuggable builds and unregistered in onDestroy.
- Release builds ignore `--es env/opts/cheats/driver/renderer/rom` (`devString` / `devFlag`) and reject handed ROMs outside `cacheDir`; `cleanSaveName` strips `/`, `\` and `..`.
- `GameOverlay` / `StatsOverlay`: windows removed with `removeViewImmediate`, handler callbacks cleared; `RaPopups`/`RaCache` removed from `RaBridge.listeners` on stop; `uiScope` cancelled.
- `rumbleWatcher` ends within 1 s of `destroyed`; `SessionTracker` methods `@Synchronized` (UI and state threads).
- `setOptionsLive` used for every in-game core option change (Game settings, upscale hotkey, pak changes); `setOption` only before `loadGame`.
- Settings stores (audio, emulation, onscreen, saves) and `RecentlyPlayed` write via `.part` + rename; `CheatLists` writes via `.part` + rename and parses before keeping the index.
- `LogFiles`: no ScreenScraper or RA secrets in my files' log lines; no direct `android.util.Log` calls outside `JLog`'s fallback; 5 sessions kept.
- `RomFiles`: ROMs found by magic, `__MACOSX` skipped, `.part` + rename; archive entry names used only for the extension (disk) and the log.
