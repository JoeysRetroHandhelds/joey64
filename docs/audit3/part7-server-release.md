# Audit 3, part 7: server, devtools, workflows, release config

Scope: `server/reports`, `server/rooms`, `devtools/`, `.github/workflows/`, `app/build.gradle.kts`,
`app/proguard-rules.pro`, both manifests, dev switches, signing, licences, secrets in git.
App at df01a53 (launcher/phase0), core ra-known2 1281dd8.

Totals: **1 High, 5 Medium, 9 Low.**

One side effect to disclose: while checking `make-pakdb.py` I ran it once against the core's
`mupen64plus.ini` (ra-known2). It rewrote `app/src/main/assets/pakdb.tsv` with identical content
(`git status` shows no change; 973 Rumble Pak games of 3292). Nothing else was written in the repo.

---

## High

### H1. Any installed app can make Joey64 open a netplay room, with a router port mapping, or join any server (release builds)
- **Where:** `app/src/main/java/com/joeysretro/joey64/MainActivity.kt:127-130`; manifest `MainActivity`
  `exported="true"` with a VIEW filter (`application/octet-stream`, zip, 7z); `app/src/main/cpp/libretro_host.cpp:2019-2026`;
  `app/src/main/cpp/netplay_server.cpp:641-642`.
- **What goes wrong:** the netplay extras are read from the intent with no dev or caller check:
  ```kotlin
  intent?.getStringExtra(EXTRA_NETPLAY_HOST)?.let { android.system.Os.setenv("N64_NETPLAY_SERVE", it, true) }
  intent?.getStringExtra(EXTRA_NETPLAY_JOIN)?.let { android.system.Os.setenv("N64_NETPLAY", it, true) }
  ```
  The `--es env/opts/cheats` switches were moved behind `devBuild` in the 2026-11-07 audit (comment at line 62:
  "This activity takes ROMs from other apps, and those must not set the process environment"), but these three
  were added later and skip that gate. Another app sends `ACTION_VIEW` with its own content URI for a ROM plus
  `com.joeysretro.joey64.NETPLAY_HOST="45000:4:auto"`. At game load the host calls `NetplayServer::start(port, ...)`,
  which listens on TCP/UDP and calls `Upnp::open(port)` (NAT-PMP, then UPnP) unless `N64_NETPLAY_UPNP=0`. The device is
  then reachable from the internet on that port, running the netplay packet parser, without the user ever opening
  Play together. With `NETPLAY_JOIN="<host>:<port>:<n>"` instead, the core connects to whatever server the other app
  names and parses its packets.
- **Fix:** honour the netplay extras only when the launcher sent the intent. The game runs in the launcher's process,
  so a one-time random token in a process-wide object (set by `LibraryModel` at lines 939/954, checked and cleared in
  `MainActivity`) works. Or route the launcher's starts through a non-exported activity alias and accept the
  extras only there.

---

## Medium

### M1. RAOfflineProxy cannot work in a release build: cleartext to 127.0.0.1 is blocked
- **Where:** `app/src/debug/AndroidManifest.xml:3` (`networkSecurityConfig="@xml/dev_network"`), `app/src/debug/res/xml/dev_network.xml`;
  `app/src/main/AndroidManifest.xml` (no config); `achievements/RaBridge.kt:336-360` (`serverCall`).
- **What goes wrong:** the only proxy host the app accepts is `http://127.0.0.1:<port>` / localhost / [::1]
  (`isLocalHost`, plain http on purpose, as RAOfflineProxy sends it). With targetSdk 34 the default network security
  config refuses cleartext, and only the **debug** manifest allows it for 127.0.0.1/localhost (its comment says
  "Release builds keep Android's default: https only"). In release, `URL(url).openConnection()` to the proxy throws
  `UnknownServiceException` ("CLEARTEXT communication ... not permitted"), an `IOException`. `serverCall` catches it and returns
  status -2, so rc_client retries without end. With Offline unlocks set to RAOfflineProxy, achievements never reach the
  proxy. Every check so far ran on debug builds, where the debug config hides this.
- **Fix:** add a `main` network security config that allows cleartext for `127.0.0.1` and `localhost` only (the
  debug one can then go), and run one release-build check with RAOfflineProxy.

