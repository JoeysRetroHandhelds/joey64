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
- `app/src/main/cpp/vk_context.*` – Vulkan frontend for parallel-RDP: instance, swapchain, 4:3 blit of the core's image; the core creates the device via the v1 negotiation interface (creating it ourselves crashes Granite)
- `app/src/main/cpp/third_party/libadrenotools/` – BSD-2, copied from dolphin's checkout; loads custom Vulkan drivers (Turnip). Its hook libs ship in the APK and are found via nativeLibraryDir
- `app/src/main/cpp/audio_out.*` – AAudio float at device rate + linear resampler + SPSC ring (blocking write = audio sync)
- `app/src/main/jniLibs/arm64-v8a/` – drop `libmupen64plus_next_libretro_android.so` here (not committed)

## Build / run
- Android Studio or `./gradlew assembleDebug` (needs JDK 17+, e.g. Android Studio's `jbr` as JAVA_HOME)
- arm64-v8a only, minSdk 29, NDK 27.3
- Dev ROM path: `/sdcard/Android/data/com.joeysretro.n64/files/test.z64`
- Logs: `adb logcat -s n64host core Granite`
- Custom Vulkan driver (dev): push e.g. Turnip's `libvulkan_freedreno.so` to `/sdcard/Android/data/com.joeysretro.n64/files/drivers/`; the app copies it to internal storage and loads it. From Git Bash set `MSYS_NO_PATHCONV=1` or adb paths get mangled
- Renderer: parallel-RDP when a custom driver is installed, else GLideN64; override with `--es renderer parallel|gliden64` on `am start`
- Controls: positional mapping of any Android gamepad in MainActivity (bottom = N64 A, left = N64 B, L2 = Z, right stick = C). Presses shorter than a frame are held for 4 polls (Snap ignores 1-frame presses). R3 or the on-screen button toggles fast-forward (4x cap, audio muted, ~60 fps shown, async parallel-RDP while on)
- Dev core options: `--es opts "key=value,key=value"` on `am start`
- Save RAM: `files/saves/<fnv64 of ROM>.srm`, written atomically every 120 frames when changed, on pause and on exit
- Debug builds: `adb shell am broadcast -a com.joeysretro.n64.STICK --ei x 0 --ei y 32767 --ei ms 200` moves the stick (`--ei stick 1` = C stick)
- Never set `MSYS_NO_PATHCONV=1` for `./gradlew`: the native build silently does nothing

## Conventions
- Core options are overridden in `g_optionOverrides` (libretro_host.cpp); M3 replaces this with the profile DB.
- Emulation runs on one native thread that owns the GL context; UI thread only posts surface/input changes.
- License: GPLv2+ (core is GPL). ares-derived code keeps its ISC notice.
- M0 verified on AYN Thor (SD 8 Gen 2): SM64 at 60 fps on GLideN64. `adb logcat -s n64host` prints fps stats every 5 s.

## Known issues
- parallel-RDP on AYN Thor with the stock Adreno 740 driver (676.53): black screen, the compiler fails on a parallel-RDP compute shader. Fixed by Turnip ("Turnip Gen8 V38", Mesa 26.99.99 / Vulkan 1.4.363): renders correctly at 60 fps.
- Synchronous parallel-RDP (needed for CPU framebuffer reads, e.g. Snap photos) costs ~10 ms/frame in Snap on the Thor (async: ~2–4 ms; GLideN64: 2.5 ms). Per-game sync setting belongs in M3.
- AAudio low-latency path is denied on the Thor (output has no RAW flag).

## Roadmap
See README.md (M0–M6). Current milestone: get M0 booting, then M1 input.
