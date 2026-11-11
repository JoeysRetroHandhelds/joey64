# Audit 3, part 4: video, controls, achievements, touch

Code: C:\GithubProjects\joey64-launcher (launcher/phase0, df01a53), read only. Paths below are under
`app/src/main/java/com/joeysretro/joey64/` unless they say otherwise. Native RA half read too
(`app/src/main/cpp/achievements.cpp`) for the JNI callbacks.

Totals: High 1, Medium 3, Low 9.

---

## High

### H1. Release builds can't reach RAOfflineProxy: plain http to 127.0.0.1 is blocked, so unlocks are lost
- Files: `achievements/RaBridge.kt:230-238` (isLocalHost only takes `http://127.0.0.1|localhost|[::1]:port`),
  `RaBridge.kt:336-369` (serverCall), `app/src/debug/res/xml/dev_network.xml`, `app/src/debug/AndroidManifest.xml`,
  `app/src/main/AndroidManifest.xml` (no `networkSecurityConfig`, no `usesCleartextTraffic`), `app/build.gradle*` (targetSdk 34).
- What goes wrong: with targetSdk 34 Android blocks cleartext traffic by default, localhost included
  ("CLEARTEXT communication to 127.0.0.1 not permitted"). The only config that allows http to 127.0.0.1/localhost
  is `src/debug/res/xml/dev_network.xml`, wired in by the debug manifest only. Its own comment says "Release builds
  keep Android's default: https only". RAOfflineProxy's address is always `http://127.0.0.1:<port>` (isLocalHost
  refuses anything else). So in a release build, with Offline unlocks set to RAOfflineProxy and the proxy running:
  every rc_client request (sign-in, game load, awardachievement) throws `UnknownServiceException` (an IOException),
  serverCall reports status -2, sign-in never answers, the game is never identified. An unlock that was already
  triggered (set loaded from the cache earlier is unlikely here, but any award request that goes out) gets no answer,
  and because `route.queue` is false for the Proxy route (`OfflineUnlocks.kt` OfflineRoute.of, `RaBridge.kt:366`) it is
  not put on Joey64's own queue; rc_client retries only until the game is left, then it's gone.
