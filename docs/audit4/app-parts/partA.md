# Audit 4 app part A: Game screen

Branch launcher/phase0 at cca5848. Read only. Paths are under
C:\GithubProjects\joey64-launcher\app\src\main\java\com\joeysretro\joey64\ unless given in full.

## Findings

### H1 A start with no ROM while a game runs reloads the core under the running game
- Where: MainActivity.kt:120, 150, 170-172, 213; C:\GithubProjects\joey64-launcher\app\src\main\cpp\libretro_host.cpp:1872
- What happens: MainActivity is exported (AndroidManifest.xml:36) and has no single-instance launch mode, so every start makes a new instance. onCreate's only guard sends the start to switchGame only when it carries a ROM. Any start without one runs the full first-start setup in the same process while the old instance's game is still running. Such starts include another app's explicit start of `.MainActivity`, or a VIEW intent with one of the filter's MIME types but no data. That setup:
  - unsets the netplay env vars;
  - runs setupGpu, which loads and probes the Vulkan driver;
  - calls `NativeBridge.setRenderer` and `setOption`, which write `g_optionOverrides` from the main thread while the emulation thread reads that map with no lock (CLAUDE.md: "never `setOption` while a game runs");
  - calls `NativeBridge.loadCore`, which calls `core.deinit()` on the core the emulation thread is running.

  The likely result is a native crash mid-game, and the game is lost since the last save.
- Evidence:
  - MainActivity.kt:61 `private fun hasRom(i: Intent?) = i?.data != null || (devBuild && ...) || i?.getStringExtra(EXTRA_ROM_PATH) != null`
  - MainActivity.kt:120 `if (gameLoaded && hasRom(intent)) { switching = true; afterLauncherSaved { if (!isDestroyed) switchGame() }; return }`
  - MainActivity.kt:150 `for (k in NETPLAY_ENV) android.system.Os.unsetenv(k)`
  - MainActivity.kt:172 `NativeBridge.setRenderer(renderer == "parallel")`
  - MainActivity.kt:213 `if (!NativeBridge.loadCore(coreFile.absolutePath, sys.absolutePath, saves.absolutePath)) {`
  - libretro_host.cpp:1872 `if (g_coreInited) { LOGI("core: still up from a start that failed, shutting it down first"); core.deinit(); }`
  - Nothing between lines 120 and 213 checks `owner` or `gameLoaded` (`gameLoaded = true` is set only at 1442).
- Fix: right after line 120, add `if (gameLoaded) { owner?.let { bring it to front }; finish(); return }`. Then no second instance touches native state while a game is loaded.

### M1 Switching games stops the running game and drops its recovery state before checking the new ROM
- Where: MainActivity.kt:2487-2500
- What happens: another app hands the game screen a ROM mid-game, such as a frontend or file manager VIEW, or an `EXTRA_ROM_PATH` extra (hasRom accepts it from any app). switchGame then:
  1. stops the running game at once;
  2. finishes its screen;
  3. deletes that game's recovery state;
  4. only then tries to unpack the new ROM.

  No quit save is written for the old game. If the new file is unreadable or not an N64 ROM, the new screen just closes. A bad or wrong file from any app ends the player's game, and their progress since the last save is gone, recovery state included.
- Evidence:
  - MainActivity.kt:2487 `val closing = owner?.states?.saveName`
  - MainActivity.kt:2488 `owner?.let { NativeBridge.stop(); it.finish() }`
  - MainActivity.kt:2493 `closing?.let { GameStates.Recovery.clear(this, it) }`
  - MainActivity.kt:2494 `val got = try { resolveRom() } catch (e: Exception) { ...; null }`
  - then `got ?: run { JLog.e(TAG, "No N64 ROM in the new intent") ...` closes.
- Fix: resolve the new ROM first, with the old game paused. Stop the old game only when that works, and write its quit save (as Quit to library does) before clearing its recovery state. On failure, resume the old game.

### M2 A full disk crashes every app start in LogFiles.previousExits
- Where: LogFiles.kt:171-182, called from LogFiles.start in MainActivity.onCreate:119 and LauncherActivity:128
- What happens: on every process start, each exit Android recorded since the last start is written to files/logs on the main thread. `writeText` and the trace copy have no try. On a full disk (or any IOException) the exception goes out of onCreate and the app crashes. LAST_EXIT is saved only after the loop, so the same exits stay "fresh" and the next start crashes the same way. That crash adds yet another exit record. Joey64 can't open, the launcher included, until space is freed, and the user is never told why. The "Not enough space" screens never get a chance to show.
- Evidence:
  - LogFiles.kt:172 `File(dir, "$name.txt").writeText(describe(x) + "\n")`
  - LogFiles.kt:176 `trace.use { copy(it, File(dir, "$name.$ext")) }`
  - LogFiles.kt:182 `exits.maxOfOrNull { it.timestamp }?.let { if (it > seen) prefs.edit().putLong(LAST_EXIT, it).apply() }`
  - Only `getHistoricalProcessExitReasons` (line 166) and `traceInputStream` (173) are wrapped.
