// Minimal libretro frontend: dlopen the core, answer environment calls,
// provide a GLES3 or Vulkan HW render context, and run retro_run on a dedicated thread.
#include <adrenotools/driver.h>
#include "perf_hint.h"
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdlib>
#include <android/native_window_jni.h>
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <unistd.h>
#include <sched.h>
#include <sys/resource.h>
#include <dirent.h>
#include <GLES3/gl3.h>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "libretro.h"   // from libretro-common, see README
#include "egl_context.h"
#include "vk_context.h"
#include "audio_out.h"

// Dev env N64_LOG_ERR=1 logs info at error level, for devices whose logd drops everything
// below error (Retroid Pocket Duo).
#define LOGI(...) __android_log_print(getenv("N64_LOG_ERR") && *getenv("N64_LOG_ERR") == '1' ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "n64host", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "n64host", __VA_ARGS__)

namespace {

struct Core {
    void* handle = nullptr;
    void (*init)();
    void (*deinit)();
    void (*get_system_av_info)(retro_system_av_info*);
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*set_controller_port_device)(unsigned, unsigned);
    bool (*load_game)(const retro_game_info*);
    void (*unload_game)();
    void (*run)();
    void* (*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
    void (*cheat_reset)();
    void (*cheat_set)(unsigned, bool, const char*);
    bool (*unserialize)(const void*, size_t);
} core;

std::string g_systemDir, g_saveDir;
retro_hw_render_callback g_hw{};
bool g_hwRequested = false;
// Renderer chosen before loadGame: Vulkan = parallel-RDP/RSP, otherwise GLES3 = GLideN64.
bool g_vulkan = false;
const retro_hw_render_context_negotiation_interface_vulkan* g_negotiation = nullptr;
retro_system_av_info g_av{};

// Core options. Defaults from the core, overrides here.
// M3's profile DB will fill g_optionOverrides per ROM hash + GPU.
std::unordered_map<std::string, std::string> g_options;
std::unordered_map<std::string, std::string> g_optionOverrides = {
    // The core defaults this to True on GLES; standalone GLideN64 defaults to off. Legacy blending
    // drops blend modes the shader blender handles, e.g. Mario Tennis's player select disappears.
    {"mupen64plus-EnableLegacyBlending", "False"},
};
std::atomic<bool> g_optionsDirty{true};

EglContext g_egl;
VkContext g_vk;
AudioOut g_audio;
std::mutex g_surfaceMutex;
ANativeWindow* g_pendingWindow = nullptr;
std::atomic<bool> g_surfaceChanged{false};
std::atomic<bool> g_surfaceResized{false};

std::thread g_thread;
std::atomic<bool> g_running{false}, g_paused{false};
std::mutex g_pauseMutex;
std::condition_variable g_pauseCv;
// Fast-forward: vsync off, audio dropped, emulation capped at kFastForwardSpeed x normal.
std::atomic<bool> g_fastForward{false};
// Benchmark (dev env N64_BENCH=1): no speed cap, vsync off, audio dropped, settings unchanged and every frame
// presented, so the counter shows how fast the game could run: the headroom a full-speed game has.
bool g_bench = false;
// parallel-RDP synchronous mode outside fast-forward. Async by default (sync makes the CPU wait for
// the GPU at every RDP sync: ~10 ms/frame in Snap on the Thor vs 2-4 ms); per game in loadGame.
bool g_rdpSync = false;
// Games whose CPU reads what the RDP drew and that break with async parallel-RDP: they stay synchronous
// in fast-forward too (async there gave black screens or the New Tetris halt). Set in loadGame.
bool g_ffKeepSync = false;
// GameShark codes applied after load_game (M3: per-game compatibility fixes from the profile DB).
std::vector<std::string> g_cheats;
// Debug: RDRAM dump requested from the UI thread, written by the emulation thread between frames.
std::mutex g_dumpMutex;
std::string g_dumpPath;
constexpr int kFastForwardSpeed = 4;
// Upscaling costs the GPU most of the fast-forward headroom (Odin 3 at 4x: Peach's Fury 1.5x, GoldenEye 1.03x),
// so fast-forward renders at 1x. The setting in use before is restored after ("" = none was overridden).
constexpr const char* kUpscaleKey = "mupen64plus-parallel-rdp-upscaling";
std::string g_upscaleBeforeFf;
bool g_upscaleFfApplied = false;

std::atomic<int> g_buttons{0};
std::atomic<int> g_tapped{0}; // presses not yet seen by a poll, so sub-frame taps still register
int g_polledButtons = 0;      // emulation thread only
std::atomic<int> g_analog[2][2] = {};
unsigned g_lastW = 0, g_lastH = 0;
int g_statRuns = 0, g_statPresents = 0, g_statDupes = 0; // emulation thread only
double g_statRunMs = 0, g_statWaitMs = 0;                 // time in retro_run / beginFrame
double g_statAudioWaitMs = 0;                             // audio sync before each frame
// For the counter ("60 FPS | 20 DL/s | 100%"): emulated frames (retro_run calls, the game's speed) and new
// frames presented (what the game drew: OoT 20 a second once repeats are skipped).
std::atomic<uint32_t> g_framesRun{0}, g_framesDrawn{0};
// Rumble Pak, player 1: the core switches each motor on/off; a UI-side thread waits in waitRumble
// for the motor to change and drives the controller's (or the device's) vibrator. Polling it from a
// per-vsync callback instead cost ~60 ms of CPU per second on the Duo (120 Hz, debug build).
std::atomic<uint16_t> g_rumbleWeak{0}, g_rumbleStrong{0};
std::mutex g_rumbleMutex;
std::condition_variable g_rumbleChanged;
bool setRumble(unsigned port, retro_rumble_effect effect, uint16_t strength) {
    if (port != 0) return false;
    bool wasOn = std::max(g_rumbleWeak.load(), g_rumbleStrong.load()) > 0;
    (effect == RETRO_RUMBLE_STRONG ? g_rumbleStrong : g_rumbleWeak) = strength;
    if ((std::max(g_rumbleWeak.load(), g_rumbleStrong.load()) > 0) != wasOn) {
        LOGI("rumble %s", wasOn ? "off" : "on");
        { std::lock_guard<std::mutex> lock(g_rumbleMutex); }
        g_rumbleChanged.notify_all();
    }
    return true;
}

template <typename T> bool sym(T& fn, const char* name) {
    fn = reinterpret_cast<T>(dlsym(core.handle, name));
    if (!fn) LOGE("missing symbol %s", name);
    return fn != nullptr;
}

// ---- libretro callbacks ----------------------------------------------------

uintptr_t hw_get_current_framebuffer() { return g_egl.fbo(); }
retro_proc_address_t hw_get_proc_address(const char* name) {
    return reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(name));
}

