# Joey64

A Nintendo 64 emulator for Android handhelds, built on the mupen64plus-next libretro core, with its own
controller-first launcher.

**Status:** in development; no public release yet. The first testing build will be a GitHub pre-release here.

## What it does

- **Library**: a box-art grid of your ROM folders (zip, z64, n64, v64), search, collections, regional copies
  folded into one game, 64DD disks with your own BIOS.
- **Video**: parallel-RDP on Vulkan or GLideN64, with the GPU driver picked per device (bundled Turnip on
  Adreno); native resolution by default, upscaling, crop edges, widescreen codes, shaders (RetroArch slang
  presets through librashader) and overlays.
- **Play**: save states (and RetroArch's), rewind, fast-forward, slow motion, turbo, cheats, RetroAchievements and
  netplay rooms.
- **Controls**: N64-style controllers mapped automatically, per-game controls and profiles, Controller, Rumble
  and Transfer Paks for each player, touch controls, Android TV.
- **Per game**: settings that override All games, and fixes some games need, applied on their own.

## Devices

Android 10 or newer, 64-bit ARM. Tested on the Retroid Pocket Flip (Mali), Retroid Pocket Duo, AYN Odin 2 Portal,
AYN Odin 3 and AYN Thor (Adreno).

## Building

Android Studio's JDK and the NDK; from the repo root:

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
librashader, rcheevos, miniupnpc and libnatpmp.