### M2. A report over 64 KB is retried every day and never sent; reports from that install stop for good
- **Where:** `reports/ReportSender.kt:74-95` (`if (code == 204 || code == 400) ... else break`), `server/reports/src/index.js:28-31` (413).
- **What goes wrong:** the app caps events at 200 but never caps bytes. A typical session event is about 625 bytes
  (I measured the README's sample with the 2026-11-09 fields: 625 chars), so about 104 sessions in one GPU/driver group
  already pass 64 KB. The first report also carries up to 150 cover events and every crash: `getHistoricalProcessExitReasons(..., 16)`,
  each summary up to 2,000 chars. The server answers 413. The sender treats 413 like "try tomorrow" (`break`), deletes
  nothing, and the next day builds the same oldest-first report again, so it gets 413 forever. `pending/` grows
  without limit, and covers and crashes are never sent. How a user gets there: `maybeSend` runs only from
  `LibraryModel.resumed()`. Someone who starts games from a frontend (ES-DE, JoeyOS) through the VIEW intent and seldom
  opens the library, or who plays offline for weeks, piles up 100+ sessions.
- **Fix:** build the body, and while it is over about 60 KB drop events from the end (they wait for the next report).
  Also treat 413 like 400 for the events it held, so one bad batch can never block the queue.

### M3. Reports server: the per-install cap can be bypassed, so one address can use up D1's daily write budget
- **Where:** `server/reports/src/index.js:22-45`, `store.js:4`, `wrangler.toml` (`PER_IP` 10/60 s); README "Free plan limits".
- **What goes wrong:** `PER_INSTALL_DAY` counts by `install`, which the poster picks (any v4-shaped UUID passes `INSTALL`).
  The only real limit is 10 posts a minute per IP. Each post can hold 200 events, and the README counts 2 rows written
  per session or cover, so about 400 rows a report. D1's free plan allows 100,000 rows written a day. About 250 reports
  (25 minutes from one address) use that up, and after that every real report fails until 00:00 UTC. Doing it again
  each day blocks reporting for good. Apps just retry tomorrow, so nothing is lost, but nothing arrives either. At
  14,400 posts a day of up to 64 KB, one address can also grow the 500 MB database by hundreds of MB a day. Made-up
  sessions also skew `drivers.sql` / `rules.sql`, which pick drivers.
- **Fix:** add a daily cap per address without storing it: a D1 row keyed by `sha256(day + secret + ip /24 or /64)`,
  deleted with the day. Lower `MAX_EVENTS` to what one real day produces. Refuse once a global daily row budget, kept
  under the free limit, is reached, so normal installs keep some room.

### M4. Rooms server: IPv6 posters skip the address check, so they can list, replace or flood rooms for any IPv4 address
- **Where:** `server/rooms/src/rules.js:30` (`if (/^\d+\.\d+\.\d+\.\d+$/.test(from) && room.ip !== from)`), `index.js:48-66`.
- **What goes wrong:** when `cf-connecting-ip` is IPv6 (common on phone networks and many home ISPs), nobody checks that
  the code's IPv4 belongs to the poster. Such a poster can:
  - list a "room" whose code points joiners at any public IPv4 address on ports 45000-45007;
  - take over a real host's slot: the batch's `DELETE FROM rooms WHERE code = ? AND id != ?` removes the real room
    (no secret needed) and puts the attacker's name and game in its place, every 30 s;
  - fill the list: 300 rooms at 4 per /30 block, refreshed inside 75 s, is 240 posts a minute. The rate limiter is
    keyed on the full IPv6 address, so about 24 addresses from one /64 do it, and then every real host gets 503 "list full".
- **Fix:** for IPv6 posters key `POSTS` on the /64. Allow the code-replacing DELETE only when the IPv4 was verified
  (`from` is IPv4 and equals the code's address). Cap rooms posted from unverified (IPv6) addresses, for example 50 in
  all and 1 per /64.

### M5. GPL source offer covers only the app repo, not the bundled core; both repos are private today
- **Where:** `launcher/screens/SettingsScreen.kt:356, 383` ("Source code" → `JOEY64_LICENCE.url` = github.com/JoeysRetroHandhelds/joey64);
  `SettingsScreen.kt:480-497` (About shows the core only as "joey64-core <commit>", with no link); `gh repo view`: joey64 PRIVATE,
  joey64-core PRIVATE.
- **What goes wrong:** the APK ships `libjoey64_libretro_android.so`, a modified mupen64plus-next (GPL-2.0+, with
  GLideN64 and xBRZ). Its corresponding source is the joey64-core repo at the commit in `core-version.txt`, and
  nothing in the app points there. The "private until release" note covers the app repo, but making joey64 public
  alone would still leave the core's source unoffered.
- **Fix:** before the first public build, make joey64-core public too and add a "Core source" row (or extend the
  Source code text) linking `github.com/JoeysRetroHandhelds/joey64-core/tree/<commit from core-version.txt>`.

---

## Low

### L1. A release can ship without the core and drivers, or with a different one than About says
- `jniLibs/**/*.so` and `assets/drivers/*.so` are untracked (`.gitignore: *.so`). `build.gradle.kts` only copies
  `core-version.txt` (the `copyCoreVersion` task). Nothing checks that the .so files exist or that their md5 matches
  `core-version.txt` / `devtools/drivers/bundled.json`. A build from a clean checkout packs an APK that runs no game. A
  hand-copied core shows the wrong commit in About, in reports and as the source offer. Today they match (checked: core
  fe1f6927..., Turnip 61baaeaf..., faux123 46e1676b..., all as recorded).
  **Fix:** a `preReleaseBuild` check task that fails on a missing file or an md5 mismatch.

### L2. Hard-coded driver names will go stale
- `MainActivity.kt:665-666` returns "Turnip faux123 v1.2.7" / "Turnip Gen8 V38" for the stats overlay ("keep these in
  step"). `devtools/drivers/faux123/meta.json` still says 1.2.5 (last touched in 7765edc; unused by the build).
  **Fix:** read the name from `bundled.json` packed as an asset, and drop or update `meta.json`.

### L3. The crash-text scrub stops at the first space, so folder names can reach the server
- `ReportSender.kt` `scrub`: `/(storage|sdcard|mnt|data/user|data/data)/\S+` → `<path>`. A Java exception message such as
  `FileNotFoundException: /storage/emulated/0/ROMs/John Smith/Zelda.z64` becomes `<path> Smith/Zelda.z64`. The README promises
  "no folder or file paths". SAF document ids (`primary:ROMs/...`) are not matched at all.
  **Fix:** scrub to the end of the line (or to `: ` / a quote), and also match `primary:\S` / `[0-9A-F]{4}-[0-9A-F]{4}:`.

### L4. The RA host-override receiver is exported without a permission
- `RetroAchievementsHostOverrideReceiver` (manifest, `exported="true"`). `isLocalHost` limits it to 127.0.0.1/localhost/[::1],
  and it only applies when Offline unlocks is set to RAOfflineProxy (`RaBridge.kt:215`). Within that, any app listening on a local
  port can point RA at itself and receive the token and posts. This is the same design as PPSSPP and Mupen64Plus AE (RAOfflineProxy
  needs it), so it is an accepted risk; noted for completeness.

### L5. Other launcher-only extras are taken from any app
- `MainActivity.kt:870` `EXTRA_MD5` is trusted as the game's key (per-game renderer rule, report md5). `:1175` `EXTRA_DD_CART` opens any URI,
  `file://` included, with the app's own rights (it is only loaded as a cartridge). `:1443` `EXTRA_LOAD_STATE` loads a state at
  start. None loses data. The H1 token would cover these too.

### L6. Rooms POST reads the whole body before its 2 KB check
- `server/rooms/src/index.js:37-38` calls `request.text()` and then checks `raw.length > 2048`. Reports checks `content-length` first.
  Bounded by Workers' own limits; add the same header check.

### L7. Workflows paste third-party text into issues
- `check-upstreams.py` `lines()` puts upstream commit subjects into issue bodies (Joey's repo). An `@name` in a subject notifies that
  user. `check-drivers.py` dedupes against only the newest 300 issues (`state=all`, 3 pages), so after that it opens a duplicate
  every day. Actions are pinned by tag (`actions/checkout@v4`), not by SHA.
  **Fix:** wrap subjects in backticks, and look the title up with the search API.

### L8. Licence question inherited from upstream: GLideN64 "GPL version 2" next to GPL-3.0 xBRZ in one core
- `GLideN64/LICENSE` (ra-known2): "licensed under the GNU General Public License version 2" (no "or later"; the GLideNHQ files
  say "version 2, or (at your option) any later version"). `TextureFilters_xbrz.cpp` is GPL-3.0 and is built into the same `.so`
  (`Makefile.common:374-378`). About lists GLideN64 as "GPL 2.0". If GLideN64 really means v2-only, that combination doesn't
  work (the same question as Project64 in the licensing memory). Upstream libretro ships the same build. This is for Joey to
  decide; it is not a code bug.

### L9. ScreenScraper login is only XOR-scrambled
- `build.gradle.kts` `ssScramble` with the key in source ("Joey64-box-art-key"). Anyone with the APK can recover it.
  ES-DE does the same and this was decided; noted only. The password itself is in no commit (checked below).

---

## Checked and fine

- reports `validate.js`: every field typed, length- and range-checked, control characters refused, unknown event types refused,
  64 KB / 200 events. Rejection is whole-report.
- reports SQL: every query uses bound parameters. A `db.batch()` counts as one query (Cloudflare D1 limits page:
  "counts as a single query"), so 3 queries a request is under the free plan's 50. Session insert has 32 bound params (limit 100).
- reports `store`: the report row is deleted again when the event batch fails, so a retry isn't counted twice. No IP or time finer than a day stored.
- reports `settings`: a `__proto__` key with a primitive value is dropped harmlessly. Objects and arrays are refused.
- rooms: id/secret 32-hex, md5 lower-cased and checked, players 1-4, code check character, private and multicast addresses refused,
  IPv4 poster must match the code, secret needed to change or delete by id, GET never returns secrets, stale rows deleted, TTL 75 s.
- rooms text: control, C1 and bidi override/isolate characters replaced, spaces squeezed, lengths capped.
- `wrangler.toml` (both): `account_id` pinned, `preview_urls = false`, no `LOCAL_TEST` var. `.wrangler/` and `.wrangler-dev.log` gitignored and not in git.
- `bundle-core.sh`: copies, removes the old-name .so, records branch, commit, dirty files and md5 of the copied file. `core-version.txt`
  md5 equals the bundled .so.
- `bundled.json` md5s equal both bundled Turnip files. `check-drivers.py` / `check-upstreams.py`: exit codes 0/1/2 handled by
  `|| [ $? -eq 1 ]`, so a network failure fails the job.
- `make-pakdb.py`: output regenerated from ra-known2 identical to the committed `pakdb.tsv`. RefMD5 depth-limited. (Its
  `"Controller Pack"` SaveType never occurs in the ini; only `None` does: dead but harmless.)
- `make-players.py`, `boxart/build-table.py`: parse libretro dats with checks; build-table refuses names with tab/newline.
- `build-shaders.sh`: `set -e`, validates the GLES output with glslc before writing it into the header.
- Workflows: only `schedule` and `workflow_dispatch` triggers (no `pull_request_target`), `permissions: contents: read, issues: write`,
  only `GITHUB_TOKEN`, no `${{ }}` of untrusted data inside `run:`.
- ProGuard: the only native lookups of our classes are `RaBridge.serverCall` / `onEvent` (`achievements.cpp:413-414`), both kept.
  No `Class.forName` / `getDeclared*` / kotlin-reflect in app code. `-keepnames` keeps class names for the JNI `Java_*` symbols.
- Manifest: FileProvider not exported, grants only. Paths are `cache/launch|updates|share` and `files/screenshots` only.
  `allowBackup=false`. UpdateStatusActivity and RestartActivity not exported. LauncherCheckActivity only in the debug manifest.
- Debug receivers: MainActivity's STICK/DUMP/LOGS/PROBLEM/RA_EVENT are registered only in `registerDebugStick()`, which returns when
  not `FLAG_DEBUGGABLE` (`:3027`). LauncherActivity's RA_FAKE/SHOW only under `BuildConfig.DEBUG` (`:130`). All are unregistered in onDestroy.
- Dev switches: `--es env/opts/cheats/driver/renderer/rom/ddcart`, `--ez` flags and `test.z64` all go through `devString` / `devFlag` / `devBuild`.
  `handedRom()` accepts only paths inside `cacheDir` in release. `cleanSaveName` and `saveNameOf` strip `/`.
- `files/dev_*` overrides: all 8 (`dev_cheats_base`, `dev_boxart_base`, `dev_covers_base`, `dev_tv`, `dev_read_threads`, `dev_update_feed`,
  `dev_rooms_base`, `dev_reports_base`) read only under `BuildConfig.DEBUG`. The debug network config is in `src/debug` only.
- Native env switches (`N64_*`, 60-odd) can only be set by `--es env` (debug) or the netplay extras (H1). `N64_NETPLAY_GW` set by `RouterAddress`.
- Signing: `keystore.properties` and `*.jks` / `*.keystore` gitignored. Without them the release comes out unsigned (documented in `docs/releasing.md`).
  `versionCode` / `versionName` bump is documented there.
- Secrets in git: the local.properties ScreenScraper password value occurs in 0 commits across all 1,051 (`git log --all -S`). No token or
  private-key patterns in full history. The devId value appears only where the account name is public anyway (the workers.dev addresses).
- Licences: LicencesTest checks both ways (every entry's file exists, every file is shown). Every library the core's
  `Makefile.common` builds (minizip, md5, libpng, zlib, xxHash, lightning, cxd4, GLideNHQ/xBRZ, libretro-common; libco is public domain)
  and the app's native libs (libc++, librashader, libadrenotools, Turnip/Mesa, miniupnpc, libnatpmp) has an entry.
