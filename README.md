# N64 Handheld (working title)

Standalone Android N64 emulator built on the **mupen64plus-next** libretro core,
focused on handheld devices (Retroid, Odin, AYN, Anbernic).

## Status
Runs games on real handhelds (Retroid Pocket 3.5 Flip, Odin 2 Portal, Retroid Pocket Duo, Odin 3)
with parallel-RDP on Vulkan (bundled Turnip on Adreno) or GLideN64. No menus yet: games are started
over adb (see CLAUDE.md).

## Layout
```
app/src/main/java/.../MainActivity.kt   Surface + lifecycle, picks core/ROM
app/src/main/java/.../NativeBridge.kt   JNI bindings
app/src/main/cpp/libretro_host.cpp      dlopen core, environment callbacks, run loop
app/src/main/cpp/egl_context.cpp/.h     EGL/GLES3 context + FBO for the core
app/src/main/cpp/audio_out.cpp/.h       AAudio output
app/src/main/cpp/libretro.h             Download from libretro/libretro-common (see below)
```

## Getting the core
Build `mupen64plus_next_libretro_android.so` from
https://github.com/libretro/mupen64plus-libretro-nx (or take it from a RetroArch
nightly), and place it in `app/src/main/jniLibs/arm64-v8a/`.

Copy `libretro.h` from https://github.com/libretro/libretro-common/blob/master/include/libretro.h
into `app/src/main/cpp/`.

## Licensing
The core is GPLv2+, so this app ships as GPL. Keep ares-derived code under its ISC notice.

## Roadmap
Focus now: performance, compatibility and latency; menus come after.

- [x] M0 – Host core, load ROM, GLES video, AAudio, run loop
- [x] M1 – Input: positional gamepad mapping, analog sticks, fast-forward (unbatched input on Android 11 still open)
- [x] M2 – Vulkan HW context for parallel-RDP, Turnip/system driver pick per GPU
- [ ] M3 – Per-game / per-GPU profile DB (ROM hash + GPU vendor/driver → renderer + core options); a few rules are hard-coded so far
- [~] M4 – Frame pacing: big-core pinning, audio sync, low-latency audio, timed pacing on 120 Hz (opt-in) done; speed cap when audio doesn't pace (New Tetris runs 2x on 120 Hz), thermal fallback open
- [ ] M5 – Handheld frontend: game list, settings, controller profiles, controller-only UI
- [ ] M6 – Test harness: compatibility runs across a ROM library per device (scripted, first pass done), n64-systemtest results, Pokémon Snap photo check
- [ ] Paks: Controller Pak works (player 1, saved in the .srm). Rumble Pak (vibrate device/controller; pick memory or rumble per game), Transfer Pak (Game Boy ROM + save for Pokémon Stadium 1/2), paks for players 2-4
- [ ] Save states, then run-ahead (removes games' built-in input lag)
- [ ] Performance on weak devices: parallel-RDP command-ring syscalls, NEON RSP vector path

### Current focus: performance, compatibility, latency (revisit as they come up)
- [ ] Crash recovery: a core crash (e.g. SM64 Last Impact) shouldn't take the app down; keep saves safe, show an error
- [ ] Core emulation fixes for what M6 finds (Last Impact ERET error, Banjo-Tooie music, OoT pause freezes); decide how to track upstream (`gliden64-update` merge pending)
- [ ] 50 Hz (PAL) games on 120 Hz panels (PAL-only games and RetroAchievements sets that only support PAL): no 50/100 Hz or VRR modes on our devices, so use timed pacing to show frames in a fixed 2,2,3,2,3 refresh pattern (5 frames per 12 refreshes, 17/25 ms). Optional frame blending later. Not 60 Hz mode (17/33 ms) or running at 60 fps
- [ ] Optional overclock / 60 fps patches for 20-30 fps games (GoldenEye, Perfect Dark, Banjo), per game
- [ ] Analog stick range and calibration per device (real N64 sticks don't reach as far as modern sticks, so movement feels too sensitive unless scaled)
- [ ] Battery and heat: measure power use, thermal fallback (M4)
- [ ] Timing accuracy: no emulator matches N64 timing exactly (ares is closest), so scenes that slowed down on hardware run faster and a few games built around that slowdown misbehave. Compare game speed against ares on known cases, tune per game (CountPerOp) in the profile DB
- [ ] Games that need the accurate path: custom microcodes (Factor 5: Rogue Squadron, Battle for Naboo, Indiana Jones) and framebuffer effects (Snap photos, blur/motion effects) break on GLideN64; the profile DB should keep them on parallel-RDP + LLE RSP, or warn on weak devices that fall back

### Later
- [ ] RetroAchievements (reuse the mupen64plus-ae fork's work; note it's GPLv3)
- [ ] Display options: widescreen hacks, integer scaling, filters/CRT shaders, GLideN64 texture packs
- [ ] Shader support: load RetroArch shader presets (slang `.slangp` for Vulkan, GLSL `.glslp` for GLideN64/GLES) so its CRT, LCD/handheld-grid, scaling and smoothing shaders work unchanged (libretro/slang-shaders, glsl-shaders); multi-pass chains, preset parameters adjustable in a menu, per-game/per-device default; also other formats where easy (ReShade-style single-pass, librashader as a possible base)
- [ ] Widescreen options per game, from the LaunchBox "N64 Widescreen Project" list of working games and mupen64plus-next configs (forums.launchbox-app.com/files/file/3649): GameShark-style widescreen codes plus the core's aspect setting, picked automatically when a game is on the list, with a toggle; parallel-RDP can't widen the view, so these games may need GLideN64 or the code alone
- [ ] Local multiplayer: controllers 2-4 with their own mapping, paks per player; one device with several pads
- [ ] Online multiplayer: netplay between devices (the core has mupen64plus netplay code: lockstep, needs identical settings and ROM; check how it holds up on mobile networks), maybe also LAN/Wi-Fi direct play between two handhelds. Delay-based first (same servers as simple64/RMG/gopher64 if the protocol matches); rollback later, using flycast's trick of savestates that hold only the RAM pages a frame changed (no N64 emulator ships rollback yet)
- [ ] Modern homebrew (libdragon/tiny3d games, e.g. lambertjamesd/n64brew2025, N64brew game jam entries): custom RSP microcode, so always parallel-RSP + parallel-RDP (never GLideN64/HLE), save type from the Advanced Homebrew ROM Header, IS-Viewer/USB debug output, SC64/64drive features; build a few jam games as a test set (MIT licensed)
- [ ] Release: signed builds, where to publish, updates, GPL source obligations, bundled driver license notices
- [ ] 64DD (disk add-on, mostly Japan-only games): ares supports it, mupen64plus partly; check what the core does, disk saves
- [ ] Rare accessories, mostly not emulated anywhere: Voice Recognition Unit (Hey You, Pikachu!; could use the device mic), Bio Sensor (Tetris 64), Densha de Go controller, N64 Mouse (Mario Artist), Capture Cassette, Randnet online service. Also Game Boy Camera through the Transfer Pak. Low priority: a handful of games
- [ ] Small: load zipped ROMs directly, Duo second screen for stats or menus