void log_cb(enum retro_log_level level, const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    __android_log_vprint(level >= RETRO_LOG_ERROR || (getenv("N64_LOG_ERR") && *getenv("N64_LOG_ERR") == '1') ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "core", fmt, ap);
    va_end(ap);
}

bool environment(unsigned cmd, void* data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        static_cast<retro_log_callback*>(data)->log = log_cb; return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *static_cast<const char**>(data) = g_systemDir.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *static_cast<const char**>(data) = g_saveDir.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *static_cast<bool*>(data) = true; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        return *static_cast<retro_pixel_format*>(data) == RETRO_PIXEL_FORMAT_XRGB8888;
    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
        *static_cast<unsigned*>(data) = g_vulkan ? RETRO_HW_CONTEXT_VULKAN : RETRO_HW_CONTEXT_OPENGLES3;
        return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        auto* hw = static_cast<retro_hw_render_callback*>(data);
        if (hw->context_type == RETRO_HW_CONTEXT_VULKAN) {
            if (!g_vulkan) return false;
            g_hw = *hw; g_hwRequested = true;
            return true;
        }
        if (g_vulkan) return false;
        if (hw->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
            hw->context_type != RETRO_HW_CONTEXT_OPENGLES2 &&
            hw->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION) {
            LOGI("core requested unsupported HW context %d", hw->context_type);
            return false;
        }
        hw->get_current_framebuffer = hw_get_current_framebuffer;
        hw->get_proc_address = hw_get_proc_address;
        g_hw = *hw; g_hwRequested = true;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
        // v1 only: the core creates the device via create_device; we create the instance.
        auto* n = static_cast<retro_hw_render_context_negotiation_interface*>(data);
        if (n->interface_type != RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) return false;
        n->interface_version = 1;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
        auto* n = static_cast<const retro_hw_render_context_negotiation_interface*>(data);
        if (n->interface_type != RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) return false;
        g_negotiation = reinterpret_cast<const retro_hw_render_context_negotiation_interface_vulkan*>(n);
        return true;
    }
    case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
        if (!g_vulkan) return false;
        *static_cast<const retro_hw_render_interface**>(data) =
            reinterpret_cast<const retro_hw_render_interface*>(g_vk.iface());
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        auto* v = static_cast<retro_variable*>(data);
        auto it = g_optionOverrides.find(v->key);
        if (it != g_optionOverrides.end()) { v->value = it->second.c_str(); return true; }
        auto d = g_options.find(v->key);
        v->value = d != g_options.end() ? d->second.c_str() : nullptr;
        return v->value != nullptr;
    }
    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
        static_cast<retro_rumble_interface*>(data)->set_rumble_state = setRumble; return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *static_cast<bool*>(data) = g_optionsDirty.exchange(false); return true;
    case RETRO_ENVIRONMENT_SET_VARIABLES: {
        // Legacy format: "Description; default|opt2|opt3" — first option is default.
        for (auto* v = static_cast<const retro_variable*>(data); v->key; ++v) {
            std::string s = v->value ? v->value : "";
            auto semi = s.find("; ");
            std::string opts = semi == std::string::npos ? s : s.substr(semi + 2);
            g_options[v->key] = opts.substr(0, opts.find('|'));
        }
        return true;
    }
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        *static_cast<unsigned*>(data) = 0; return true; // forces SET_VARIABLES path
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
        g_av.geometry = *static_cast<const retro_game_geometry*>(data); return true;
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        g_av = *static_cast<const retro_system_av_info*>(data); return true;
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        for (auto* d = static_cast<const retro_input_descriptor*>(data); d->description; ++d)
            if (d->port == 0) LOGI("input: dev %u idx %u id %u = %s", d->device, d->index, d->id, d->description);
        return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        return true;
    default:
        return false;
    }
}

void video_refresh(const void* data, unsigned w, unsigned h, size_t) {
    if (g_vulkan) {
        if (!data) ++g_statDupes;
        else { g_lastW = w; g_lastH = h; ++g_statPresents; ++g_framesDrawn; }
        g_vk.videoRefresh(data, w, h);
        return;
    }
    if (!data) { ++g_statDupes; return; }     // dupe frame: keep previous
    g_lastW = w; g_lastH = h;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        g_egl.present(w, h, g_hw.bottom_left_origin);
        ++g_statPresents;
        ++g_framesDrawn;
    }
    // Software frames are not expected from mupen64plus-next with a HW context.
}

