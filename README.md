# Joey64

A Nintendo 64 emulator for Android handhelds. It runs its own N64 core,
[joey64-core](https://github.com/JoeysRetroHandhelds/joey64-core) (a fork of Mupen64Plus-Next, changed for handhelds),
inside its own controller-first app: a box-art library, settings for every game, and an in-game menu, all made to
be used with a gamepad (touch and TV remotes work too).

**AI disclosure:** AI was used for the entire app. The code (the app and our core changes) is written with
Anthropic's Claude through Claude Code, which also runs most of the device testing. I decide what goes in,
design the screens and play it on real handhelds.

**Status:** early. Releases are on this repo's Releases page as an APK to install by hand; the app tells you
when a new one is out.

## What's special

- **It sets itself up for your device.** Joey64 looks at the GPU and its driver and picks the renderer
  (parallel-RDP on Vulkan, or GLideN64), the driver (a bundled Turnip build on Adreno, the system driver on
  Mali) and the tuning that measured best on that kind of chip. There is nothing to configure before the first
  game, and every choice can still be changed.
- **Fixes for each game, built in.** A game that needs a setting, a cheat-style patch or a different renderer
  gets it automatically, matched by its ROM, with the reason written down in `gamedb/games.json`. That includes
  ROM hacks, prototypes, homebrew and 64DD disk dumps that don't run as dumped.
- **Lower lag.** Each frame is shown about one frame (~16 ms) sooner than mupen64plus normally does. Audio
  uses an exclusive low-latency stream where the device allows it, and the audio buffer grows only when a device
  needs it. A frame pacing setting trades a little lag for steadier motion on 120 Hz screens.
- **Faster parallel-RDP.** Joey64 skips refreshes that would show the same picture again, and uses a "smart
  sync" that waits for the GPU only when the game reads back what it drew. Games that need exact framebuffer
  reads (Pokémon Snap's photos, Resident Evil 2) keep them without paying for full synchronous mode everywhere.
- **Netplay built for handhelds.** Host a room on your own device and share an 8-character room code. Rooms on
  the same Wi-Fi show up by themselves, and public rooms are listed worldwide. The router's port is opened for
  you (NAT-PMP or UPnP) and input delay adjusts to the connection while you play. No relay server sits in the
  middle. It supports up to 4 players, and checks that everyone has the same ROM.
- **Works with RetroArch.** Joey64 reads and writes RetroArch's Mupen64Plus-Next saves and save states as they
  are, imports a RetroArch setup on first run, and uses your RetroArch shader presets (.slangp, through
  librashader) and overlay packs straight from their folders.
- **Transfer Pak for every player.** Each player can have their own Game Boy game and save (Pokémon Stadium 1
  and 2), read from your Game Boy folders, including RetroArch's Gambatte or mGBA saves.
- **64DD.** Disk games run with your own IPL BIOS, alone or with their cartridge (F-Zero X Expansion Kit,
  Dezaemon DD), with disk saves.
- **Your progress is kept safe.** A recovery state is taken every few minutes and whenever the app goes to the
  background. If the app is ever closed or crashes mid-game, the library offers to carry on from there. On a
  crash, the game's own save is written to disk before the app closes. Saves are backed up automatically, and
  your saves, states and settings can live in any folder you pick, for example one you sync.
- **One app for every screen.** Layouts adapt to 16:9, 4:3, portrait and small second screens (the AYN Thor's
  bottom screen). Android TV is supported, with a TV-safe layout and remote-friendly controls.

## Features

### Library
- Box-art grid of your ROM folders: .z64, .n64, .v64, plain or in .zip or .7z, plus 64DD .ndd and .d64 disks.
  The library notices new games by itself, or only when you ask.
- Box art from ScreenScraper, matched by the ROM's MD5. Covers ScreenScraper doesn't have come from Joey64's own
  set. You can pick your own picture or hide a cover.
- One tile per game: regional copies, revisions and betas fold together (USA, Europe, Japan and others), and
  the game's options pick which version plays. A short grey line tells apart copies that share a name.
- Search, Favourites, your own collections, and filters (4 players, hacks and translations, never played, has
  achievements). Sort by name, recently played, most played or recently added. Hide games you don't want to
  see.
- Cover size, names under covers, short or full names, and tidy names ("The Legend of Zelda", not "Legend of
  Zelda, The").
- Per-game Saves page (back up, restore or delete the game's save, play from any state) and Screenshots page
  (view, share, delete).
- Continue where you quit, or pick where to start from a list of quit saves and slots with their pictures.

### Video
- Renderers: parallel-RDP (Vulkan, accurate) and GLideN64 (OpenGL ES), chosen per device and per game, or by
  hand.
- Resolution: native by default, upscaling up to 4x or 8x where the GPU allows it.
- Picture shape: Auto, 4:3, 16:9, stretch, integer scaling. Widescreen and 60 fps: switch them on and each game
  that has a code for it gets one (or pick a game's version yourself); games with their own 16:9 mode get the
  16:9 picture for it.
- Crop edges and trim the game's own black border.
- Look: the N64's filters (VI anti-aliasing, dither filter, bilinear) or sharp presets, deinterlacing,
  supersampling.
- Colour (brightness, contrast, saturation, gamma), sharpening (AMD CAS or FSR 1), colour-blind help, black
  frame insertion on 120 Hz screens.
- Shaders: sharp pixels, smooth, CRT light and heavy, LCD grid, NTSC composite, up to 4 stacked, each adjustable,
  your own saved presets, and RetroArch .slangp presets.
- Sides of the picture: a blurred copy of the game, a Joey64 frame, your own image, or a RetroArch overlay pack
  (bezels), matched to each game by name. An overlay image can also go over everything.
- Previews in the settings, and a side panel in game that changes the picture live.

### Controls
- Up to 4 players, each with any controller, the built-in controls or touch controls. Common controllers are
  mapped to the N64 layout automatically.
- Remap every button, per player and per game. Profiles to save and load a mapping.
- Stick feel: range, response curve, octagon gate like a real N64 stick, invert, swap, dead zone and
  anti-dead zone, trigger threshold, with a live preview.
- Tap to hold, turbo (5-20 presses a second) and button macros.
- Paks per player: Controller Pak, Rumble Pak (with strength, on the controller or the device), Transfer Pak,
  chosen automatically for each game.
- Touch controls with a layout editor (move, resize, add a D-pad, fast-forward, save or load state, screenshot
  and macro buttons). They hide when a controller is used and come back on a touch.
- Hotkeys (a hotkey button plus another button, or any button on its own): save and load state, change slot,
  fast-forward, rewind, slow motion, screenshot, volume, mute, upscale, pause, frame advance, reset, turbo on or
  off, next shader, stats overlay, the GameShark button, undo load or save state, quit.
- A controller that disconnects pauses the game until it comes back (or another player takes over).

### Playing
- Save states: 10 slots with pictures, plus the quit save and up to 10 older quit saves, all in RetroArch's
  format. Undo the last load or save.
- Rewind (up to 5 minutes back), fast-forward (125-400%, toggle, hold or by trigger pressure), slow motion, frame
  advance.
- Cheats: GameShark lists downloaded for each game, with notes, and a GameShark button for codes that need one.
- RetroAchievements: sign in, unlock pop-ups and sounds, progress, subsets as tabs. Unlocks made offline are
  sent later, or through RAOfflineProxy. Softcore only for now.
- Emulation settings per game: overclock, CPU core, Expansion Pak, thermal fallback.
- Audio settings: volume, latency, mono, fast-forward sound, playing alongside music from other apps.
- On-screen stats: frame rate, speed, a frame-time graph, CPU, GPU, battery, temperature, RAM, driver and
  renderer, in a corner or down the side of the picture. Also pop-ups for states, screenshots and hotkeys.

### Settings and data
- All games or one game: Video, Emulation, Audio and Controls settings can be set for everything, then changed
  for a single game. Game settings shows and resets what a game has of its own, and copies it to another game.
- Data folder: saves, states, screenshots, cheats and backups in Joey64's own storage or any folder you pick,
  moved over for you.
- Save backups on every start or daily, and a full Joey64 backup (everything, saves only or settings only) to a
  .zip you can restore.
- First run: ROM folder, where saves go, import from RetroArch, RetroAchievements sign-in.
- Updates: the app checks this repo's releases and installs a new version when you say so.
- Optional, off unless you turn it on: anonymous reports (sessions per GPU and driver, crashes, missing covers),
  and an occasional request to send a save state for a game we're working on. Settings › About › Share logs
  makes a zip of recent session logs to send with a bug report.

## Devices

Android 10 or newer, 64-bit ARM (arm64-v8a).

**Compatibility is currently known only for the devices we've tested:**

| Device | Chip / GPU |
|---|---|
| AYN Thor | Snapdragon 8 Gen 2, Adreno 740 |
| AYN Odin 2 Portal | Snapdragon 8 Gen 2, Adreno 740 |
| AYN Odin 3 | Snapdragon 8 Elite, Adreno 830 |
| AYN Odin 3 Portal | Snapdragon 8 Elite, Adreno 830 |
| Retroid Pocket Duo | Adreno 722 |
| Retroid Pocket Flip | Unisoc T618, Mali-G52 |

Joey64 may install and run on other Android devices, but nothing has been checked on them yet, and the
per-device choices (driver, renderer, tuning) have only been measured on these. Reports from other devices
help: Settings › About › Send reports, or Share logs with an issue here.

## Game compatibility

**[Game compatibility list](https://joeysretrohandhelds.github.io/joey64-data/compat/)**: how each game runs in
Joey64 (Runs, Starts, Broken or Not checked yet), from our test runs on the devices above. It's updated after each
full run of the library. A game that runs wrong for you: open an issue with the "Game doesn't run right" form.

## Building

Android Studio's JDK and the NDK (27.3); from the repo root:

```
./gradlew assembleDebug
```

The core is built separately from [JoeysRetroHandhelds/joey64-core](https://github.com/JoeysRetroHandhelds/joey64-core)
(a fork of [libretro/mupen64plus-libretro-nx](https://github.com/libretro/mupen64plus-libretro-nx)).
`devtools/bundle-core.sh` copies a core build into `app/src/main/jniLibs/arm64-v8a/` and records the core
commit it came from in `core-version.txt`. Release builds: `docs/releasing.md`.

## Layout

```
app/src/main/java/com/joeysretro/joey64/   the app: game screen (MainActivity), launcher/, controls/, video/, ...
app/src/main/cpp/                          the libretro host: core loading, Vulkan/GLES video, audio, run loop
gamedb/                                    what each game needs (games.json; see gamedb/README.md)
server/                                    the opt-in reports server and the netplay rooms list (Cloudflare Workers)
devtools/                                  core bundling, driver checks, shader and data builders
branding/                                  the icon and wordmark
```

## Licence

Joey64 is GPL-3.0-or-later (`LICENSE`). The core is GPLv2+ and compiles in GNU lightning (LGPLv3+); the launcher's
AndroidX and Compose libraries are Apache-2.0, so the app as shipped is GPLv3. Third-party notices are in the app
(Settings › About › Licences) and under `app/src/main/cpp/third_party/`.

Built on mupen64plus and mupen64plus-next, parallel-RDP and parallel-RSP, GLideN64, Mesa Turnip, libadrenotools,
librashader, rcheevos, miniupnpc and libnatpmp. Box art from ScreenScraper; cheat lists and Joey64's own covers
from [joey64-data](https://github.com/JoeysRetroHandhelds/joey64-data).
