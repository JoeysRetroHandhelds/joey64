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
- `app/src/main/assets/drivers/` – bundled Vulkan drivers (not committed, `*.so`): `libvulkan_freedreno.so` (Turnip Gen8 V38, from `devtools/drivers/`) and `libvulkan_malisx2.so` (built from bmdhacks/malisx2 + the Mesa patches in its `patches/mesa/`). Licenses in `assets/licenses/`. Without them the app runs on the system driver
- `app/src/main/cpp/audio_out.*` – AAudio float at device rate + linear resampler + SPSC ring (blocking write = audio sync)
- `app/src/main/jniLibs/arm64-v8a/` – drop `libmupen64plus_next_libretro_android.so` here (not committed)
- Core source: `C:\GithubProjects\mupen64plus-libretro-nx` (private repo JoeysRetroHandhelds/n64-core, branch `handheld`; `upstream` = libretro/mupen64plus-libretro-nx). Build (Git Bash, NDK 27.3):
  `ndk-build.cmd -C libretro/jni APP_ABI=arm64-v8a GLES3=1 STRINGS=<ndk>/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strings.exe AWK=awk TR=tr -j16`.
  On a clean tree the asm_defines rule fails under cmd.exe: run `llvm-strings <obj of asm_defines.c> | tr -d '' | awk -v dest_dir=libretro/obj/local/arm64-v8a/asm_defines/ -f mupen64plus-core/tools/gen_asm_defines.awk` and rerun. Output `libretro/libs/arm64-v8a/libretro.so` → copy to jniLibs under the name above

## Build / run
- Android Studio or `./gradlew assembleDebug` (needs JDK 17+, e.g. Android Studio's `jbr` as JAVA_HOME)
- arm64-v8a only, minSdk 29, NDK 27.3
- Dev ROM path: `/sdcard/Android/data/com.joeysretro.n64/files/test.z64`
- Logs: `adb logcat -s n64host core Granite`
- Driver by GPU (`MainActivity.setupGpu`, logged as `GPU: ...`): bundled Turnip on Adreno 6xx–8xx (verified on Adreno 740), the system driver on Mali (Mali-G52: malisx2 measured ~14% slower than stock), malisx2 only on a Mali without a Vulkan 1.1 system driver. A custom driver that has no usable GPU falls back to the system driver. Override with `--es driver system|turnip|malisx2`
- Custom Vulkan driver (dev): a `.so` in `/sdcard/Android/data/com.joeysretro.n64/files/drivers/` (or internal `files/drivers/`) overrides the choice; the app copies it to internal storage and loads it. From Git Bash set `MSYS_NO_PATHCONV=1` or adb paths get mangled
- Renderer: parallel-RDP when the driver in use is a custom one or stock Mali with Vulkan 1.1, else GLideN64; override with `--es renderer parallel|gliden64` on `am start`
- Dev process env (read by the core): `--es env "PARALLEL_RDP_SMALL_TYPES=0,KEY=value"`
- Controls: positional mapping of any Android gamepad in MainActivity (bottom = N64 A, left = N64 B, L2 = Z, right stick = C). Presses shorter than a frame are held for 4 polls (Snap ignores 1-frame presses). R3 or the on-screen button toggles fast-forward (4x cap, audio muted, ~60 fps shown, async parallel-RDP while on)
- Dev core options: `--es opts "key=value,key=value"` on `am start`; GameShark codes: `--es cheats '<code>;<code>'` (quote the whole `am start` for `adb shell`, or the device shell splits at spaces)
- Debug builds: `adb shell am broadcast -a com.joeysretro.n64.DUMP --es name x` writes RDRAM to `files/dumps/x.bin`
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
- Synchronous parallel-RDP (needed for CPU framebuffer reads, e.g. Snap photos) costs ~10 ms/frame in Snap on the Thor (async: ~2–4 ms; GLideN64: 2.5 ms). parallel-RDP runs async by default; games listed in `kSyncRdpGames` (libretro_host.cpp, by header game code: Snap = NPF) run synchronous outside fast-forward. `--es opts mupen64plus-parallel-rdp-synchronous=True|False` overrides.
- AAudio low-latency path is denied on the Thor (output has no RAW flag).
- Audio sync waits before each frame (`AudioOut::waitForRoom`) while more than target + one frame is queued (target ~40 ms, +10 ms per 5 s window with underruns at full speed, max 150 ms). Rate control (max 1%) holds the fill at the target when vsync paces instead, and stays neutral while audio paces. The stats line shows fill/target, underruns and `poll->display` (input poll to scanout, from VK_GOOGLE_display_timing). Measured: Portal 55 ms queued, Flip ~65 ms, no underruns at full speed (was a full ~200 ms ring); poll->display unchanged (Portal 18.5 ms, Flip 28 ms in a match).
- Pokémon Snap on GLideN64 needs the source-built core (n64-core `handheld`, commits dc641ea + 535dc2a): upstream greys out Oak's Mark because the selection screen's visibility check copies depth-buffer strips, and GLideN64 served its color buffer instead. The same commit fixes glsm hiding all GL extensions on GLES3 builds. Worth upstreaming.

## Roadmap
See README.md (M0–M6). Current milestone: get M0 booting, then M1 input.