- Fix: wrap the body of each loop pass in `try { } catch (e: Exception) { JLog.e(...) }`, and save LAST_EXIT before the loop, so a failed write is never retried at every start.

### L1 Restart now and the Save picker's Auto cell say things the code doesn't do
- Where: launcher/ingame/PauseScreens.kt:250, 273; MainActivity.kt:882
- What happens:
  - The Restart row's line says the game is saved to the auto slot and starts from there. Since audit 3, restartForSettings writes the recovery state instead and leaves the auto slot alone. If that write fails, the game starts fresh. A player who reads the line and later picks Auto gets the older quit save, not the restart point.
  - The Save picker's Auto cell says "Quitting saves here" even when Settings › Saves › Save on quit is Off, when quitting saves nothing.
  - The KDoc at MainActivity.kt:882 still says "the auto slot is written".
- Evidence:
  - PauseScreens.kt:250 `detail = "Saves to the auto slot, then starts again from there") { actions.restartNow() }`
  - PauseScreens.kt:273 `append(" · " + if (picker.save && com.joeysretro.joey64.states.StateSlots.isAuto(sel.slot)) "Quitting saves here"`
  - MainActivity.kt:882 `* Restart now: the auto slot is written, then the game screen starts again in a fresh process`
  - CLAUDE.md: "Restart now (`restartForSettings`) writes the recovery state (since code audit 3: writing the auto slot shifted the kept quit saves)".
- Fix:
  - Change the Restart line to "Starts again from where you are now".
  - Show "Quitting saves here" only when Save on quit is On.
  - Fix the KDoc.

### L2 EXTRA_STATE_RING is not in LAUNCHER_EXTRAS, so another app's start can turn the state ring on
- Where: MainActivity.kt:1437, 3342-3345, 86-87
- What happens:
  - launchGame reads the ring flag through `launcherBool`, which is gated on fromJoey64. So a direct start from another app is ignored.
  - carriedExtras strips only the extras in LAUNCHER_EXTRAS when it carries an untrusted start's extras into the trusted restart. The restart goes through RestartActivity or the non-exported GAME_START alias, used by switchGame and restartForSettings.
  - EXTRA_STATE_RING is missing from the list, so an outside app's `STATE_RING=true` survives into a trusted start. The host then writes 5 states plus meta.json for that game to files/states/ring/<md5>/. This adds disk use only, but it gets around the gate the code meant to have.
  - Also, a Restart now from a library game carries the flag, and the ring starts over.
- Evidence:
  - MainActivity.kt:1437 `if (launcherBool(EXTRA_STATE_RING) && md5 != null) android.system.Os.setenv("N64_STATE_RING", stateRingDir(this, md5).path, true)`
  - MainActivity.kt:3342-3345 lists `EXTRA_ROM_PATH, EXTRA_SAVE_NAME, ... EXTRA_PLAY_NOTE, ...EXTRA_IS_DISK, ...EXTRA_NEEDS_CART`, with no EXTRA_STATE_RING.
  - MainActivity.kt:87 `Bundle(b).apply { if (!fromJoey64) LAUNCHER_EXTRAS.forEach { remove(it) } }`
- Fix: add EXTRA_STATE_RING to LAUNCHER_EXTRAS.

### L3 The bundled driver copy still runs on the main thread (audit 3 p1 L8 fixed only its stamp)
- Where: MainActivity.kt:1182-1192, reached from setupGpu in onCreate
- What happens:
  - The first game start after each install or update copies the bundled Turnip .so (several MB) from the APK on the main thread, before the surface exists.
  - On slow storage this is a visible stall, and with a slow disk it can come close to an ANR.
  - Audit 3 fixed the stamp. The copy itself is unchanged.
- Evidence:
  - MainActivity.kt:1186 `if (dst.exists() && stamp.exists() && stamp.readText() == version) return dst`
  - MainActivity.kt:1190 `assets.open("drivers/$name").use { i -> tmp.outputStream().use { i.copyTo(it) } }`
