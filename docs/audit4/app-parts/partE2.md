# Audit 4 app part E2: UI: netplay, achievements and in-game screens

Base: C:\GithubProjects\joey64-launcher, launcher/phase0, cca5848. Read only. Paths below are under
app/src/main/java/com/joeysretro/joey64/ unless they start with app/ or another repo.

## Findings

### H1 A joiner's own game save is replaced by the host's save
- Where: launcher/screens/NetplayScreens.kt:732 (the line that promises it), app/src/main/cpp/libretro_host.cpp:1002-1035 (loadSave / flushSave), joey64-core rel3 mupen64plus-core/src/main/main.c open_sra_file / open_eep_file / open_fla_file / open_mpk_file. Most of this is native and core code, so it belongs to the other half. I came to it from the joiner's room screen and report it because it loses saves.
- What happens: the joiner's room says "You'll play with <host>'s save and settings". That is done by the core. When the emulation starts, `open_sra_file` (and the EEPROM, FlashRAM and Controller Pak ones) call `netplay_read_storage`, and a joiner gets the host's save written into `saved_memory`. `saved_memory` is the same buffer the frontend exposes as RETRO_MEMORY_SAVE_RAM (`retro_get_memory_data` returns `&saved_memory`). The order:
  1. The host app's `loadGame` calls `loadSave()` after `retro_load_game`. That reads the joiner's own `<save name>.srm` into the buffer and records it in `g_lastSave`.
  2. On the first `retro_run`, the core's game thread runs `main_run`, which opens the save files. The host's save overwrites the buffer.
  3. Every 120 frames, `flushSave()` sees the buffer differs from `g_lastSave` and replaces the joiner's `.srm` with it on disk (tmp + rename). It does the same on exit, and SrmSync pushes the file to a picked data folder.
  Nothing checks `g_netplay` on that path. So after one netplay session as a joiner, that game's own save on this device is gone and the host's save is in its place. The only copy left is in Back up saves, if it is on.