- Why it hasn't shown: every device check so far ran a debug build, which has the cleartext exception.
- Fix: add a main (all builds) network-security-config with a `domain-config cleartextTrafficPermitted="true"` for
  `127.0.0.1` and `localhost` only (the same file the debug build uses, moved to `src/main`); check `[::1]` too
  (domain-config can't name it; either drop `[::1]` from isLocalHost or accept that it stays blocked).

---

## Medium

### M1. Saving or clearing the sign-in can crash the app from a background coroutine
- File: `achievements/RaBridge.kt:73` (`io = CoroutineScope(SupervisorJob() + Dispatchers.IO)`, no
  CoroutineExceptionHandler), `:293` (`io.launch { SignInStore(ctx).save(...) }`), `:310` (`io.launch { SignInStore(ctx).clear() }`),
  `:142-146` (signOut: `SignInStore(ctx).clear()` outside the try that only wraps the cache clear).
  `launcher/settings/SignInStore.kt:36-51`, `KeystoreBox.seal` (`:79-89`, not wrapped).
- What goes wrong: an exception in a `launch` with no handler goes to the thread's uncaught handler, which kills the
  process on Android. `save()` throws when the Keystore misbehaves (`KeystoreBox.seal`/`key()` has no try:
  KeyStoreException/ProviderException on some devices, or after the keystore was reset), and every DataStore
  `edit` throws `CorruptionException` when `signin.preferences_pb` is damaged (no corruptionHandler is set) or
  IOException on a full disk. A damaged file is a crash loop for the user: `init` swallows the load error (`:101`)
  and shows them signed out, they sign in, `loginDone` saves, the app dies; Sign out dies the same way.
- Fix: wrap the three bodies in try/catch (log and carry on), or give `io` a CoroutineExceptionHandler; set a
  `ReplaceFileCorruptionHandler { emptyPreferences() }` on the `signin` DataStore.

### M2. RAOfflineProxy dying mid-game loses every unlock earned after it
- File: `achievements/RaBridge.kt:362-367`, `OfflineUnlocks.kt` (`OfflineBy.Proxy -> queue = false`).
- What goes wrong: with Proxy picked, Joey64's queue deliberately takes nothing ("RAOfflineProxy keeps them"). That
  only holds while the proxy answers. If Android kills the proxy app in the background (common on handhelds while a
  game runs), the award requests get no answer at all (status -2, connection refused): the proxy never saw them, Joey64
  doesn't queue them, rc_client retries until the game is left, then they're lost. The route is fixed for the game
  (`gameStarting`), so nothing switches over.
- Fix: in serverCall, also queue an award when the route is Proxy and there was no answer at all (status -2 / no body);
  sendUnsent then sends it once the proxy (or, with Joey64 picked later, the server) answers.

### M3. Transfer Pak: a picked Game Boy save that can't be read is marked as copied, and the old save plays instead
- File: `controls/GameBoyFiles.kt:178-199` (saveCopy), called from `MainActivity.kt:1286` at game start.
- What goes wrong: when the picked save changes, the copy is replaced from the picked document inside a try; if
  `openInputStream` throws (permission to that folder lost, file moved, SAF provider gone) or returns null, the
  error is only logged, but `source.writeText(from)` (`:196`) still records the new save as the copy's source. The
  game then plays (and writes to) the previous copy, e.g. another game's or a "new" save, and the next start won't
  try the picked save again since the source already matches. The user sees the wrong Pokémon save with no message.
  Also `copy.copyTo(prev, true)` (`:187`), `old.copyTo(copy)` (`:182`) and `source.writeText` are outside any try,
  so a full disk throws out of saveCopy into the game-start path.
- Fix: write `.source` only after the copy really went in (and return/flag a failure so the start can say so);
  wrap the whole function so disk errors come back as a failure, not an exception.

---

## Low

### L1. RaBridge.init reads the keystore and DataStore on the main thread
- `achievements/RaBridge.kt:101` `runBlocking { SignInStore(app).load() }`; called from `LauncherActivity.kt:127`
  (onCreate) and `MainActivity.kt:1315`. A DataStore first read plus a Keystore AES-GCM decrypt; tens of ms normally,
  longer on a slow keystore. Fix: load on `io` and start `loginWithToken` from there.

### L2. serverCall answers rc_client only when the failure is an IOException
- `RaBridge.kt:337-369`. Anything else thrown in the executor body (e.g. a RuntimeException from the HTTP stack or a
  non-http URL cast) skips `serverResponse`, leaks the native `Pending` (`achievements.cpp:189`) and leaves that
  rc_client call (sign-in, game load) waiting forever (sign-in stays Busy). Rare, but cheap to close: catch
  `Throwable` around the call and always reach `serverResponse(handle, status, body)` in a finally.

### L3. Any app on the device can redirect RetroAchievements (and the token) to its own local server
- `achievements/RetroAchievementsHostOverrideReceiver.kt`, manifest `exported="true"` with no permission.
  Accepted design (PPSSPP's rules, RAOfflineProxy finds it by class name), noted for the record: when the user has
  picked RAOfflineProxy, any installed app can send SET with `http://127.0.0.1:<its port>` and receive the next
  token sign-in and every request; any app can also send CLEAR so Proxy users start games without achievements.
  Fix (if wanted): accept SET only from RAOfflineProxy's package/signature (check `sentFromPackage` on API 34+) or
  show the new host in Settings.

### L4. A damaged overlays index.json can crash a game start
- `video/RaOverlay.kt:185-191` index(): `a.getJSONObject(it)` and `e.getString("rel")` throw JSONException for an
  entry that isn't an object or lacks "rel" (readJson only guards the parse). Called on the main thread from
  `MainActivity.kt:938` (Match each game) and from Compose (`PicturePopups.kt:246, 913`). Fix: `optJSONObject` /
  `optString` and skip bad entries (as SlangStore.index already effectively does).

### L5. Main-thread file and bitmap work in the Picture popups
- `PicturePopups.kt:631, 686, 913` call `raPlaceValue` → `RaOverlayStore.matchFor` → reads index.json in
  composition; `PicturePopups.kt:698/717/768` `found()` → `RaOverlayStore.holeOf` decodes the overlay PNG and
  flood-fills it on the main thread the first time (cached in holes.json after). Big overlay packs make this a
  visible stall. Fix: move to `produceState` + `Dispatchers.IO` like the previews.

### L6. File names from settings files aren't re-sanitised before being written to
- `controls/GameBoyFiles.kt:165` `File(outDir, name)` uses `gbGame.name` as stored in the controls JSON; SAF display
  names have no '/', but a hand-edited or restored controls file with `"name":"../../x.gb"` writes outside
  `cache/tpak`. Same shape, read-only, in `RaOverlayStore.localCfg/imageFile` and `SlangStore.localFile`
  (`"raov:../.."` / `"slang:../.."` values). Fix: take `File(name).name` / reject `..` segments.

### L7. Copies from SAF folders have no size cap
- `video/Slang.kt:340-348` (TreeReader.copy), `RaOverlay.kt:246-250`, `PictureImages.copyIn` (`:72-86`),
  `GameBoyFiles.copyGame` (zip entry unbounded). A huge file named as a texture/image/GB ROM is copied whole into
  internal storage. UnlockSound.importOwn already caps at 5 MB; the others could cap similarly (e.g. 64 MB).

### L8. Rename removes the old preset/profile before the new one is written
- `video/Picture.kt:265-275` (PictureStore.rename: `remove` then `save`), `controls/ControlsStore.kt:68-75`
  (renameProfile). `write` only logs a failure, so on a full disk the preset/profile is simply gone (and with
  presets the Shader rows are already pointed at the new name). Fix: write the new file first, delete the old after.

### L9. PictureImages.copyIn deletes the existing image before the rename
- `video/PictureImages.kt:83` `f.delete(); if (!part.renameTo(f)) throw`: renameTo replaces on the same filesystem
  anyway, so the delete only adds a window where a failed rename leaves no image (games pointing at that name then
  draw no sides). Also two different pictures with the same display name overwrite each other. Fix: drop the delete.

---

## Checked and fine

- RA token: native `userJson` passes it to Kotlin once (`achievements.cpp:155`); `loginDone` strips it before the
  listeners (`RaBridge.kt:290`); kept only sealed with an AndroidKeystore AES-GCM key (`SignInStore`); the password
  is passed straight to rcheevos and released (`achievements.cpp:441-449`), never stored.
- Token/password never logged: Kotlin logs only the user name and `endpoint()` ("r=..." only); rcheevos at INFO logs
  "Attempting to log in %s (with token)" (user name only; checked `rc_client.c`); the old unsealed prefs are wiped at init.
- Offline queue (`RaUnlockQueue`): all methods `@Synchronized`, atomic tmp+rename write, damaged file reads as empty,
  dedupe per user+id, removed only on a settled answer (rcheevos' own retry rule), rebuilt with seconds-since for
  the signed-in account; sendUnsent runs on one thread, so no double send; a duplicate send would be harmless anyway.
- Sign out with unsent unlocks asks first (unsentCount) before `queue.clear(user)`.
- Host override: isLocalHost refuses user info, path, query, fragment, non-http and non-loopback hosts.
- JNI: `Env` attaches/detaches threads not already attached; exceptions cleared after every CallStaticVoidMethod;
  `serverResponse` releases the body with JNI_ABORT and frees `Pending`; rcheevos built with threads (no
  RC_NO_THREADS), so callbacks on ra-http next to `rc_client_do_frame` on the emulation thread are locked inside
  rc_client; RAM pointer guarded by `g_ramMutex`; pending progress by `g_progressMutex`; `setHost` never passes null
  (the earlier Thor crash is handled).
- Listeners: RaBridge.onEvent catches each listener's exceptions; RaPopups/RaCache hop to their own executors and
  catch; a rejected execute after stop is caught by the same try.
- RaBadges: file names restricted to `[A-Za-z0-9_.-]`, no "..", one download per name, tmp+rename.
- RaGameList: off the main thread (LibraryModel), parse wrapped, .part+rename, failures logged without the URL body.
- UnlockSound: import capped at 5 MB, checked playable before replacing, .part cleaned; MediaPlayer release under a lock.
- RaSettings: every enum/int read with a fallback and range check; unknown values fall back to defaults.
- VideoStore/ControlsStore/TouchStore/PictureStore: whole-file .part+rename writes, unreadable files logged and
  defaults used; every parsed value range-checked or enum-matched with fallback (PlayerSetup, StickFeel, Hotkeys
  incl. the version<3 migration, Macro/MacroStep frames clamp, TouchControl sizes/opacity clamp, TouchSettings).
- Game file names: `ControlsStore.safe` replaces separators and control characters (used by every per-game store).
- Slang deps: `Slang.resolve` drops `..` that would leave the picked folder; cycles stopped by the visited set; the
  preset path itself goes through TreeReader.find, which can't follow `..`; SAF queries/opens all wrapped.
- RetroArch overlay .cfg parse: overlay count capped at 64, image paths through `Slang.resolve`.
- Hole finder: explicit stack bounded by w*h with a seen array; image down-sampled to under 960 wide.
- PictureImages.load catches OutOfMemoryError; sample size from the bounds.
- GameBoyFiles: zip entry names cut to the last path part (no zip slip); `dirs.first { }` only gets names that came
  from `dirs`; tree ids parsed in a try.
- AxisPicker, PlayerInput, StickFeel.apply: dead zone clamped to 0..50 so no divide by zero; pure math.
- TouchInput: `cancel()` runs against the old list before a new layout replaces it, so indexes stay valid; touch
  window add/remove wrapped; fade runnable removed on hide.
- LookPreview: sampling clamps indexes; JVM-only math.
