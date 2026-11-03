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
- [ ] 50 Hz (PAL) games on 120 Hz panels: no even cadence; switch display mode or blend frames
- [ ] Optional overclock / 60 fps patches for 20-30 fps games (GoldenEye, Perfect Dark, Banjo), per game
- [ ] Analog stick range and calibration per device (N64 stick range vs modern sticks)
- [ ] Battery and heat: measure power use, thermal fallback (M4)

### Later
- [ ] RetroAchievements (reuse the mupen64plus-ae fork's work; note it's GPLv3)
- [ ] Display options: widescreen hacks, integer scaling, filters/CRT shaders, GLideN64 texture packs
- [ ] Multiplayer: controllers 2-4 with their own mapping (netplay likely out of scope)
- [ ] Release: signed builds, where to publish, updates, GPL source obligations, bundled driver license notices
- [ ] Small: load zipped ROMs directly, 64DD, Duo second screen for stats or menus
