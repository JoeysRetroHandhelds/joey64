# CLAUDE.md

Standalone Android N64 emulator for handhelds (Retroid, Odin, AYN, Anbernic), hosting the
mupen64plus-next libretro core from a Kotlin app with a C++ libretro frontend.

## Goals (priority order)
1. Automatic renderer + settings per game (ROM hash) and per device (GPU vendor/driver): parallel-RDP vs GLideN64.
2. Smooth frame pacing: Swappy, audio/video sync modes, big-core pinning, thermal fallback.
3. Handheld-first frontend: device detection, controller profiles, controller-only UI.

## Layout
- `app/src/main/java/com/joeysretro/n64/` – Kotlin: `MainActivity` (surface/lifecycle), `NativeBridge` (JNI)
- `app/src/main/cpp/libretro_host.cpp` – dlopen core, environment callbacks, run thread, input state
- `app/src/main/cpp/egl_context.*` – EGL/GLES3 context, FBO the core renders into, 4:3 blit
- `app/src/main/cpp/audio_out.*` – AAudio float at device rate + linear resampler + SPSC ring (blocking write = audio sync)
- `app/src/main/jniLibs/arm64-v8a/` – drop `libmupen64plus_next_libretro_android.so` here (not committed)

## Build / run
- Android Studio or `./gradlew assembleDebug` (needs JDK 17+, e.g. Android Studio's `jbr` as JAVA_HOME)
- arm64-v8a only, minSdk 29, NDK 27.3
- Dev ROM path: `/sdcard/Android/data/com.joeysretro.n64/files/test.z64`
- Logs: `adb logcat -s n64host core`

## Conventions
- Core options are overridden in `g_optionOverrides` (libretro_host.cpp); M3 replaces this with the profile DB.
- Emulation runs on one native thread that owns the GL context; UI thread only posts surface/input changes.
- License: GPLv2+ (core is GPL). ares-derived code keeps its ISC notice.
- M0 verified on AYN Thor (SD 8 Gen 2): SM64 at 60 fps on GLideN64. `adb logcat -s n64host` prints fps stats every 5 s.

## Roadmap
See README.md (M0–M6). Current milestone: get M0 booting, then M1 input.