- Evidence: core `open_sra_file`: `storage->data = saved_memory.sram; ... // If player 1 we send, otherwise we recieve  netplay_read_storage("saveram.sra", storage->data, storage->size);`. libretro.c: `case RETRO_MEMORY_SAVE_RAM: return &saved_memory;`. Host: `loadSave(); ... g_lastSave.assign(mem, mem + size);` then `if (g_lastSave.size() == size && !memcmp(g_lastSave.data(), mem, size)) return; ... rename(tmp.c_str(), g_savePath.c_str())`. `g_netplay` is checked for state loads, cheats, fast-forward, rewind and upscaling (libretro_host.cpp:2342-2391, 2596), but not in flushSave. Not run on a device: confirm with one joiner session and an md5 of the .srm before and after.
- Fix: in a netplay room as a joiner (N64_NETPLAY set and this device isn't player 1), never write save RAM back. Either skip `flushSave` while `g_netplay` is set, or point `g_savePath` at a scratch file. Keep the joiner's quit save and recovery state out of the normal slots too.

### M1 "Restart to use the new settings?" restarts the game inside a netplay room
- Where: MainActivity.kt:870-879 (askRestart), 887-919 (restartForSettings), 1830 (`resume`); launcher/ingame/PauseScreens.kt:231, 247-252.
- What happens: in a room the pause menu greys Save state, Load state, Cheats and Reset, but Game settings stays open. Change any row that is read only at start: Video's Resolution, Renderer, Driver, Pacing, Widescreen or 60 fps; any Emulation row such as Overclock; Audio's Latency. Then Resume calls `askRestart()`, which has no room check, and shows Restart now / Later. Restart now writes the recovery state and starts the game again in a new process. `carriedExtras()` carries the netplay extras across (EXTRA_NETPLAY_HOST / JOIN / KEYS are launcher extras, kept because the start came from Joey64). If the host restarts, the room ends for everyone and a new one opens on the same port with no players. If a joiner restarts, it tries to register again in a game that has already started. And netplay refuses the recovery-state load (`netplay: state load refused`). The netplay screens promise "every player has to stay in step", and this breaks it.
- Evidence: `override fun resume() { if (!askRestart()) closeMenu() }`. askRestart starts `val st = states ?: return false; val pending = pendingAtStart(st.saveName)` with no `net.inRoom` test. restartForSettings: `next = gameStart(this).apply { carriedExtras()?.let { putExtras(it) } ... putExtra(EXTRA_LOAD_STATE, STATE_RECOVERY) }`. carriedExtras: `Bundle(b).apply { if (!fromJoey64) LAUNCHER_EXTRAS.forEach { remove(it) } }`.
- Fix: in a room, have askRestart return false and let the change wait for the next start. Or show the Restart card with only Later and the line "Not in a netplay room". Greying the read-only-at-start rows in a room's Game settings would also work.

### M2 A netplay start that fails says nothing, and the others wait 30 s
- Where: launcher/LibraryModel.kt:1208-1236 (netplayStartHosted, netplayGo); netplay/PlayTogether.kt:450-484 (startGame).
- What happens, host side: Start the game calls `PlayTogether.startGame()` first. That closes the lobby, sets the state to Idle (so Your room disappears), sends START to the joiners and starts a 30 s wait for the game's room. Only then does the model look for the ROM (`launcher.intentFor(game)` on IO). If no copy can be read, it logs and returns, and the host sees the room vanish with nothing in its place. The joiners wait up to SERVER_WAIT_MS (30 s) and then get "<host> closed the room". This happens with a game whose ROMs folder can't be reached: Host a room's picker lists `model.shown`, which keeps greyed games. It also happens with a SAF grant that went away, or an archive that won't unpack.
- What happens, joiner side: `netplayGo` returns silently when `gameByMd5` or `intentFor` fails after GO. The joiner's state is already Idle, so its room screen just goes. The host's game waits for a player who never comes ("Waiting for Player N").
- Evidence: `val hs = PlayTogether.startGame() ?: return ... val intent = withContext(Dispatchers.IO) { launcher.intentFor(game) } ?: run { JLog.w(TAG, "no readable file for ${game.title}"); return@launch }`. startGame: `_state.value = Idle; scope.launch { for ((j, _) in joiners) j.send(LobbyLines.line("START", keys[j]!!)) ...`. netplayGo: `val game = gameByMd5(go.md5) ?: return` and the same `?: run { JLog.w(...); return@launch }`.
- Fix: work out the intent before startGame. If there's none, keep the room open and say why ("Joey64 can't read <game> right now"). Use the same check for greyed games in the picker. On the joiner side, send BYE and show the existing "Couldn't start" card instead of returning.

### M3 Room cards and Host a room's covers have no label for TalkBack
- Where: launcher/screens/NetplayScreens.kt:247-266 (RoomCard), 412-433 (PickTile).
- What happens: on both, the thing you press is the cover Box (`pressable`). Its only child is an Image with `contentDescription = null`, or the controller icon with null. The room's name, game and players, and the game name under a cover, are sibling Texts outside the clickable. So TalkBack reads each card as an unnamed button. A TalkBack user can't tell which room they join or which game they host. Audit 3's M33 added names to the library's GameTile (`.semantics { contentDescription = ... }`, LibraryScreen.kt:501), but these two weren't covered. PickTile names itself only when there's no cover, because then the Text is inside the Box.
- Evidence: `Box(modifier...pressable(source, onClick = onClick)) { if (pic != null) Image(pic, null, ...) else Icon(..., null, ...) }`, then `Text(title ...)`, `Text(shortGame(gameTitle) ...)` and the line after the Box. PickTile has the same shape with `Image(pic, null, ...)`.
- Fix: add `.semantics { contentDescription = "<host>'s room, <game>, 2 of 4, 18 ms" }` to RoomCard's Box, with "You don't have it" as a disabled state. Give PickTile the game's name, as GameTile has.

### L1 Names and titles from other devices aren't capped or cleaned when they arrive
- Where: netplay/LobbyLines.kt:72-76 (parsePlayers), 86-92 (parseBeacon); netplay/PlayTogether.kt:609-612 (ROOM); shown in NetplayScreens.kt:577, 585, 665, 713, 730, 173 and ProblemPopups.kt:122, 128, 147.
- What happens: `field()` cleans only what this device sends: it drops tabs, line ends, | and ;, and caps at 48. Nothing cleans what arrives. A host's ROOM line can give a host name or game title of up to the 4 KB line cap. A Wi-Fi beacon can give up to 512 bytes. PLAYERS names can be just as long. None of them has bidi overrides or control characters removed. The worldwide list's Worker does strip both (server/rooms/src/rules.js:45-46), but a Wi-Fi beacon or a host's own lobby skips it. Shown in `PopupTitle("$host's room")`, "Joining X's room…", "Waiting for X to start the game", "Couldn't join X's room" and the mismatch text. A 4 KB name fills the card and pushes Leave the room off the bottom; B still leaves. A U+202E in a name turns the "'s room" after it backwards. Compose Text draws plain text, so nothing worse than that.
- Evidence: `Beacon(port, f[3].ifEmpty { "Someone" }, f[4], f[5], md5, ...)`; `hostName = f[2]; hostGame = Game(f[4], f[5].lowercase(), f[6])`; `Entry(slot, f.getOrNull(1).orEmpty().ifEmpty { "Player $slot" }, ...)`.
- Fix: run every received name and title through one cleaner. Use the Worker's rule: control and bidi characters become spaces, spaces squeezed, 48 characters (80 for a title), cut on a code point.

### L2 Restart now says it saves to the auto slot, but it writes the recovery state
- Where: launcher/ingame/PauseScreens.kt:249-250; MainActivity.kt:897-901.
- What happens: the row's line says "Saves to the auto slot, then starts again from there". Since code audit 3 (M1), restartForSettings writes the recovery state on purpose, so the quit saves don't move. The words describe the old behaviour.
- Evidence: `JoeyRow("Restart now", ..., detail = "Saves to the auto slot, then starts again from there")` against `// As the recovery state, so the quit saves stay as they were ... val saved = !startLoadFailed && st.saveRecovery()`.
- Fix: "Keeps where you are, then starts again from there", or Joey's wording.

### L3 Host a room that can't open a port does nothing and says nothing
- Where: launcher/screens/NetplayScreens.kt:202, 473; launcher/LibraryModel.kt:1149-1152; netplay/PlayTogether.kt:241.
- What happens: `netplayHost` returns false when none of ports 45000-45007 can be bound, for example when another app holds them. Both callers drop the result. The picker closes and the page stays as it was, with only a log line.
- Evidence: `{ g -> picking = false; model.netplayHost(g) }`, `NetOption("Host a room", ...) { model.netplayHost(game) }`; `val s = server ?: run { JLog.e(TAG, "no room port free (45000-45007)"); return false }`.
- Fix: when it returns false, show a short card or toast ("Couldn't open a room: another app is using Joey64's room ports").

### L4 The Netplay page says "A Join" on a room you can't join
- Where: launcher/screens/NetplayScreens.kt:146, 173-176, 193-196.
- What happens: a room for a game that isn't in this library is greyed and says "You don't have it", but its hint row still shows "A Join", and A does nothing (`if (g != null) ...`). TalkBack isn't told it's disabled either. The game's own Netplay popup does this right (`enabled = have`).
- Evidence: `focusKey.startsWith("room ") -> listOf(Hint("A", "Join"), ...)`; `RoomCard(...) { if (g != null) { ...; model.netplayJoin(...) } }`.
- Fix: no A hint while focus is on a room you don't have, and add `disabled()` to that card's semantics. The Save picker's dimmed Auto cell (PauseScreens.kt:313) needs the same `disabled()`.

### L5 Fixed-size boxes clip or overflow at large font sizes
- Where: launcher/screens/NetplayScreens.kt:521 (code boxes 36 x 46 dp), 553 (key caps 34 dp tall), 224 (action cards 220 dp wide, one line); launcher/ingame/PauseScreens.kt:305 (slot cells 100 x 75 dp); StatsOverlay.kt:199.
- What happens: these boxes have a fixed size in dp, but their text is in sp. At Android's largest font scale (2x):
  - The room-code characters no longer fit their 46 dp boxes.
  - The key-cap letters spill past their 34 dp caps onto the next row (the caps don't clip).
  - In a slot cell without a picture, the name plus a two-line date ("Today 9:12 pm" wraps) is taller than 75 dp and is cut by `.clip(shape)`.
  - "Join by code" ellipsises in its 220 dp card.
  - The stats overlay's text is `size.sp * scaledDensity`, so Full at Large on a 1080 px screen is taller than the screen, and its bottom rows (device, driver, version) go off the edge.
- Evidence: `Box(Modifier...size(36.dp, 46.dp)...) { Text(..., style = JoeyTheme.type.roomCode...) }`; `Box(modifier.height(34.dp)...)`; `Modifier.size(100.dp, 75.dp)...clip(shape)`; `val px = v * resources.displayMetrics.scaledDensity`.
- Fix: use `heightIn(min = ...)` instead of fixed heights for the code boxes and caps, and `maxLines = 1` on the slot date. For the overlay, either cap the text at 1.3x or drop the quiet lines when the panel is taller than the window.

### L6 The RetroAchievements tab reads the unlock queue file on the main thread every 2 s
- Where: launcher/screens/RaTab.kt:337-339, 372, 412; achievements/RaBridge.kt:393-396; achievements/RaUnlockQueue.kt:29, 55-58.
- What happens: while the tab is open, `produceState` calls `RaBridge.unsentCount()` every 2 s on the main thread. So do Sign out's click and SignOutPrompt's composition. That reads and parses `RaUnlockQueue`'s JSON file under a `@Synchronized` lock that the HTTP thread also takes while it writes the file. It's small today, but it is disk work on the UI thread in a loop, and it waits whenever an unlock is being written.
- Evidence: `val waiting by produceState(RaBridge.unsentCount()) { while (true) { value = RaBridge.unsentCount(); kotlinx.coroutines.delay(2_000L) } }`; `@Synchronized fun count(user: String) = read().count { ... }` with `JSONArray(file.readText())`.
- Fix: read it with `withContext(Dispatchers.IO)` inside the produceState loop, or keep the count in a StateFlow that the queue updates when it writes.

### L7 A player who leaves while "out of step" is up is never shown
- Where: MainActivity.kt:2364-2387 (netShow).
- What happens: when a player leaves, `netLeftSaid` is updated straight away. If the Out of step card is up, NetLeft isn't shown (`if (o.screen !is NetOutOfStep) o.screen = NetLeft(...)`), and the next tick finds nothing new. After Keep playing, the host never sees "<Player N> left the game".
- Evidence: `netLeftSaid = netLeftSaid or n.left ... if (o.screen !is ...NetOutOfStep) o.screen = ...NetLeft(p, netName(p)); return`.
- Fix: update `netLeftSaid` only once NetLeft is actually shown, or queue it to show when Out of step closes.

### L8 Join by code's Start has no feedback during the lookup, and a closed popup still joins
- Where: launcher/LibraryModel.kt:1163-1170; launcher/screens/NetplayScreens.kt:510-513.
- What happens: Start (or the Join key) with a valid code launches `targetForCode` on IO. That takes up to about 0.6 s: two rounds of J64FIND broadcasts with a 300 ms wait each. During it nothing on screen changes, and a second Start launches a second lookup. If the player presses B and closes the popup during the lookup, the coroutine still calls `netplayJoin`, and "Joining…" opens after they backed out.
- Evidence: `viewModelScope.launch { val t = withContext(Dispatchers.IO) { PlayTogether.targetForCode(code) } ?: return@launch; netplayJoin(t.copy(fromCode = true)) }`; JoinByCodePopup's onDismiss doesn't cancel it.
- Fix: return the Job (or keep it in the model) and cancel it from the popup's dismiss. Show "Looking for the room…" or ignore Start while one is under way.

### L9 In a room, Controller disconnected's last row says "Quit to library"
- Where: launcher/ingame/PauseScreens.kt:358, 242-243.
- What happens: in a room the pause menu's Quit row reads "Leave the room" (NetplayWords.LEAVE). The Controller disconnected card always uses `actions.quitLabel` ("Quit to library"), even though choosing it leaves the room for this player, and ends it for everyone if this is the host. The words differ from the menu's for the same action.
- Evidence: `JoeyOption(actions.quitLabel, firstFocus()) { actions.disconnectChoice(DisconnectChoice.Quit) }` against `if (room) JoeyOption(NetplayWords.LEAVE) { actions.netLeave() }`.
- Fix: in a room, use NetplayWords.LEAVE for that row too.

## Checked and fine
- Every room state has a way out. Hosting: B asks "Close the room?", Cancel focused, and a tap outside does nothing (M31). InRoom: B or Leave the room. Connecting: B or Cancel calls `leave()`. Closed: OK or B. Full, Mismatch, Unreachable, Lost and HostClosed: NetEndedPopup's OK, B, and Try again or Details.
- In-game netplay cards. NetPaused: B or a tap outside opens this player's menu, and a 1 dp focus holder that TalkBack skips lets B reach the card. NetOutOfStep: Keep playing / Leave the room or End for everyone, B does nothing. NetLeft: Carry on without them / Leave the room. The host leaving takes a joiner back to the library with "The host closed the room".
- In a room, the menu's Save state, Load state, Cheats and Reset are greyed focus stops ("Not in a netplay room"). The save, load and undo, Reset, fast-forward, slow motion, rewind, frame advance, GameShark and upscale hotkeys, plus the touch Save state, Load state and Fast-forward buttons, all go through netBlocked.
- Room codes: RoomCode.decode checks length, alphabet and the check character, and RoomList.parse drops rooms with a bad code or a non-MD5. The md5 that names a room's cover file is checked again in lookUpRoomCover. Public and Wi-Fi dedupe compares cleaned codes on both sides.
- Lobby input: `LobbyLines.readLine` caps a line at 4 KB (H6). parsePlayers keeps slots 1-4. GO's player number must be 1-4 (p3 L7), and its key is parsed as hex or dropped. The UDP buffer is 512 bytes.
- YourRoom: slotFocus has 5 requesters for slots 1..places, and places is coerced to 2-4, so there's no index error. X shows and works only on a player's row. Focus follows the moved player, and requestFocus is wrapped. Who can join is a greyed focus stop while the router is looked up.
- Socket writes from the UI (movePlayer, leave) go off the main thread. host() on the main thread only binds sockets. targetForCode runs on IO.
- Join by code: 8 boxes, the cursor box lit, the mistyped-code line, Delete/Join caps for touch, and X / Start for a pad. It closes when the room is joined or the join ends.
- Host a room picker: L2/R2 page through FocusLanding, Y search, the name kept clear of the focus ring, and 1-player games left out.
- Netplay page: Back goes to the top and the bar, Start is Join by code, a closed popup lands on its card (A8), and empty rows are text, not focus stops.
- Pause menu: B resumes. The slot picker's B goes back to the menu on the row that opened it (interceptBack), as does a tap outside. The menu wraps Up/Down and scrolls under its title. The dimmed Auto in Save is a focus stop with no A and says why. A on an empty slot when loading does nothing. Slot pictures are decoded on the state thread.
- Controller disconnected: B does nothing as approved. The first available row takes focus, and Carry on without them shows only for players 2-4. Not enough space: OK or B goes back to the game.
- Restart card: B and a tap outside choose Later. Restart now is guarded by `quitting`.
- In-game Achievements: B goes back to the menu, or to the game when the hotkey opened it (label changed to match). L1/R1 switch sets only in game, L2/R2 page. focusId opens the right set tab and row, and an empty set holds focus on its words.
- Launcher Achievements: Show and Sort survive a game's page. Up from the first row goes to the chips (focusRestorer), the empty list focuses the chip, and nothing falls to the bar. The bar gets L1/R1 first (ControlBus.dispatch), so PageShell swallowing the rest doesn't trap them. Badge waits are limited to 4 threads (p2 L13). GamesList drops rows that don't parse. game_json is always written by `JSONObject.toString()`, so the page's own parse is safe.
- RetroAchievements tab: sign-in fields land on Username or Password. B cancels a sign-in in progress (cancelSignIn signs out a late success). A wrong password clears the field and focuses it. The signed-in or signed-out swap re-arms focus only on a change (A8). Locked Hardcore and Leaderboards rows are focus stops with their lock read as "Locked". Sign out asks only when unlocks are unsent, with Stay signed in focused. The place picker's taken spot is greyed and inert (JoeyRow ignores A when disabled). The sound picker stops on close and checks an import on IO.
- In-game sign-in: an expired token sets the account to null, so InGameSignIn doesn't close at once, and it closes itself on sign-in.
- First-run RA step: B is Skip, or cancels while busy. Focus requests are wrapped in runCatching.
- GameOverlay: pop-up and badge windows are non-focusable and non-touchable panels, made invisible when empty (no stale frame), and removed with removeViewImmediate on destroy. TalkBack announces each pop-up through the game window, throttled to one a second. Taps on them are hit-tested against measured bounds.
- PopupQueue: a same-key pop-up replaces the one showing or waiting, and UNTIL_HIDDEN ones step aside and come back.
- StatsOverlay: `frameTimes` is capped by the native side at the array's length, so drawGraph's index is safe. A layout change renders at once (B5). The window is removed when Off, and with removeViewImmediate on destroy. /proc and sysfs reads are wrapped.
- Text: JoeyRow labels and details carry maxLines with ellipsis, values are capped at 220 dp, and ActionCard, RoomCard and the Achievements header lines are one line with ellipsis.
