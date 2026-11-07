# Launcher audit, all phases (2026-11-07)

Every phase in `docs/launcher-build-plan.md`, checked against the code on `launcher/phase0` (859e393), the settings build order in `docs/settings-gap-audit.md`, and the README. "Open" means not built; "unchecked" means built but not tried where the plan says to try it.

## Summary

| Phase | State | Open |
|---|---|---|
| 0 Groundwork | Built | Forced landscape still on MainActivity; 4:3 / square / portrait never checked; Android 10 SQLite unchecked |
| 0b Updater | Built, install checked with Joey | (Licences list, Source code row, credits: built 2026-11-07); install-session fallback never exercised; repo still private |
| 1 Library, first run | Built | First-run screens 3-4 (where saves go): the reason they waited is gone; "You can do this later in Settings" line; not on the other four devices |
| 2 Box art | Built | (The download switches' row: built 2026-11-07, Settings › Library) |
| 3 In game, states | Built | Rail label "Continue · game"; states loading in RetroArch untested |
| 4 Controls | Built | Rumble reaches Player 1 only (host drops ports 1-3); Transfer Pak; real N64 pads; Switch Online N64 over USB |
| 5 Collections | Built | "Has achievements" filter (approved; RetroAchievements is now built); 4-player hacks |
| 6 Cheats | Built | Widescreen and 60 fps spots (Video's Aspect ratio still says Auto = 4:3 until they have one); unplaced libretro files; offline and Download cheats off unchecked |
| 7 RetroAchievements | Built, softcore | Unlock sound and volume; RAOfflineProxy status line (needs mockup) and PR (Joey posts); tap "Sign-in expired"; progress in save states; Joey's own sign-in and a real unlock through the queue; Hardcore and Leaderboards wait for Joey |
| 8 Settings | Appearance, Video, Audio, On screen, Saves, the stop-gap Library, RA, About built | Library tab, Emulation tab, General; rows 8-12 of the settings build order |

Tests: 245 unit tests pass. The branch is 9 commits ahead of main and 9 behind (merge is Joey's go).

## Found in this audit

**Gaps in what's built:**
1. **Built 2026-11-07 (Settings › Library).** **No way to turn box art or cheat downloads off or on.** The settings exist and are obeyed, but no screen shows them since Phase 2. The Cheats page even says "Downloading cheats is off in Settings › Library", a tab that doesn't exist (reachable only by restoring a backup with it off). Decision 3 already OK'd a stop-gap Library tab: ROM folders, the two switches (the data folder and Import now live in Saves).
2. **Built 2026-11-07 (Settings › Library).** **ROM folders can't be changed after first run.** No screen adds or removes a ROM folder once setup is done (same stop-gap tab).
3. **Built 2026-11-07 (About's licences and credits; 24 entries).** **No licence list.** About shows only "GPL 3.0 or later". The APK ships Compose/AndroidX/Kotlin (Apache-2.0), Commons Compress and XZ, rcheevos (MIT), libadrenotools (BSD-2), FidelityFX (MIT), Turnip/Mesa (MIT), the core (GPL-2.0-or-later parts, parallel-RDP MIT, ares-derived ISC) and Rubik (OFL). Only six licence texts are in `assets/licenses` (FidelityFX, Commons, libretro-database, Mesa, Rubik, XZ), and none is shown in the app. The About and licences mockup is approved. Needed before any public build.
4. **Built 2026-11-07.** **Has achievements** filter in Show: approved with Phase 5, waiting on Phase 7, which is now built.
5. **Built 2026-11-07.** **Rumble for players 2-4**: `setRumble` returns false for any port but 0.
6. **Built 2026-11-07.** **First-run screens 3-4** ("Where should Joey64 keep saves, states and screenshots?"): approved, and the data folder now works without the core change they waited for.
7. **Built 2026-11-07.** **The import's "You can do this later in Settings" line** can come back now, pointing at Settings › Saves.

**Approved mockups not built yet** (each already approved; build order is Joey's):
- Problem screens: game won't start, can't reach the ROMs folder, controller disconnected (pause and carry on), not enough space to save. (Crash recovery is built.)
- About's licences and credits (item 3 above). Built 2026-11-07.
- N64 paks page rows: Rumble on, Rumble strength, Transfer Pak.
- Save manager per game, Screenshot gallery.
- Touch controls (layout, editor, settings page, portrait).
- Netplay (screens v2; core netplay needs Joey's go).
- Texture packs (GLideN64 only today; parallel-RDP step B queued).
- Picture panel: shaders and bezels.

**Wanted, need a mockup first:** turbo, undo save/load, rewind, slow motion (frame advance: built 2026-11-07, a hotkey), overscan crop, Android TV, HDR, the Emulation and Library settings tabs, controls extras (latch, profiles, macros), 64DD, run-ahead, app language, ROM patches, shortcuts, cheat search, input display, clip recording, Expansion Pak off per game.

**Checks the plan asks for that haven't run:**
- Every phase on the Flip, Portal, Odin 3 and Duo: only the Thor since 2026-11-06 (the device queues, then Thor-only testing).
- Touch-only passes (touch was tried on single screens, never a whole phase).
- 4:3, square and portrait layouts (no such device; previews never made).
- The uncapped bench for phases that touch the game, after Phase 3 (pop-up cost): the overlays since (stats, badges) weren't benched uncapped; the stats overlay was checked at 60 fps.

**Out of date, fixed in this audit:** the build plan's "core opens saves by path, nothing calls the move", first-run screens 3-4's reason, Phase 3's data-folder line; README's save-states, never-lose-progress and launcher hook-up items.

**Parked, already tracked (README / build plan):** quitting after a second game in the same process leaves Joey64; SAF moves reset file dates; `.nomedia` gets the folder path; N64 video filters popup has no per-game dots; the game picker leaves out a second entry with the same title; Copy shows no pop-up; 8x wording in Video ("Strong devices only") on the Thor, where 8x runs as 4x.

## Compared with other apps, not on any list yet

Planned features were compared in `docs/settings-gap-audit.md` (2026-11-07). These came up here and are on no list; each is a suggestion for Joey:
- **Import from M64Plus FZ and Mupen64Plus AE**, not only RetroArch: their users are who Joey64 is for. Both keep mupen64plus standalone saves (`.eep`, `.sra`, `.fla`, `.mpk` per game), which would need converting to one `.srm` (Joey left RetroArch's old `.eep`/`.sra` alone on 2026-11-06).
- **Game info in Details** (year, developer, genre, players), as Lemuroid, Daijishō and RetroArch show from libretro-database or ScreenScraper. The players count is already bundled.
- **Recently played first**: a sort or a row (the library's Sort is kept, not designed).
- **Pause when the app goes to the background** with a state written then (README's never-lose-progress item).
- **TalkBack labels**: none of the reviewed emulators do it well; worth a look once screens settle.