- Fix: copy the drivers once from the launcher after an update, off the main thread, or in prepareGame on gameIo before the driver is needed.

### L4 State-thread loads catch only IOException, so a SAF provider error crashes the game
- Where: states/GameStates.kt:227-229, 323-324
- What happens: `load()` runs on the `joey64-states` HandlerThread, from the load hotkey, the pause menu and a start's auto load. The read through `readWhole` is outside any try. With a picked data folder, `TreeSpace.openRead` goes to a ContentResolver. A revoked grant or a provider that has gone away throws SecurityException or IllegalArgumentException, not IOException. On a HandlerThread that kills the app mid-game. The write paths in the same file catch only `IOException` too (lines 70, 98, 134).
- Evidence:
  - GameStates.kt:229 `val bytes = readWhole(name) ?: return false.also { JLog.e(TAG, "no $name") }`
  - GameStates.kt:323-324 `private fun readWhole(name: String): ByteArray? = (space.openRead(path(name)) ?: space.openRead(path("$name.old")))?.use { it.readBytes() }`
- Fix: catch Exception (not only IOException) around the state thread's file work and show the existing "couldn't load / save" pop-up.

## Checked and fine
- JNI contract: all 69 `external fun`s in NativeBridge.kt match the `JNI(name)` functions in libretro_host.cpp by name, argument order and types. Nullable arguments (openLog, setSlang path, setLookImage rgba, loadState achv) are null-checked natively.
- `stateProgress` is thread-local natively and is always called right after `saveState` on the same state thread (GameStates).
- `setOption` is used only before loadGame on a first start. Live changes (Game settings, upscale hotkeys) go through `setOptionsLive`, moved into the map between frames.
- Upscale hotkeys are blocked in a netplay room (NetplayInGame.kt blockedLine), as are state, reset and rewind hotkeys.
- Cheats change through `setCheats` on the state thread, between frames. The rumble watcher thread ends with the game.
- GAME_START is a non-exported alias. RestartActivity is not exported. Launcher-only extras are read through `launcherString`/`launcherBool`, gated on fromJoey64. carriedExtras catches bad parcels (apart from L2).
- The second game start (switchGame) unpacks on gameIo with a try (audit 3 H2 fix holds). Its MD5 is carried (M18 fix holds).
- restartForSettings writes the recovery state, not the auto slot (audit 3 fix holds; only the text is stale, L1).
- saveQuit writes the new state as `.new` and shifts the kept quit saves only after that write works (audit 3 M1 fix holds).
- Recovery states are per game (`files/recovery/games/<save name>/`). switchGame and a clean quit clear only that game's.
- State files: writeWhole uses .part then .old; readWhole falls back to .old. StateFormat checks RZIP sizes against a 64 MB cap with Long math.
- FramePicture and the state thumbnails: decoding failures are caught.
- onDestroy: stop, then the SrmSync push on gameIo, recovery cleared unless kept, and the state thread quit safely.
- SrmSync (DataFolders.kt): @Synchronized; pull and push go through .part and a checked write; a torn disk-save copy is dropped and pushed next time; all exceptions are caught.
- RomFiles: archive entry sizes are capped and the 7z memory limit is set. The handed ROM cache is reused only when its size matches. cleanSaveName strips path parts.
- ZipNames, Capped, RecentlyPlayed, Controllers: bounds and nulls are handled.
- LogMask masks the RetroAchievements token, netplay keys, user names and content URIs in log lines. catchUncaught and keepJavaCrash catch their own errors.
- GameOverlay and StatsOverlay: windows removed with removeViewImmediate on close. Graph indexes stay in range. RaSettings is read once.
- PopupQueue (InGamePopups.kt): UNTIL_HIDDEN pop-ups are removed by key; no leak across games. SlotDates formatting is fine.
- PauseScreens focus: greyed rows are focus stops. The Save picker keeps focus after a save (95c0eaa). Disconnected's B doing nothing is by design.
- Host-closed room quit (b159884) is guarded by `quitting`, so it runs once.
- Debug receivers (DUMP, PROBLEM, STICK, RA_EVENT) are registered only in debug builds and unregistered in onDestroy.
- GpuRules and GpuTrial: driver fallback to the system driver when a custom one has no GPU. BundledDrivers reads bundled-drivers.json with a catch.
- AudioSettings, OnScreen, StatsText, EmulationSettings: reads fall back to defaults on bad JSON. migrateThermal runs inside a try and rewrites a file only once.