void audio_sample(int16_t l, int16_t r) {
    if (g_fastForward || g_bench) return;
    int16_t s[2] = {l, r}; g_audio.write(s, 1);
}
size_t audio_batch(const int16_t* d, size_t frames) {
    if (!g_fastForward && !g_bench) g_audio.write(d, frames); // blocking write would throttle fast-forward
    return frames;
}
// Taps are held for kMinPressPolls polls: some games (Pokemon Snap's tutorial) ignore a press
// that is visible for a single frame.
constexpr int kMinPressPolls = 4;
int g_tapHold[16] = {};
// Late input: the core polls at vertical blank, at the end of retro_run, but the game reads the
// controller during the next retro_run, after the audio and vsync waits. So the buttons are sampled
// at the game's first read after each poll instead (sticks are always read live). Retroid Flip,
// input sample to display: menus ~54 -> ~44 ms, Mario Tennis match ~47 -> ~42 ms. Dev: env
// N64_LATE_INPUT=0 samples at the poll again; poll->display then reads a frame short, since that
// sample is used by the next frame, not the one presented after it.
bool g_lateInput = true;
bool g_pollPending = false;
void sampleButtons();
void input_poll() {
    if (g_lateInput) { g_pollPending = true; return; }
    sampleButtons();
}

void sampleButtons() {
    int taps = g_tapped.exchange(0), held = 0;
    for (int i = 0; i < 16; ++i) {
        if (taps & (1 << i)) g_tapHold[i] = kMinPressPolls;
        if (g_tapHold[i] > 0) { --g_tapHold[i]; held |= 1 << i; }
    }
    g_polledButtons = g_buttons.load() | held;
    if (g_vulkan) {
        timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); // the clock display timing reports in
        g_vk.notePoll(ts.tv_sec * 1000000000LL + ts.tv_nsec);
    }
}

int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
        if (g_pollPending) { g_pollPending = false; sampleButtons(); }
        int mask = g_polledButtons;
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)mask;
        return (mask >> id) & 1;
    }
    if (device == RETRO_DEVICE_ANALOG && index < 2 && id < 2)
        return (int16_t)g_analog[index][id].load();
    return 0;
}

// ---- run loop --------------------------------------------------------------

void applySurfaceIfChanged() {
    bool resized = g_surfaceResized.exchange(false);
    if (g_surfaceChanged.exchange(false)) { // attaching builds the swapchain at the current size
        std::lock_guard<std::mutex> lk(g_surfaceMutex);
        if (g_vulkan) g_vk.attachWindow(g_pendingWindow);
        else g_egl.attachWindow(g_pendingWindow);
    } else if (resized && g_vulkan) {
        // Android doesn't report OUT_OF_DATE for a resized window (only SUBOPTIMAL, which we
        // ignore), so a swapchain built before the system bars were hidden kept their size and
        // was stretched to the screen (Retroid Pocket Duo: 1920x969 on 1920x1080). EGL follows the
        // window size by itself.
        g_vk.resizeSwapchain();
    }
}

// Logs emulated frames/s (retro_run calls), presented and duped frames every 5 s.
void logStats() {
    using clock = std::chrono::steady_clock;
    static clock::time_point start = clock::now();
    ++g_statRuns;
    ++g_framesRun;
    double secs = std::chrono::duration<double>(clock::now() - start).count();
    if (secs < 5.0) return;
    char lat[64] = "";
    double latMean, latMax; int uneven;
    if (g_vulkan && g_vk.takeLatency(latMean, latMax, uneven))
        snprintf(lat, sizeof lat, ", poll->display %.1f ms (max %.1f), %d uneven", latMean, latMax, uneven);
    double fillMs, targetMs; int underruns;
    g_audio.takeStats(!g_fastForward && !g_bench && g_statRuns / secs >= 58.5, fillMs, targetMs, underruns);
    LOGI("stats: %.1f emu fps, %.1f presented, %.1f dupes (%ux%u), run %.2f ms, wait %.2f ms, "
         "audio wait %.2f ms, fill %.0f/%.0f ms, %d underruns, device %.0f ms%s",
         g_statRuns / secs, g_statPresents / secs, g_statDupes / secs, g_lastW, g_lastH,
         g_statRunMs / g_statRuns, g_statWaitMs / g_statRuns, g_statAudioWaitMs / g_statRuns, fillMs,
         targetMs, underruns, g_audio.outputLatencyMs(), lat);
    g_statRuns = g_statPresents = g_statDupes = 0;
    g_statRunMs = g_statWaitMs = g_statAudioWaitMs = 0;
    start = clock::now();
}

// ---- save RAM ---------------------------------------------------------------
// The core keeps EEPROM/SRAM/FlashRAM/Controller Pak in RETRO_MEMORY_SAVE_RAM; persisting it is
// the frontend's job. One .srm per ROM, named like RetroArch's (the ROM file name without its
// extension), so its Mupen64Plus-Next saves work as they are; written atomically when it changes:
// checked every ~2 s, on pause (Android may kill us after onPause) and on exit. ROMs without a
// name (the dev path) and saves from before names use the ROM's content hash instead.
std::string g_savePath, g_saveFallbackPath;
std::vector<uint8_t> g_lastSave;

