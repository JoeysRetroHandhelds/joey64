# Settings gap audit (2026-11-07)

Every setting in the emulators on the Thor, compared with what Joey64 lets a user change today.

**Sources:**
- Dolphin: the menu source (every SettingsFragmentPresenter section).
- ARMSX2: its settings search index, hotkey list and quick menu, from GitHub source.
- M64Plus FZ: every preference screen.
- PPSSPP, RetroArch, Eden and Azahar: their config files on the Thor.

DuckStation's settings file can't be read (permission denied). Cemu's has little in it.

**Relevance:**
- **High:** an N64 player would expect it.
- **Med:** nice to have.
- **Low:** niche, or about another console.

**Plan:** "planned" means it's already in `launcher-build-plan.md` (a mockup approved, or "wanted"). "new" means nothing for it yet.

## What Joey64 has today

- **Appearance:** Theme, Pop-ups theme.
- **RetroAchievements:** Hardcore, Leaderboards, Encore, Spectator, Unofficial, Rich presence, Challenge badges, Progress, Screenshot on unlock, Save badges for offline, Pop-up place and time, Badge place, Sign in and out.
- **About:** updates, device info.
- **Controls, per player and per game:** controller, buttons, D-pad, C buttons, stick, dead zone, pak.
- **Hotkeys:** hotkey button, Save, Load, Next and Previous slot, Pause menu, Fast-forward (mode, speed), Screenshot, GameShark, Reset, Quit (to library or exit).
- **Other:** cheats, collections, save states with slots, the pause menu.

Video, audio and emulation have no user settings. They're automatic, or dev switches only: renderer, upscale, aspect, swap interval, smoother timing.

## 1. Video and picture

