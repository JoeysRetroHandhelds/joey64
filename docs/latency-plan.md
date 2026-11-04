# Latency, step 1: measure it

Goal: real numbers for every stage between a button press and light on the screen, on the Thor,
so each later change (frame delay, unbuffered input, run-ahead) can be judged before vs after.

## What the code already tells us

Found while reading the host; measurement should confirm the size of each.

1. **Sound lags about 200 ms.** `AudioOut` has a ~200 ms ring (`cap_ = rate / 5 * 2`) and
   emulation blocks on it when full, so the emulator runs until the ring is full and every sound
   plays ~200 ms after its frame. Probably the biggest perceived lag today.
2. **The panel runs at 120 Hz and we never ask for 60.** No `setFrameRate` call; swap interval 1
   waits for the next 8.3 ms vsync, and 60 fps comes from the audio blocking. Frames land on
   whichever 120 Hz vsync is next, so frame timing is uneven and the start of each frame drifts
   against vsync.
3. **Stick input is batched to vsync.** `dispatchGenericMotionEvent` without
   `requestUnbufferedDispatch`: Android holds motion events until the next frame callback.
   Buttons (key events) are not batched.
4. **When the game sees input.** The core refreshes our snapshot at each VI interrupt
   (`main_check_inputs` -> `poll_cb`, mupen64plus-core `main.c:262`); the game reads it whenever
   it polls the controller (`input_state` via `emulate_game_controller_via_libretro.c`).
   Thor supports `EGL_ANDROID_get_frame_timestamps`, so on-glass time is measurable.

## Stages and how each is measured

All timestamps use CLOCK_MONOTONIC (`SystemClock.uptimeNanos` / `steady_clock`), one clock base.

| Stage | Start | End | Source |
|---|---|---|---|
| Delivery | Kernel event time | Our `dispatch*Event` runs | `event.eventTimeNanos` (or `eventTime`) |
| Wait for game read | Delivered | First `input_state` call that returns the new state | host |
| Game's own lag | Game reads input | First frame whose picture changes | lag test (below) |
| Render + queue | Frame finished (`video_refresh`) | `eglSwapBuffers` returns | host |
| Compositor + scanout | Swap | Frame actually on screen | `EGL_DISPLAY_PRESENT_TIME_ANDROID`, read back a few frames later by frame id |
| Audio | Sample written | Sample played | ring fill + `AAudioStream_getTimestamp` |

Total = sum, shown live (excluding game lag) and as an estimate including the game's lag once
measured.

## Lag test: the game's own lag, exactly

Emulation from a savestate is deterministic, so:

1. Save state at frame N.
2. Run K frames (e.g. 10) with no input; hash each frame's picture (blit the FBO into a 64x48
   FBO, `glReadPixels`, hash).
3. Load the state, run again with the button pressed from frame N.
4. First frame whose hash differs = the game's lag in frames for that button in that scene.
5. Control: repeat step 3 without input; hashes must match step 2, or the result is discarded
   (non-determinism, or a renderer that doesn't restore cleanly from a state; GLideN64 keeps
   frame buffers on the GPU, so some games may need the parallel-RDP run).

Triggered by `adb shell am broadcast -a com.joeysretro.joey64.LAGTEST --es button A`, logged as
`lagtest: A -> N frames`. Later this feeds the profile DB and sets run-ahead per game (step 4).

## Code changes

- `MainActivity.kt` / `NativeBridge.kt`: pass event timestamps with `setButtons` / `setAnalog`.
- `libretro_host.cpp`: small ring of input changes (time, mask); stamp poll time and first read
  after a change; frame ids per presented frame; extend the 5 s stats line with per-stage
  averages and worst cases; lag test runner (needs `retro_serialize*` symbols).
- `egl_context.cpp`: enable `EGL_TIMESTAMPS_ANDROID` on the window surface;
  `eglGetNextFrameIdANDROID` before each swap; `eglGetFrameTimestampsANDROID` for frames a few
  swaps old.
- `audio_out.cpp`: report ring fill and AAudio's output latency.
- Debug overlay: a small text view updated twice a second from native stats; toggled by
  broadcast.
- Vulkan (parallel-RDP) path: `VK_GOOGLE_display_timing` if the driver has it; after GLES works.

## Checks

- Numbers are stable across runs (same scene, same button).
- Lag test control run matches.
- Optional sanity check: a 240 fps slow-motion phone video of the button and screen should agree
  with the total within a frame.

## After this (steps 2 to 4)

2. Cheap fixes, each measured: request 60 Hz (`setFrameRate`), pace by video with dynamic audio
   rate and a small ring, unbuffered input, frame delay (start the frame as late as vsync allows).
3. Run-ahead prototype in one game; cost and glitches on both renderers.
4. Per-game lag from the lag test into the profile DB (M3), run-ahead set from it.