void loadSave() {
    auto* mem = static_cast<uint8_t*>(core.get_memory_data(RETRO_MEMORY_SAVE_RAM));
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!mem || !size) { LOGI("save: core exposes no save RAM"); return; }
    FILE* f = fopen(g_savePath.c_str(), "rb");
    const std::string& from = f ? g_savePath : g_saveFallbackPath;
    if (!f && !g_saveFallbackPath.empty()) f = fopen(g_saveFallbackPath.c_str(), "rb");
    if (f) {
        size_t n = fread(mem, 1, size, f);
        fclose(f);
        LOGI("save: loaded %zu/%zu bytes from %s", n, size, from.c_str());
    } else {
        LOGI("save: none yet (%s, %zu bytes)", g_savePath.c_str(), size);
    }
    g_lastSave.assign(mem, mem + size);
}

void flushSave() {
    auto* mem = static_cast<uint8_t*>(core.get_memory_data(RETRO_MEMORY_SAVE_RAM));
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!mem || !size || g_savePath.empty()) return;
    if (g_lastSave.size() == size && !memcmp(g_lastSave.data(), mem, size)) return;
    std::string tmp = g_savePath + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) { LOGE("save: cannot write %s", tmp.c_str()); return; }
    bool ok = fwrite(mem, 1, size, f) == size;
    ok = (fflush(f) == 0) && ok;
    ok = (fsync(fileno(f)) == 0) && ok;
    fclose(f);
    if (!ok || rename(tmp.c_str(), g_savePath.c_str()) != 0) { LOGE("save: write failed"); return; }
    g_lastSave.assign(mem, mem + size);
    LOGI("save: wrote %zu bytes", size);
}

// Keep the emulation thread on the big cores (capacity within 80% of the fastest): left to the
// scheduler it starts on little cores and only migrates as load builds. M4 adds thermal fallback.
void pinToBigCores() {
    int cap[CPU_SETSIZE] = {}, maxCap = 0, n = 0;
    for (; n < CPU_SETSIZE; ++n) {
        char path[96];
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpu_capacity", n);
        FILE* f = fopen(path, "r");
        if (!f) break;
        if (fscanf(f, "%d", &cap[n]) != 1) cap[n] = 0;
        fclose(f);
        if (cap[n] > maxCap) maxCap = cap[n];
    }
    if (!maxCap) return;
    cpu_set_t set; CPU_ZERO(&set);
    std::string cores;
    for (int i = 0; i < n; ++i)
        if (cap[i] * 10 >= maxCap * 8) { CPU_SET(i, &set); cores += std::to_string(i) + " "; }
    int r = sched_setaffinity(0, sizeof set, &set);
    LOGI("emu thread pinned to cpus %s(%s)", cores.c_str(), r == 0 ? "ok" : strerror(errno));
}

// Then the emulation thread gets the last big core to itself and the threads that inherited its
// big-core mask (parallel-RDP's worker, threads created later) the other big cores. Mario Tennis on
// the Flip (2 big cores): 59.7 vs 57.2 fps in a match. Rescanned now and then for new threads.
// N64_PIN_SPLIT=0 in the environment turns it off.
void splitBigCores() {
    static cpu_set_t big, rest, one;
    static bool init = false;
    if (!init) {
        CPU_ZERO(&big);
        if (sched_getaffinity(0, sizeof big, &big) || CPU_COUNT(&big) < 2) return;
        int last = -1;
        for (int i = 0; i < CPU_SETSIZE; ++i) if (CPU_ISSET(i, &big)) last = i;
        rest = big; CPU_CLR(last, &rest);
        CPU_ZERO(&one); CPU_SET(last, &one);
        sched_setaffinity(0, sizeof one, &one);
        LOGI("emu thread on cpu %d alone", last);
        init = true;
    }
    pid_t self = gettid();
    std::string moved;
    if (DIR* d = opendir("/proc/self/task")) {
        while (dirent* e = readdir(d)) {
            pid_t tid = atoi(e->d_name);
            if (tid <= 0 || tid == self) continue;
            cpu_set_t s; CPU_ZERO(&s);
            if (sched_getaffinity(tid, sizeof s, &s) == 0 && (CPU_EQUAL(&s, &big) || CPU_EQUAL(&s, &one)) &&
                sched_setaffinity(tid, sizeof rest, &rest) == 0) moved += std::to_string(tid) + " ";
        }
        closedir(d);
    }
    if (!moved.empty()) LOGI("threads moved off the emu thread's core: %s", moved.c_str());
}

// EXPERIMENT: emulation thread priority (mupen64plus-ae's "high priority thread" uses Android's
// THREAD_PRIORITY_URGENT_AUDIO, nice -19). N64_THREAD_PRIO=<nice> sets it, e.g. -19 (urgent audio),
// -10 (urgent display), -4 (display); unset leaves the default (0). N64_THREAD_PRIO_ALL=1 also gives the
// other threads already running in the process (parallel-RDP workers, audio) the same value.
void setEmuThreadPriority() {
    const char* env = getenv("N64_THREAD_PRIO");
    if (!env || !*env) return;
    int nice = atoi(env);
    int r = setpriority(PRIO_PROCESS, 0, nice);
    LOGI("emu thread priority %d (%s), now %d", nice, r == 0 ? "ok" : strerror(errno), getpriority(PRIO_PROCESS, 0));
    const char* all = getenv("N64_THREAD_PRIO_ALL");
    if (!all || *all != '1') return;
    std::string done;
    if (DIR* d = opendir("/proc/self/task")) {
        while (dirent* e = readdir(d)) {
            pid_t tid = atoi(e->d_name);
            if (tid > 0 && tid != gettid() && setpriority(PRIO_PROCESS, tid, nice) == 0) done += std::to_string(tid) + " ";
        }
        closedir(d);
    }
    LOGI("threads given priority %d: %s", nice, done.c_str());
}