| Setting | Who has it | N64 | Plan |
|---|---|---|---|
| Aspect ratio (4:3, 16:9 stretch, full screen) | all | High | new (dev switch `N64_ASPECT` only) |
| Internal resolution / upscale | all | High | new (default native, upscale is the user's choice per memory) |
| Integer scaling | RA, PPSSPP, ARMSX2, Azahar, FZ (angrylion) | High | new |
| Shaders (CRT, LCD, sharp) and bezels | RA, ARMSX2, PPSSPP, Dolphin, FZ | High | planned (Game settings › Picture) |
| Overscan crop | RA, Dolphin, FZ (parallel-RDP) | High | planned (wanted) |
| parallel-RDP VI options: VI anti-aliasing, divot filter, dedither, gamma dither, bilinear, SSAA downsample, native texture LOD, native TEX_RECT, deinterlace | FZ, RA core options | High | new |
| Widescreen hack / patches | Dolphin, ARMSX2, FZ (GLideN64) | High | partly: widescreen cheats get their own spot |
| Renderer choice (parallel-RDP / GLideN64) | FZ, RA | Med | new (dev only; GLideN64 is the fallback) |
| Brightness, contrast, saturation, gamma | ARMSX2, Dolphin (colour correction), FZ (gamma) | Med | new |
| Sharpening (FSR / CAS) | ARMSX2, Eden | Med | new |
| Anisotropic, texture filtering, mipmaps | Dolphin, ARMSX2, PPSSPP, Eden | Low (parallel-RDP is accurate, not filtered) | new |
| Frame skip | PPSSPP, ARMSX2, FZ | Low | new |
| VSync / frame pacing / swap interval | Dolphin, PPSSPP, RA, Eden, Azahar | Med | new (dev `N64_SWAP_INTERVAL`) |
| Screen orientation in game, expand into cutout | Dolphin, ARMSX2, FZ, Azahar, PPSSPP | Med (portrait for phones) | new |
| Custom GPU driver picker | Dolphin, Eden, PPSSPP | Med | new (we bundle drivers; no user pick) |
| Texture packs, dump textures | Dolphin, ARMSX2, PPSSPP, Azahar, FZ | Med | planned (texture packs) |
| HDR | RA | Low on handhelds | planned (HDR) |
| Black frame insertion, run-ahead, frame delay | RA | Low / Med (run-ahead cuts lag) | new |

## 2. On-screen stats (performance overlay)

Joey64 has nothing a user can turn on.

| Setting | Who has it | N64 | Plan |
|---|---|---|---|
| FPS, VPS, speed % | Dolphin, ARMSX2, PPSSPP, Eden, RA | High | new |
| Frame-time graph | Dolphin, ARMSX2, Eden | Med | new |
| CPU/GPU usage, battery temperature, RAM, device and GPU model | ARMSX2, Eden | Med (handheld users love it) | new |
| Position, size, colour | ARMSX2, Eden | Med | new |
| Show which buttons are pressed | ARMSX2, RA | Low | planned later (input display) |
| Notifications on/off by type (state saved, fast-forward, cheats applied) | RA, ARMSX2 | Med | new (pop-ups can't be turned off by type) |

## 3. Audio

Joey64 has no audio settings at all.

| Setting | Who has it | N64 | Plan |
|---|---|---|---|
| Volume, mute | all | High | new |
| Fast-forward: mute or lower volume | ARMSX2, RA | High (goes with the new fast-forward) | new |
| Audio latency / buffer size | Dolphin, ARMSX2, PPSSPP, RA, FZ | Med | new (dev `N64_AUDIO_*` only) |
| Time stretch / keep pitch | Dolphin, FZ, Azahar | Med | new |
| Swap left and right | ARMSX2, FZ | Low | new |
| Mix with other apps / respect silent mode | PPSSPP | Low | new |
| Menu sounds | ARMSX2, RA | Low | new |

## 4. Emulation and speed

| Setting | Who has it | N64 | Plan |
|---|---|---|---|
| Slow motion | ARMSX2, RA, Dolphin (speed limit) | Med | planned (wanted) |
| Frame advance | RA, FZ, ARMSX2 | Low | planned (wanted) |
| Rewind | RA | Med | planned (measure cost first) |
| Speed limit off / custom % | Dolphin, ARMSX2, Eden, Azahar | Low (fast-forward covers it) | new |
| Overclock (count per op) | FZ, RA core option | Med (fixes lag in some games) | partly: smoother timing is automatic per game |
| VI refresh rate | FZ | Low | new |
| Expansion Pak off per game | FZ, RA | Low | planned later |
| 64DD support (IPL, .ndd disks) | FZ, RA | Med (F-Zero X Expansion, Mario Artist) | **new: nowhere in the plan** |
| Transfer Pak (Game Boy ROM and save) | FZ, RA | Med (Pokémon Stadium) | planned (paks) |
| CPU core (dynarec / interpreter), Ignore TLB, Randomise interrupts | FZ advanced | Low | new (gamedb handles per game) |
| Sustained performance mode / CPU hints | PPSSPP, ARMSX2 | Med | new (we have thermal code, no user setting) |

## 5. Saves and states

| Setting | Who has it | N64 | Plan |
|---|---|---|---|
| Undo save / undo load | DuckStation, PCSX2 | Med | planned (wanted) |
| Save manager, backups | FZ | Med | planned (save manager) |
| Auto-save on quit, auto-load on start (on/off) | RA, FZ | Med | new (always on today) |
| How many auto saves to keep | FZ, RA | Low | new |
| Data folder location | FZ, Eden, Dolphin | Med | partly planned |
| Back up / restore or export / import all app data | ARMSX2, FZ | Med | **new** |
| State compression | RA | Low | new |

## 6. Hotkeys

These are actions other apps can bind that Joey64 can't.

| Action | Who has it | N64 | Plan |
|---|---|---|---|
| Save to slot N / load from slot N (each slot its own key) | ARMSX2 (0–9) | Med | new |
| Save state and exit | ARMSX2 | Med | new |
| Show or hide the stats overlay | ARMSX2, RA | Med | new (needs section 2) |
| Slow motion, frame advance, rewind | ARMSX2, RA, FZ | Med | planned (with the features) |
| Speed up / speed down step | FZ | Low | new |
| Volume up / down, mute | RA | Med | new |
| Open achievements | ARMSX2 | Med | new (pause menu has it) |
| Upscale up / down | ARMSX2 | Low | new |
| Next shader / shader on-off | RA | Low | new (with Picture) |
| Turbo on/off | RA | Low | planned (turbo) |
| Single-button hotkeys (no hotkey button) | ARMSX2, RA, FZ | Med | new (only Pause menu works alone) |

## 7. Controls

| Setting | Who has it | N64 | Plan |
|---|---|---|---|
| Turbo / autofire | RA, PPSSPP, ARMSX2, DuckStation | Med | planned (wanted) |
| Latch: tap to hold a button down | ARMSX2 | Med (holding Z or R) | partly: touch only ("hold Z, L or R by tapping") |
| Stick feel: sensitivity, outer dead zone, anti-dead zone, response curve | ARMSX2, PPSSPP, RA | **High** (N64 stick range differs from modern sticks; walk vs run) | new (only dead zone today) |
| Invert / swap stick axes | ARMSX2, Dolphin | Low | new |
| D-pad acts as stick | ARMSX2 | Med | new |
| Trigger threshold (how far before L2/R2 counts) | PPSSPP, RA | Low | new |
| Rumble strength | RA, Dolphin, Eden | Med | planned (paks: Rumble strength) |
| Gyro or tilt as stick | ARMSX2, PPSSPP, RA | Low | new |
| Named controller profiles (save and load) | Dolphin, FZ | Med | new |
| Macros (one button, a button sequence) | ARMSX2 | Low | new |
| Touch controls | all | High (phones) | planned |
| Block the Home button while playing | ARMSX2 | Low | new |
| Mappable Back and volume keys | FZ | Low | new |

## 8. Library and app

| Setting | Who has it | N64 | Plan |
|---|---|---|---|
| Sort (name, last played, play time) and region filter | FZ, RA | Med | partly: collections and filters |
| Cover size, names on tiles | ARMSX2, FZ | Med | new |
| App language | Eden, Dolphin | Med (before Play Store) | new |
| Picture-in-picture | Eden | Low | new |
| Home-screen shortcuts | many | Med | planned later |
| ROM patches (.ips, .bps) | RA | Med | planned later |
| Android TV | Dolphin, FZ | Med | planned |
| Netplay | FZ, RA, PPSSPP | Med | planned |
| Cheat search | Project64, DuckStation | Low | planned later |

## 9. Per-game overrides

ARMSX2, Dolphin, Eden, FZ and RA let **every** setting above be set per game. Joey64 has per-game controls only, plus planned Picture and Texture pack. Per-game video, audio and emulation are listed under "Not in this plan".

## Biggest gaps

**Not planned, high value:**
1. Video settings a user can change: aspect, upscale, integer scaling, parallel-RDP VI filters.
2. Stats overlay (FPS, speed, temperature) plus its hotkey.
3. Audio: volume, mute, and lower volume while fast-forwarding.
4. Stick feel: sensitivity, outer and anti-dead zone, curve.
5. 64DD.
6. Back up and restore app data.
7. More hotkeys: save to slot N, save and exit, volume, single-button hotkeys.

Each needs a mockup from Joey first.

**Already planned and covers the rest:** Picture (shaders, bezels), overscan, turbo, undo, rewind, slow motion, touch controls, paks with rumble strength and Transfer Pak, save manager, texture packs, netplay, Android TV.

## Joey's picks, tab by tab (2026-11-07, no mockups yet)

**1. Video and picture.**
- **Keep:**
  - Aspect ratio
  - Upscale
  - Integer scaling
  - parallel-RDP VI options, under Advanced
  - Renderer choice, under Advanced
  - Brightness, contrast, saturation, gamma
  - Sharpening (FSR / CAS)
  - VSync / frame pacing, under Advanced
  - In-game orientation
  - Draw into the cutout
  - GPU driver picker, under Advanced
  - Run-ahead: strong devices only, measure the cost first
  - Black frame insertion
  - Per game for every kept setting
- **Drop:**
  - Anisotropic, texture filtering, mipmaps
  - Frame skip

**2. On-screen stats.**
- **Keep:**
  - FPS
  - Speed %
  - VPS
  - Frame-time graph
  - CPU and GPU load
  - Battery temperature and charge
  - RAM use
  - Device, chip, GPU, driver and version
  - Settings summary (renderer, upscale, driver)
  - Presets: Off, Simple, Full, Custom
  - Position and size (no colour or background)
  - A hotkey to cycle the overlay
  - Pop-ups on or off by type
- **Left as planned later:** the input display.

**3. Audio.**
- **Keep:**
  - Volume
  - Mute
  - Fast-forward sound: Mute, Quieter or Full
  - Latency: Auto, Low or Safe, under Advanced
  - Time stretch: test first
  - Mono
  - Play alongside other apps
  - Volume and mute hotkeys
  - Per game: volume and latency
- **Drop:**
  - Swap left and right
  - Respect silent mode
  - Menu sounds (for now)
  - Sound while paused

**4. Emulation and speed.**
- **Keep:**
  - Slow motion
  - Frame advance
  - Rewind: measure the cost first
  - Overclock: Auto, Off, 1x, 2x or 3x, under Advanced, per game
  - Expansion Pak off per game
  - 64DD: check the core supports it first
  - Transfer Pak
  - CPU core, under Advanced, per game
  - Performance mode: Balanced, Max or Battery saver
  - Thermal fallback on or off, under Advanced
- **Drop:**
  - Speed limit
  - VI refresh override
  - TLB, interrupt and PI DMA switches
  - PAL60

**5. Saves and states.**
- **Keep:**
  - Undo save and undo load
  - Save manager
  - Auto-save on quit: on or off
  - Continue: Ask, Always or Never
  - How many auto saves to keep
  - Recovery state: on or off and how often, under Advanced
  - Data folder location
  - Back up and restore all app data, with exporting only saves or only settings
  - Import from RetroArch again, in Settings
- **Drop:**
  - State compression
  - State picture switch
  - Save RAM write timing
  - Cloud sync (for now)

**6. Hotkeys.**
- **Keep:**
  - Cycle the stats overlay
  - Slow motion
  - Frame advance and pause/unpause
  - Rewind, with rewind
  - Volume up and down, mute
  - Open achievements
  - Upscale up and down
  - Next shader and shader on-off, with Picture
  - Turbo on/off, with turbo
  - Undo load, with undo
  - Single-button hotkeys, an option on each
  - Swap discs, with 64DD
- **Drop:**
  - Per-slot save and load keys
  - Save and quit
  - Speed step
  - Per-game hotkeys

**7. Controls.**
- **Keep:**
  - Turbo
  - Latch
  - Stick range
  - Response curve
  - Anti-dead zone, under Advanced
  - Octagon gate
  - Invert and swap axes
  - D-pad as the stick
  - Trigger threshold, under Advanced
  - Rumble strength
  - Named profiles
  - Macros
  - Touch controls
  - Raphnet: test first
- **Drop:**
  - Gyro
  - Block Home
  - Mouse
- **Also dropped:** mappable volume keys (Joey 2026-11-07). Back is already mappable, and so are rear buttons that send their own key.

**8. Library and app.**
- **Keep:**
  - Sort
  - Region filter, in Show
  - Cover size and columns
  - Names on tiles
  - Full or short names
  - App language: later, but built ready for it
  - Home-screen shortcuts
  - ROM patches
  - Android TV
  - Netplay
  - Cheat search
  - Hide games
  - Several ROM folders
  - Rescan on start or by hand
  - Download box art: On or Wi-Fi only
- **Drop:**
  - Picture-in-picture
  - Boot animation

**9. Per game, and RetroAchievements extras.**
- **Keep:**
  - Game settings from the pause menu and from the library
  - A mark on changed settings, with Reset to All games
  - Copy one game's settings to another
  - Unlock sound picker and volume (Phase 7)
  - The remaining pop-up types, with tab 2
- **Drop:**
  - Custom RA server
  - Separate leaderboard pop-up places
- **Already there:** the game-start summary.

**10. Shaders and overlays** (added after Joey asked; beyond the approved Picture panel).
- **Keep:**
  - Your own RetroArch .slangp presets: check a runtime such as librashader for both renderers
  - Shader settings as live sliders
  - Stack several shaders, under Advanced
  - Shader scale, under Advanced
  - More built-ins: NTSC composite, CRT light and heavy, colour-blind modes
  - An overlay image over the game, with opacity
  - Bezels that follow the aspect
  - Place and size the game inside a bezel, under Advanced
  - An auto shader by screen, as a default you can change
  - Named shader presets for any game
- **Drop again:** downloadable bezel packs (licences unclear, decided 2026-11-05).

## Kept list, in suggested build order

Each item needs Joey's mockup first. The order puts what players miss most first, and what shares a screen together.

1. **Settings › Video** (one tab, then the same rows per game). **Built 2026-11-07** (launcher/phase0; `docs/launcher-build-plan.md`):
   - aspect
   - upscale
   - integer scaling
   - brightness, contrast, saturation, gamma
   - sharpening
   - orientation
   - cutout
   - black frame insertion
   - Advanced: renderer, parallel-RDP VI options, frame pacing, GPU driver
2. **Settings › Audio.** **Built 2026-11-07** (launcher/phase0), all but time stretch: tested, not now (Joey 2026-11-07), tracked in the README:
   - volume
   - mute
   - fast-forward sound
   - mono
   - play alongside other apps
   - Advanced: latency, time stretch (test first)
3. **Stick feel, in Controls.** **Built 2026-11-07** (launcher/phase0), with Left stick and D-pad as the Stick choice for "D-pad as the stick":
   - stick range
   - response curve
   - octagon gate
   - D-pad as the stick
   - invert and swap axes
   - Advanced: anti-dead zone, trigger threshold
4. **Stats overlay.** **Built 2026-11-07** (launcher/phase0, Settings › On screen), as the approved mockup:
   - presets: Off, Simple, Full, Custom
   - FPS, speed %, VPS, frame times
   - CPU and GPU load, battery, RAM
   - device, chip, GPU, driver and version
   - settings summary
   - position and size
   - pop-ups on or off by type
5. **Game settings:**
   - from the pause menu and from the library
   - changed marks with Reset to All games
   - copy to another game
6. **New hotkeys and single-button hotkeys:**
   - cycle the overlay
   - volume and mute
   - open achievements
   - upscale up and down
   - pause/unpause
7. **Saves:**
   - auto-save on quit on or off
   - Continue: Ask, Always or Never
   - how many auto saves to keep
   - recovery state settings
   - back up and restore all app data, with exporting saves only or settings only
   - Import from RetroArch again
   - data folder location
8. **Emulation:**
   - performance mode: Balanced, Max or Battery saver
   - Advanced: overclock, CPU core, thermal fallback
   - Expansion Pak off per game
9. **Library:**
   - sort
   - region filter
   - cover size and columns
   - names on tiles
   - full or short names
   - hide games
   - several ROM folders
   - rescan setting
   - box-art download setting
10. **Controls extras:**
    - latch
    - named profiles
    - macros
    - Raphnet (test first)
11. **With features already planned:**
    - turbo, with its hotkey
    - undo, with its hotkey
    - slow motion, frame advance and rewind, each with hotkeys (rewind: measure first)
    - Picture shaders and bezels, with their hotkeys, and tab 10's additions
    - rumble strength and Transfer Pak
    - touch controls
    - unlock sound
    - netplay
    - Android TV
    - ROM patches
    - shortcuts
    - cheat search
12. **New features:**
    - 64DD with disc swap (check the core first)
    - run-ahead (strong devices, measure first)
    - app language (later, but build ready for it)
