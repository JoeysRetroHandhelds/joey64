# N64 Handheld (working title)

Standalone Android N64 emulator built on the **mupen64plus-next** libretro core,
focused on handheld devices (Retroid, Odin, AYN, Anbernic).

## Status
Skeleton. Boots a libretro core and runs its frame loop on a native thread.
Video uses an OpenGL ES 3 hardware context (GLideN64 path). Not yet built or tested on device.

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
- [x] M0 – Host core, load ROM, GLES video, AAudio, run loop
- [ ] M1 – Input: gamepad mapping → RETRO_DEVICE_JOYPAD/ANALOG
- [ ] M2 – Vulkan HW context (RETRO_HW_CONTEXT_VULKAN) for parallel-RDP
- [ ] M3 – Per-game / per-GPU profile DB (ROM hash + GPU vendor/driver → renderer + core options)
- [ ] M4 – Frame pacing: Swappy, audio/video sync modes, big-core pinning, thermal fallback
- [ ] M5 – Handheld frontend: device detection, controller profiles, controller-only UI
- [ ] M6 – Test harness: n64-systemtest results, Pokémon Snap photo check