void runLoop() {
    pinToBigCores();
    setEmuThreadPriority();
    // EXPERIMENT: Adreno GPU clocks held at max while running (Dolphin's "Force max GPU clocks")
    const char* turboEnv = getenv("N64_GPU_TURBO");
    const bool turbo = turboEnv && *turboEnv == '1';
    if (turbo) { adrenotools_set_turbo(true); LOGI("GPU turbo on"); }
    // EXPERIMENT: ADPF performance hint session for this thread (Android 13+)
    const char* hintEnv = getenv("N64_PERF_HINT");
    const bool useHint = hintEnv && *hintEnv == '1';
    PerfHint perfHint;
    const char* noSplit = getenv("N64_PIN_SPLIT");
    const bool split = !(noSplit && *noSplit == '0');
    const char* late = getenv("N64_LATE_INPUT");
    g_lateInput = !(late && *late == '0');
    LOGI("late input %s", g_lateInput ? "on" : "off");
    int splitIn = 300; // frames: the core's threads exist by then
    int framesSinceSaveCheck = 0;
    bool ffApplied = false, benchApplied = false;
    // Dev (env N64_STATE=<raw save state file>): load it once the core has run a little (mupen64plus-next
    // can't load before), for repeatable benchmark scenes. RetroArch's RZIP states must be unpacked first.
    const char* statePath = getenv("N64_STATE");
    int stateWait = statePath && *statePath ? 30 : -1;
    g_bench = getenv("N64_BENCH") && atoi(getenv("N64_BENCH")) == 1;
    auto ffNext = std::chrono::steady_clock::now(), ffLastShown = ffNext, capNext = ffNext;
    if (!g_vulkan) g_egl.makeCurrent();
    while (g_running) {
        if (g_paused) flushSave();
        {
            std::unique_lock<std::mutex> lk(g_pauseMutex);
            g_pauseCv.wait(lk, [] { return !g_paused || !g_running; });
        }
        if (!g_running) break;
        applySurfaceIfChanged();
        bool hasWindow = g_vulkan ? g_vk.hasWindow() : g_egl.hasWindow();
        if (!hasWindow) { std::this_thread::sleep_for(std::chrono::milliseconds(16)); continue; }
        if (g_bench && !benchApplied) {
            benchApplied = true;
            if (g_vulkan) g_vk.setUnthrottled(true);
            else g_egl.setSwapInterval(0);
            LOGI("benchmark mode: uncapped, vsync off, audio dropped");
        }
        // pacing comes from vsync + blocking audio writes (M4 refines); fast-forward swaps
        // both for a sleep that caps the speed
        bool ff = g_fastForward;
        if (ff != ffApplied) {
            ffApplied = ff;
            if (g_vulkan) g_vk.setUnthrottled(ff || g_bench);
            else g_egl.setSwapInterval(ff || g_bench ? 0 : 1);
            // Synchronous parallel-RDP caps fast-forward near 1.7x on the Thor; async reaches 4x. Sync
            // (smart sync, by default on Adreno) comes back when fast-forward ends; games that break with
            // async (g_ffKeepSync) keep it throughout.
            if (g_vulkan) {
                g_optionOverrides["mupen64plus-parallel-rdp-synchronous"] = (g_rdpSync && (!ff || g_ffKeepSync)) ? "True" : "False";
                static const bool keepUpscale = getenv("N64_FF_KEEP_UPSCALE") && atoi(getenv("N64_FF_KEEP_UPSCALE"));
                if (ff && !keepUpscale) {
                    auto it = g_optionOverrides.find(kUpscaleKey);
                    g_upscaleBeforeFf = it != g_optionOverrides.end() ? it->second : "";
                    g_optionOverrides[kUpscaleKey] = "1x";
                    g_upscaleFfApplied = true;
                } else if (!ff && g_upscaleFfApplied) {
                    if (g_upscaleBeforeFf.empty()) g_optionOverrides.erase(kUpscaleKey);
                    else g_optionOverrides[kUpscaleKey] = g_upscaleBeforeFf;
                    g_upscaleFfApplied = false;
                }
                g_optionsDirty = true;
            }
            ffNext = std::chrono::steady_clock::now();
        }
        if (ff) {
            ffNext += std::chrono::microseconds((long long)(1e6 / (g_av.timing.fps * kFastForwardSpeed)));
            auto now = std::chrono::steady_clock::now();
            if (ffNext > now) std::this_thread::sleep_until(ffNext);
            else if (now - ffNext > std::chrono::milliseconds(100)) ffNext = now; // don't sprint to catch up
        }
        // Fast-forward shows at most ~60 frames a second so presenting doesn't cap the speed.
        bool show = true;
        if (ff) {
            auto now = std::chrono::steady_clock::now();
            show = now - ffLastShown >= std::chrono::microseconds(16000);
            if (show) ffLastShown = now;
        }
        if (!ff && !g_bench) {
            auto t0 = std::chrono::steady_clock::now();
            g_audio.waitForRoom();
            g_statAudioWaitMs += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            // Speed cap. Audio sync and vsync normally pace, but a game that stops producing audio
            // (New Tetris) ran at the panel rate on 120 Hz: 2x. 2% slack keeps it out of the way
            // of audio pacing and rate control.
            auto now = std::chrono::steady_clock::now();
            capNext += std::chrono::microseconds((long long)(1e6 / (g_av.timing.fps * 1.02)));
            if (capNext > now) std::this_thread::sleep_until(capNext);
            else if (now - capNext > std::chrono::milliseconds(50)) capNext = now; // behind: don't sprint
        }
        if (g_vulkan) {
            auto t0 = std::chrono::steady_clock::now();
            if (!g_vk.beginFrame(show)) continue;   // swapchain recreated, try again
            auto t1 = std::chrono::steady_clock::now();
            core.run();
            g_vk.endFrame();
            g_statWaitMs += std::chrono::duration<double, std::milli>(t1 - t0).count();
            g_statRunMs += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t1).count();
        } else {
            auto t0 = std::chrono::steady_clock::now();
            glBindFramebuffer(GL_FRAMEBUFFER, g_egl.fbo());
            core.run(); // includes the swap, so the vsync wait lands here
            g_statRunMs += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        }
        logStats();
        if (stateWait > 0 && --stateWait == 0) {
            std::vector<char> s;
            if (FILE* f = fopen(statePath, "rb")) {
                fseek(f, 0, SEEK_END); s.resize(ftell(f)); fseek(f, 0, SEEK_SET);
                s.resize(fread(s.data(), 1, s.size(), f)); fclose(f);
            }
            bool ok = !s.empty() && core.unserialize(s.data(), s.size());
            LOGI("state %s: %s (%zu bytes)", statePath, ok ? "loaded" : "FAILED", s.size());
        }
        // Dev (env N64_HASH_RAM=<n>): logs a hash of RDRAM every n frames after the state loads, to check that two
        // builds emulate the same thing (benchmark runs are deterministic from a state).
        static const int hashEvery = getenv("N64_HASH_RAM") ? atoi(getenv("N64_HASH_RAM")) : 0;
        static int hashFrame = 0;
        if (hashEvery > 0 && stateWait == 0 && ++hashFrame % hashEvery == 0) {
            auto* mem = static_cast<const uint32_t*>(core.get_memory_data(RETRO_MEMORY_SYSTEM_RAM));
            size_t words = core.get_memory_size(RETRO_MEMORY_SYSTEM_RAM) / 4;
            uint64_t h = 1469598103934665603ull;
            for (size_t i = 0; mem && i < words; i++) h = (h ^ mem[i]) * 1099511628211ull;
            LOGI("ramhash %d %016llx", hashFrame, (unsigned long long)h);
        }
        {
            std::lock_guard<std::mutex> lk(g_dumpMutex);
            if (!g_dumpPath.empty()) {
                auto* mem = static_cast<const uint8_t*>(core.get_memory_data(RETRO_MEMORY_SYSTEM_RAM));
                size_t size = core.get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
                if (FILE* f = mem ? fopen(g_dumpPath.c_str(), "wb") : nullptr) {
                    fwrite(mem, 1, size, f); fclose(f);
                    LOGI("dump: wrote %zu bytes of RDRAM to %s", size, g_dumpPath.c_str());
                }
                g_dumpPath.clear();
            }
        }
        if (++framesSinceSaveCheck >= 120) { framesSinceSaveCheck = 0; flushSave(); }
        if (useHint) perfHint.reportFrame();
        if (split && --splitIn == 0) { splitBigCores(); splitIn = 600; }
    }
    if (turbo) adrenotools_set_turbo(false);
    flushSave();
    if (g_hw.context_destroy) g_hw.context_destroy();
    core.unload_game();
    core.deinit();
    g_audio.close();
    if (g_vulkan) g_vk.destroy();
    else g_egl.destroy();
}

} // namespace

