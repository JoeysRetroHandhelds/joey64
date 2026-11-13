# Audit 4 app part C: Network clients

Code: C:\GithubProjects\joey64-launcher, launcher/phase0 at cca5848. Paths below are under
`app/src/main/java/com/joeysretro/joey64/` unless they start with `app/`, `server/` or `cpp`.
Read only; no gradle task run.

## Findings

No High findings.

### M1 Nothing ever deletes a game's state ring (files/states/ring/<md5>/: 5 x 16.8 MB raws + meta.json) (known item 1)
- Where: `cpp/state_ring.cpp:47-56, 63-75` (the only delete); `staterequest/StateRequests.kt:152-164, 170-180, 193-199`;
  `staterequest/StateUpload.kt:34-56, 85-112`; `launcher/library/GameLauncher.kt:62-63`; `MainActivity.kt:1435-1438`
- What happens: the host's `removeOldRing` is the only code that deletes ring files, and it runs only from
  `StateRing::configure` when the ring is on (`if (dir_.empty()) return; mkdirs(dir_); removeOldRing(dir_);`), that is
  on the next start of the same game with `EXTRA_STATE_RING`. The launcher only gives that extra while `ringFor` is
  true (`!p.never && w.md5 !in p.skipped && w.md5 !in p.sent`). So after any answer except No, after the upload, and
  when the game leaves the wanted list, the game's ring is never configured again and its ~84 MB stay for good. One
  per wanted game played: ten wanted games is ~840 MB of internal storage, and Android counts it as app data.
  The Kotlin side has no delete at all: `grep` finds `ringDir` used only by `ringOf` (read meta.json).
  - Popup answers: `answer()` (StateRequests.kt:170-180) only edits prefs; `Answer.No` is `Unit`.
  - After sending: `StateUpload.queue` copies the raw into the outbox (`File(part, "state").writeBytes(StateFormat.wrap(state.readBytes()))`)
    and `maybeSend` deletes only the outbox item (`item.deleteRecursively()`), never the ring.
  - Off the list / no ask: `check()` returns early (`if (md5.lowercase() !in wanted) return null`) and when `decide`
    says nothing (under 5 min, no 3D state, crash without quitAt), with the ring left in place.
  - A crashed session (no quitAt) is never asked about and its ring stays until that game is played again.
- Evidence: `state_ring.cpp:71-73` `if (dir_.empty()) return; mkdirs(dir_); removeOldRing(dir_);`;
  `GameLauncher.kt:62` `if (...StateRequests.ringFor(context, game.md5)) intent.putExtra(...EXTRA_STATE_RING, true)`;
  `StateRequests.kt:198` `return !p.never && w.md5 !in p.skipped && w.md5 !in p.sent`.
- Fix: in Kotlin, delete `ringDir(md5)` (a) at the end of `stateAnswered` after `answer()` returns (after the Yes's
  `queue` has copied the state), (b) in `check()` whenever it returns null after reading a ring, and (c) on launcher
  start, sweep `files/states/ring/*` for any md5 that `ringFor` says false for (or older than a day), which also
  covers crashes, list removals and Don't ask again. Keep the delete off the main thread (it already is: both run on IO).

### M2 A 410 "have enough" (and a 404 "not wanted") ends asking for that game forever on this install, and so does every Yes, sent or not (known item 2)
- Where: `staterequest/StateUpload.kt:76, 97-101`; `staterequest/StateRequests.kt:145-149, 170-176, 121, 198`
- What happens: `resultOf` maps 410 (and 404 "not wanted") to `Drop(done = true)`, and `maybeSend` then calls
  `StateRequests.done`, which adds the md5 to the `sent` set, which `decide` and `ringFor` treat as final. Nothing
  ever takes an md5 out of `sent`. The server's count goes down when states are deleted from D1/R2 (state.js counts
  rows: `SELECT count(*) AS n FROM states WHERE md5 = ?`) or when Joey raises the game's `enough` in wanted.json; a
  404 "not wanted" happens when the game is off the server's list (10-minute cache) while the client's copy (up to 7
  days old) still has it, so a game taken off and put back is never asked about again either.
  Note from the client side: `done()` changes nothing in practice, because `answer(Yes)` already added the md5 to
  `sent` when the item was queued (`Answer.Yes -> { if (StateUpload.queue(ctx, ask, saveName)) add("sent") }`). So
  fixing only `done()` won't help: a Yes marks the game done before the server has answered. The same forever-mark
  then also covers uploads the server never took: 400 / 413 drops, `MAX_TRIES` (12) drops after weeks of 5xx or
  "not found", and the item the outbox cap deletes (M3). The player said Yes, saw "on its way", and that install is
  never asked again although nothing arrived.
- Evidence: `StateUpload.kt:76` `code == 410 || (code == 404 && body.trim().startsWith("not wanted")) -> Result.Drop(done = true)`;
  `:99` `if (r.done) StateRequests.done(ctx, meta.optString("md5"))`; `StateRequests.kt:148`
  `putStringSet("sent", ... + md5.lowercase())`; `:174` the Yes path above.
- Fix: keep three separate records: `sent` only after a 2xx (move the `add("sent")` from `answer()` to
  `Result.Sent` in `maybeSend`, with a separate "queued" mark so the popup doesn't come back while one waits); on
  `Drop(done)` store "enough/not wanted at <time>, wanted.json version <x>" instead of `sent`, and clear it when the
  kept wanted.json changes (or after e.g. 30 days); on a plain `Drop` clear the queued mark so the game can be asked
  about again. Better still, let wanted.json say a game is full (`enough` reached, kept by the PC tool that pulls
  states) so clients stop asking, and stop paying for the ring, before a play rather than after a 2 MB upload.

### M3 The outbox cap deletes the newest Yes, not the oldest
- Where: `staterequest/StateUpload.kt:50-51, 59`
- What happens: `waiting()` sorts "newest first" (`sortedByDescending { it.name }`, names start with the time), and
  `queue` then deletes `waiting(ctx).dropLast(MAX_WAITING)`. `dropLast(5)` returns everything except the last five,
  that is the newest ones past five. With six items waiting the item just written is deleted at once, `queue` still
  returns true, `answer` marks the game `sent` (M2), and "Thanks! The save state is on its way." shows. The comment
  above it says "The oldest go past MAX_WAITING". It happens when six Yeses wait, e.g. while offline or while the
  server answers 5xx / 404 "not found" (each backs off up to a day). No test covers it (StateRequestsTest has no
  queue case).
- Evidence: `:51` `waiting(ctx).dropLast(MAX_WAITING).forEach { it.deleteRecursively() }` with `:59`
  `.sortedByDescending { it.name }`.
- Fix: `waiting(ctx).drop(MAX_WAITING)` (newest first, so this drops the oldest), and add a unit test.

### M4 Moving the data folder carries the state rings into the user's picked folder
- Where: `launcher/storage/DataMover.kt:80-89, 108-112`; `launcher/storage/DataFolders.kt:21`; `MainActivity.kt:3326`
- What happens: the ring lives in `files/states/ring/<md5>/` (`File(ctx.filesDir, "states/ring/$md5")`), inside the
  `states` kind. A data folder move from Joey64's own storage walks every kind recursively (`e.isDir -> walk(path)`),
  so each ring's 5 raws (16.8 MB each) and meta.json are copied through SAF into `<picked folder>/states/ring/<md5>/`
  and deleted here. That makes the move ~84 MB slower per ring, puts files the user never made in their folder (and
  in any sync they run on it), and a later move back brings them home again. A popup still on screen loses its state
  (`queue` then says "is gone" and nothing is sent). The host keeps writing new rings to `files/states/ring`, which
  the next move copies out again.
- Evidence: `DataFolders.kt:21` `val KINDS = listOf(DataKind.Saves, DataKind.States, ...)`; `DataMover.kt:86-88`
  `e.isDir -> { walk(path); if (from.list(path).isEmpty()) from.delete(path) }`.
- Fix: keep the ring out of the moved tree (e.g. `files/staterequests/ring/<md5>/`, changing `stateRingDir` and
  `StateRequests.ringDir` together, plus the move of any old ring), or have `DataMover.walk` skip `states/ring`.

### L1 "Thanks! The save state is on its way." shows when nothing was queued
- Where: `launcher/LibraryModel.kt:544-556`; `staterequest/StateRequests.kt:170-176`
- What happens: `answer()` returns Unit, so `stateAnswered` can't tell that `StateUpload.queue` returned false (the
  ring's raw is gone, e.g. moved by M4 or replaced by a new session, or the copy failed with no space) and puts up the
  thank-you note anyway.
- Evidence: `StateRequests.answer(app, ask, answer, saveName); if (answer == ...Yes) { _libraryNote.value = LibraryNote("Save state", "Thanks! ...") ...}`
- Fix: have `answer` return whether the Yes was queued and show the note only then (or a short "Couldn't keep the
  save state" note otherwise).

### L2 A wanted list that doesn't parse is kept for a week
- Where: `staterequest/StateRequests.kt:207-221`
- What happens: any 200 reply is written to `wanted.json` before it's parsed. A captive portal or proxy page (200
  HTML) is kept, `parseWanted` turns it into an empty map, and for 7 days (`WANTED_DAYS`) no game gets a ring or an
  ask. The cheat index does it the right way (parses first, keeps the old one on failure: `CheatLists.kt:126-134`).
- Evidence: `val text = String(get(...)); ...; part.writeText(text); if (!part.renameTo(f)) ...`, then only
  `parseWanted(f.readText())`.
- Fix: parse the reply first (and check it has a `games` array); keep it only when it parses, else keep last week's.

### L3 An outbox item cut off while being written stays on disk for good
- Where: `staterequest/StateUpload.kt:40-43, 59`
- What happens: each Yes writes `<time>-<md5>.part/` (the ~2 MB wrapped state and the save). If the process dies
  between `part.mkdirs()` and `renameTo` (swiped away, low memory, no space), the `.part` folder stays: `waiting()`
  skips `.part` names and the next queue only clears its own new name.
- Evidence: `val part = File(box, item.name + ".part")`; `listFiles { f -> f.isDirectory && !f.name.endsWith(".part") }`.
- Fix: at the start of `queue` (or `maybeSend`), delete every `*.part` in the outbox.

### L4 A Java crash's stack is deleted before its report goes, so a failed send loses it
- Where: `reports/ReportSender.kt:51, 195, 101-113`
- What happens: `send()` builds every crash summary first (`exits(ctx)` -> `summary()`), and for a Java crash
  `summary` reads `java-crash-<pid>.txt` and deletes it at once. When the post then fails (offline, 429, 5xx: `failed
  = true; break`) or the crash is held back because the report is over 60 KB, `exitsAfter` doesn't move, so the crash
  is sent next time, but only as "crash (Java)" without its stack.
- Evidence: `ApplicationExitInfo.REASON_CRASH -> File(..., "java-crash-${x.pid}.txt").takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }`
- Fix: read without deleting, and delete the file only when the crash went (where `maxExit` is set after a 204 /
  400 / 413), or when its exit is older than `exitsAfter`.

### L5 The downloaded APK stays in cache/updates after the update
- Where: `launcher/update/AppUpdates.kt:148-151, 186-189`; `launcher/update/UpdateModel.kt:159-162`
- What happens: `download` clears `cache/updates/` only when the next download starts. After an install (or Not now
  on the permission prompt) `Joey64-<version>.apk` (~25-30 MB) stays until Android clears the cache under pressure.
- Evidence: `dir.listFiles()?.forEach { it.delete() }  // older downloads` is the only delete.
- Fix: clear `cache/updates/` when `justUpdated` reports a new version (the first start after the install), and when
  a NeedPermission prompt is dismissed.

### L6 The install-session fallback copies the APK on the main thread
- Where: `launcher/update/UpdateModel.kt:138, 154-162`; `launcher/update/AppUpdates.kt:226-240`
- What happens: `install(apk)` runs on `viewModelScope` (Main) after the download, and from `resumed()` (Main). When
  `openInstaller` fails, `AppUpdates.install` copies the whole APK into the PackageInstaller session and `fsync`s it
  on the main thread: ~30 MB of disk work, a visible freeze on slow storage and an ANR risk.
- Evidence: `if (!AppUpdates.openInstaller(context, apk)) AppUpdates.install(context, apk)`;
  `apk.inputStream().use { it.copyTo(out) }; session.fsync(out)`.
- Fix: run the fallback in `viewModelScope.launch(Dispatchers.IO)` (the commit and its status activity don't need the
  main thread).

### L7 A public room whose game name is over 80 characters is never listed, and the host isn't told
- Where: `netplay/RoomList.kt:113-119, 134-135`; `netplay/PlayTogether.kt:410-411`; server `server/rooms/src/rules.js` (`field`)
- What happens: the listing sends `game.title` (the library title, which can be the full file name) and the name
  uncut. The Worker refuses a game over 80 characters or a name over 48 (`if (s.length > max) throw new Invalid(what + " too long")`)
  with 400. The client only logs `public rooms: POST answered 400` and posts the same body again every 30 s; the room
  screen still says Public. Long hack and No-Intro names with several tags reach 80.
- Evidence: `.put("name", now.name).put("device", now.device).put("game", now.game)` with no cut; server
  `game: field(b.game, 80, "game")`.
- Fix: cut name and device to 48 and game to 80 (as `LobbyLines.field` does to 48 on the Wi-Fi side) before posting.

### L8 The public list's rooms are all pinged three times a second while the Netplay page is open
- Where: `netplay/PlayTogether.kt:143-157`
- What happens: the pinger sends `PingBurst.COUNT` (3) UDP pings a second to every room on this Wi-Fi plus every room
  on the worldwide list (`RoomList.rooms.value.orEmpty()`), which the Worker returns up to 300 of (`MAX_ROOMS`), while
  the page shows the three best (`NetplayScreens.kt:464` `.take(3)`). At a full list that is ~900 packets a second
  to hosts the player never sees, and it hands this device's address to every listed host.
- Evidence: `val targets = (_rooms.value.map { ... } + RoomList.rooms.value.orEmpty().map { it.ip to it.port }).distinct()`
  then `for (i in 0 until PingBurst.COUNT) ... for ((ip, port) in targets) sock.send(...)`.
- Fix: cap the worldwide targets (e.g. the newest 20 or those for games in the library), or ping them once a few
  seconds and burst only the rooms on screen.

## Checked and fine
- State requests: `parseRing` drops paths with `..`; `queue` also checks the raw is a file under `filesDir`
  (`canonicalPath.startsWith(filesDir + "/")`), so a bad meta.json can't upload another file.
- State requests: `parseWanted` takes only 32-hex md5s and known kinds; the reply is capped at 2 MB (`Capped.bytes`).
- State requests: `check` waits at most 5 s for quitAt, on Dispatchers.IO (`resumed` -> `launch(Dispatchers.IO)`);
  `ringFor` reads only the kept file at game start (no network), called inside `intentFor` off the main thread.
- State upload contract against `server/reports/src/state.js`: multipart field names md5 / kind / app / state / save,
  state wrapped as RZIP (`StateFormat.wrap`), save gzipped; the app version (`versionName`, e.g. "0.1.0.1") passes the
  server's `^[0-9A-Za-z.+_-]{1,40}$`; chunked upload, so the server's Content-Length check is skipped and its
  `state.size` check (20 MB) still applies.
- State upload answers: 204 sent; 429 waits for Retry-After (clamped 60 s - 1 day) and stops the round; -1 offline
  stops the round; 5xx / 408 / 404 "not found" back off from 1 min doubling to a day; 400 / 413 / "wrong kind" drop;
  12 tries at most; `sending` AtomicBoolean stops two rounds at once; a damaged item.json is deleted, not retried.
- State upload: Retry-After as an HTTP date parses to null and falls back to the backoff (no crash).
- Reports: `maybeSend` catches every Exception and runs on IO; body cut to 60 KB with at least one event (audit 3 M22
  fix is in place and right); 400 / 413 drop instead of blocking the queue; only a 204 uses up the day.
- Reports: crash text goes through `scrub` (content URIs, file paths, SAF ids); the tombstone reader bounds every
  length and varint and returns null on bad input; tombstone read capped at 8 MB.
- Reports: `SessionTracker` methods are `@Synchronized`; pending/open files written through `.part` + rename;
  `sweepOpen` uses only our own processes' pids (`runningAppProcesses`).
- Reports: the device part, md5, header and settings are length- and character-checked to match validate.js.
- Updater: feed reply capped at 4 MB; JSON errors become Failed with backoff (1, 2, 4, 8, then 15 min); the APK is
  checked for size, SHA-256 when the release gives a digest, and package name before rename; Android's installer
  checks the signature; each run has its own .part name (audit 3 L4 fix holds); cancel is checked every 64 KB.
- Updater: `UpdateStatusActivity` is `exported="false"`, so no other app can hand it an `EXTRA_INTENT` to start;
  its PendingIntent is FLAG_MUTABLE as PackageInstaller needs.
- Updater: `Releases.compare` treats non-numbers as 0 (no crash); drafts are skipped; testing builds pick the
  highest version of the 20 newest.
- Netplay lobby: lines capped at 4 KB (`LobbyLines.readLine`), at most 4 sockets waiting for HELLO with a 10 s
  deadline (audit 3 H6 fix holds); GO's player checked 1-4; keys 8 hex, `SecureRandom`, never logged
  (`LogMask.extra` masks `NETPLAY_KEY(S)` in the session log's intent line; the PlayTogether logs say only "with its key").
- Netplay: every socket write runs on PlayTogether's IO scope (movePlayer, leave); UPnP open/close on one queue
  thread; the Wi-Fi lock and multicast lock are released when browsing and rooms end (`lowLatencyCheck`).
- Netplay: beacons need 9 fields, the proto, a port 1-65535 and a 32-hex md5 (it names a cover file); players
  clamped 1-4; own beacons ignored; public room list md5 and code checked, names cut to 48/80.
- Netplay: other players' public addresses go through `LogMask.addresses` in join and router logs.
- Netplay JNI: `netplayInfo` always returns an 8-int array (`NewIntArray(8)`), Kotlin reads it with `getOrElse`;
  `netplayOutside` is "state outside" and a bad address only makes `RoomCode.encode` return null;
  `netplayUpnpOpen/Close` are called only on the single upnp thread.
- Rooms list contract against `server/rooms`: POST fields id / secret (32 hex each) / name / device / game / md5 /
  players / code, DELETE with X-Room-Secret; `hosting()` is `@Synchronized` and waits for the previous request
  (audit 3 L8 fix holds); the list read is capped at 1 MB.
- RetroAchievements: the password goes straight to native and is never stored or logged; the token is kept sealed
  (`SignInStore`), removed from the login event before listeners see it, sent only in POST bodies, and failed calls
  log only the `r=` request name.
- RetroAchievements: `serverCall` always answers native in `finally` (any Throwable), so rc_client never waits for
  good; `serverResponse` gets null on no answer.
- RetroAchievements: the host override takes only plain http loopback with a port (accepted design in audit 3);
  the offline queue file is written through .tmp + rename and every method is `@Synchronized`; `settled` follows
  rcheevos' retry rule.
- RetroAchievements: badge file names from URLs are limited to `[A-Za-z0-9_.-]` and no `..`, so a reply can't name a
  file outside `files/ra/badges/`; one download per file at a time.
- Cheats: index and list replies capped at 8 MB; source names limited to `[A-Za-z0-9_-]` and keys come from the
  ROM header, so a list can't be written outside `files/cheatlists/`; a bad kept index is deleted, not thrown into
  the UI (audit 3 p2 L2 fix holds); bad lists are caught and deleted; one cheats writer with a lock (audit 3 H3 fix holds).
- ScreenScraper (secrets): request errors name the endpoint (`what`), never the URL with the login;
  `FileNotFoundException(what)` and `IOException("HTTP $code from $what")`.