// ---- JNI -------------------------------------------------------------------

#define JNI(name) Java_com_joeysretro_joey64_NativeBridge_##name

// 4-character game code from the cartridge header (0x3B, e.g. "NPFE"), whatever the ROM's byte order.
std::string romGameId(const std::vector<uint8_t>& rom) {
    if (rom.size() < 0x40) return "";
    auto at = [&](size_t i) -> char {
        switch (rom[0]) {
            case 0x37: return rom[i ^ 1];   // .v64: 16-bit byte-swapped
            case 0x40: return rom[i ^ 3];   // .n64: 32-bit little-endian
            default:   return rom[i];       // .z64: big-endian
        }
    };
    std::string id;
    for (size_t i = 0x3B; i < 0x3F; ++i) { char c = at(i); id += (c >= 0x20 && c < 0x7F) ? c : '?'; }
    return id;
}

extern "C" {

JNIEXPORT jboolean JNICALL JNI(loadCore)(JNIEnv* env, jobject, jstring path, jstring sys, jstring save) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    core.handle = dlopen(p, RTLD_NOW | RTLD_LOCAL);
    if (!core.handle) LOGE("dlopen %s: %s", p, dlerror());
    env->ReleaseStringUTFChars(path, p);
    if (!core.handle) return JNI_FALSE;

    const char* s = env->GetStringUTFChars(sys, nullptr);  g_systemDir = s; env->ReleaseStringUTFChars(sys, s);
    const char* v = env->GetStringUTFChars(save, nullptr); g_saveDir = v;   env->ReleaseStringUTFChars(save, v);

    bool ok = sym(core.init, "retro_init") && sym(core.deinit, "retro_deinit")
        && sym(core.get_system_av_info, "retro_get_system_av_info")
        && sym(core.set_environment, "retro_set_environment")
        && sym(core.set_video_refresh, "retro_set_video_refresh")
        && sym(core.set_audio_sample, "retro_set_audio_sample")
        && sym(core.set_audio_sample_batch, "retro_set_audio_sample_batch")
        && sym(core.set_input_poll, "retro_set_input_poll")
        && sym(core.set_input_state, "retro_set_input_state")
        && sym(core.set_controller_port_device, "retro_set_controller_port_device")
        && sym(core.load_game, "retro_load_game")
        && sym(core.unload_game, "retro_unload_game")
        && sym(core.run, "retro_run")
        && sym(core.get_memory_data, "retro_get_memory_data")
        && sym(core.get_memory_size, "retro_get_memory_size")
        && sym(core.cheat_reset, "retro_cheat_reset")
        && sym(core.cheat_set, "retro_cheat_set")
        && sym(core.unserialize, "retro_unserialize");
    if (!ok) return JNI_FALSE;

    core.set_environment(environment);
    core.set_video_refresh(video_refresh);
    core.set_audio_sample(audio_sample);
    core.set_audio_sample_batch(audio_batch);
    core.set_input_poll(input_poll);
    core.set_input_state(input_state);
    core.init();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI(setSurface)(JNIEnv* env, jobject, jobject surface) {
    std::lock_guard<std::mutex> lk(g_surfaceMutex);
    if (g_pendingWindow) ANativeWindow_release(g_pendingWindow);
    g_pendingWindow = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    g_surfaceChanged = true;
}

JNIEXPORT void JNICALL JNI(surfaceResized)(JNIEnv*, jobject) { g_surfaceResized = true; }

// Note: loadGame runs on the UI thread and makes the context current there;
// start() hands the context to the emulation thread.
JNIEXPORT void JNICALL JNI(setVulkanDriver)(JNIEnv* env, jobject, jstring dir, jstring name, jstring hooks, jstring tmp) {
    auto str = [env](jstring s) { const char* c = env->GetStringUTFChars(s, nullptr); std::string r = c; env->ReleaseStringUTFChars(s, c); return r; };
    VkContext::setCustomDriver(str(dir).c_str(), str(name).c_str(), str(hooks).c_str(), str(tmp).c_str());
}

JNIEXPORT jstring JNICALL JNI(probeVulkan)(JNIEnv* env, jobject, jboolean custom) {
    return env->NewStringUTF(VkContext::probe(custom).c_str());
}

// Sharp display (sharp-bilinear final scale); MainActivity also turns the VI blur filters off.
JNIEXPORT void JNICALL JNI(setSharp)(JNIEnv*, jobject, jboolean on) {
    g_vk.setSharp(on);
}

JNIEXPORT void JNICALL JNI(setRenderer)(JNIEnv*, jobject, jboolean vulkan) {
    g_vulkan = vulkan;
    g_optionOverrides["mupen64plus-rdp-plugin"] = vulkan ? "parallel" : "gliden64";
    g_optionOverrides["mupen64plus-rsp-plugin"] = vulkan ? "parallel" : "hle";
    g_optionsDirty = true;
}

JNIEXPORT jboolean JNICALL JNI(loadGame)(JNIEnv* env, jobject, jstring rom, jstring saveName) {
    if (!g_vulkan) {
        if (!g_egl.init()) return JNI_FALSE;
        applySurfaceIfChanged();
    }

    const char* p = env->GetStringUTFChars(rom, nullptr);
    FILE* f = fopen(p, "rb");
    std::vector<uint8_t> buf;
    if (f) { fseek(f, 0, SEEK_END); buf.resize(ftell(f)); fseek(f, 0, SEEK_SET); fread(buf.data(), 1, buf.size(), f); fclose(f); }
    // Sync by default on Adreno (MainActivity). Elsewhere, games that break with async parallel-RDP (their
    // CPU reads the framebuffer, which async can serve stale): Pokemon Snap
    // (photo checks), The New Tetris (async: halts on a "secret cheat, power off" screen after ~10 s),
    // Resident Evil 2 and Cruis'n USA (async: black screen). An explicit option override (--es opts) wins.
    static const char* const kSyncRdpGames[] = {"NPF", "NRI", "NRE", "NCU"};
    // Also kept synchronous in fast-forward: Yoshi's Story renders black with async on Adreno.
    static const char* const kAdrenoSyncGames[] = {"NYS"};
    std::string id = romGameId(buf);
    auto idIn = [&](auto& list) { return std::any_of(std::begin(list), std::end(list), [&](const char* g) { return id.compare(0, 3, g) == 0; }); };
    g_ffKeepSync = idIn(kSyncRdpGames) || idIn(kAdrenoSyncGames);
    auto syncOpt = g_optionOverrides.find("mupen64plus-parallel-rdp-synchronous");
    if (syncOpt != g_optionOverrides.end()) g_rdpSync = syncOpt->second == "True";
    else g_rdpSync = idIn(kSyncRdpGames);
    g_optionOverrides["mupen64plus-parallel-rdp-synchronous"] = g_rdpSync ? "True" : "False";
    g_optionsDirty = true;
    // CPU core: the dynamic recompiler, except games it crashes. Sin and Punishment (the English patch):
    // jumps to a bogus address right after boot; fine on the cached interpreter. --es opts wins.
    static const char* const kInterpreterGames[] = {"NGU"};
    if (!g_optionOverrides.count("mupen64plus-cpucore"))
        g_optionOverrides["mupen64plus-cpucore"] =
            std::any_of(std::begin(kInterpreterGames), std::end(kInterpreterGames),
                        [&](const char* g) { return id.compare(0, 3, g) == 0; }) ? "cached_interpreter" : "dynamic_recompiler";
    LOGI("game id %s, parallel-RDP %s, %s", id.c_str(), g_rdpSync ? "synchronous" : "async",
         g_optionOverrides["mupen64plus-cpucore"].c_str());
    retro_game_info info{ p, buf.data(), buf.size(), nullptr };
    bool ok = !buf.empty() && core.load_game(&info);
    env->ReleaseStringUTFChars(rom, p);
    if (!ok) { LOGE("retro_load_game failed"); return JNI_FALSE; }

    core.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    core.cheat_reset();
    for (size_t i = 0; i < g_cheats.size(); ++i) {
        core.cheat_set((unsigned)i, true, g_cheats[i].c_str());
        LOGI("cheat %zu: %s", i, g_cheats[i].c_str());
    }

    uint64_t hash = 1469598103934665603ull; // FNV-1a of the ROM
    for (uint8_t b : buf) hash = (hash ^ b) * 1099511628211ull;
    char hashName[32];
    snprintf(hashName, sizeof hashName, "/%016llx.srm", (unsigned long long)hash);
    const char* sn = env->GetStringUTFChars(saveName, nullptr);
    std::string named = sn;
    env->ReleaseStringUTFChars(saveName, sn);
    if (named.empty()) {
        g_savePath = g_saveDir + hashName;
        g_saveFallbackPath.clear();
    } else {
        g_savePath = g_saveDir + "/" + named + ".srm";
        g_saveFallbackPath = g_saveDir + hashName;
    }
    loadSave();
    core.get_system_av_info(&g_av);
    LOGI("game loaded: %ux%u max, %.2f fps, %.0f Hz", g_av.geometry.max_width, g_av.geometry.max_height,
         g_av.timing.fps, g_av.timing.sample_rate);
    g_vk.setContentRate(g_av.timing.fps);

    if (g_vulkan) {
        if (!g_hwRequested || g_hw.context_type != RETRO_HW_CONTEXT_VULKAN) {
            LOGE("core did not request a Vulkan context"); return JNI_FALSE;
        }
        if (!g_vk.init(g_negotiation)) return JNI_FALSE;
        applySurfaceIfChanged();
        if (g_hw.context_reset) g_hw.context_reset();
    } else {
        g_egl.ensureFbo(g_av.geometry.max_width, g_av.geometry.max_height);
        if (g_hwRequested && g_hw.context_reset) g_hw.context_reset();
    }
    g_audio.open((int)(g_av.timing.sample_rate + 0.5));

    if (!g_vulkan) // release for run thread
        eglMakeCurrent(eglGetCurrentDisplay(), EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI(start)(JNIEnv*, jobject) {
    if (g_running) return;
    g_running = true;
    g_thread = std::thread(runLoop);
}

JNIEXPORT void JNICALL JNI(pause)(JNIEnv*, jobject, jboolean p) {
    g_paused = p;
    g_audio.setPaused(p);
    g_pauseCv.notify_all();
}

JNIEXPORT void JNICALL JNI(setOption)(JNIEnv* env, jobject, jstring key, jstring value) {
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    g_optionOverrides[k] = v;
    g_optionsDirty = true;
    LOGI("option override: %s = %s", k, v);
    env->ReleaseStringUTFChars(key, k);
    env->ReleaseStringUTFChars(value, v);
}

JNIEXPORT void JNICALL JNI(addCheat)(JNIEnv* env, jobject, jstring code) {
    const char* c = env->GetStringUTFChars(code, nullptr);
    g_cheats.emplace_back(c);
    env->ReleaseStringUTFChars(code, c);
}

JNIEXPORT void JNICALL JNI(dumpRam)(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    { std::lock_guard<std::mutex> lk(g_dumpMutex); g_dumpPath = p; }
    env->ReleaseStringUTFChars(path, p);
}

JNIEXPORT void JNICALL JNI(setFastForward)(JNIEnv*, jobject, jboolean on) {
    g_fastForward = on;
}

JNIEXPORT jint JNICALL JNI(rumble)(JNIEnv*, jobject) {
    return (jint)std::max(g_rumbleWeak.load(), g_rumbleStrong.load());
}

JNIEXPORT jboolean JNICALL JNI(waitRumble)(JNIEnv*, jobject, jboolean wasOn, jint timeoutMs) {
    auto isOn = [] { return std::max(g_rumbleWeak.load(), g_rumbleStrong.load()) > 0; };
    std::unique_lock<std::mutex> lock(g_rumbleMutex);
    g_rumbleChanged.wait_for(lock, std::chrono::milliseconds(timeoutMs), [&] { return isOn() != (bool)wasOn; });
    return isOn();
}

JNIEXPORT jint JNICALL JNI(framesRun)(JNIEnv*, jobject) {
    return (jint)g_framesRun.load();
}
JNIEXPORT jint JNICALL JNI(framesDrawn)(JNIEnv*, jobject) {
    return (jint)g_framesDrawn.load();
}
JNIEXPORT jdouble JNICALL JNI(contentFps)(JNIEnv*, jobject) {
    return g_av.timing.fps;
}

JNIEXPORT void JNICALL JNI(stop)(JNIEnv*, jobject) {
    if (!g_running) return;
    g_running = false;
    g_pauseCv.notify_all();
    if (g_thread.joinable()) g_thread.join();
}

JNIEXPORT void JNICALL JNI(setButtons)(JNIEnv*, jobject, jint mask) {
    g_tapped |= mask & ~g_buttons.exchange(mask);
}

JNIEXPORT void JNICALL JNI(setAnalog)(JNIEnv*, jobject, jint i, jint x, jint y) {
    if (i < 0 || i > 1) return;
    g_analog[i][0] = x; g_analog[i][1] = y;
}

} // extern "C"
