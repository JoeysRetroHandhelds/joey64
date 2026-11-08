// Minimal libretro frontend: dlopen the core, answer environment calls,
// provide a GLES3 or Vulkan HW render context, and run retro_run on a dedicated thread.
#include "joeylog.h"
#include <adrenotools/driver.h>
#include "perf_hint.h"
#include "gpu_clock_pin.h"
#include "gpu_counters.h"
#include "thermal.h"
#include "display_opts.h"
#include "rewind_test.h"
#include "rewind.h"
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdlib>
#include <android/native_window_jni.h>
#include <algorithm>
#include <cmath>
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
#include "achievements.h"

// Dev env N64_LOG_ERR=1 logs info at error level, for devices whose logd drops everything
// below error (Retroid Pocket Duo).
#define LOGI(...) joeyLog(ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)
#define LOGE(...) joeyLog(ANDROID_LOG_ERROR, "joey64host", __VA_ARGS__)

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
    bool (*load_game_special)(unsigned, const retro_game_info*, size_t); // 64DD disks; optional
    void (*unload_game)();
    void (*run)();
    void* (*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
    void (*cheat_reset)();
    void (*cheat_set)(unsigned, bool, const char*);
    bool (*unserialize)(const void*, size_t);
    size_t (*serialize_size)();
    bool (*serialize)(void*, size_t);
    void (*reset)();
} core;

std::string g_systemDir, g_saveDir;
std::string g_ddCart; // a 64DD disk's cartridge (NativeBridge.setDdCart), "" = the disk alone

// A 64DD disk image by its extension (MainActivity unpacks disks under their own name for this).
static bool isDiskPath(const char* p) {
    std::string s(p);
    size_t dot = s.rfind('.');
    if (dot == std::string::npos) return false;
    std::string ext = s.substr(dot + 1);
    for (auto& c : ext) c = (char)tolower((unsigned char)c);
    return ext == "ndd" || ext == "d64";
}
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
// Frame advance (the hotkey): frames still to run while paused, one per press.
std::atomic<int> g_stepFrames{0};
std::mutex g_pauseMutex;
std::condition_variable g_pauseCv;
// Fast-forward: vsync off, audio dropped, emulation capped at 4x normal (lower while the
// N64_THERMAL_GOV=1 governor says so: thermal.h).
std::atomic<bool> g_fastForward{false};
// The user's fast-forward speed (Controls > Hotkeys > Fast-forward), or Adaptive's from the trigger, in
// percent of normal; the thermal cap still applies on top.
std::atomic<int> g_ffSpeedPct{400};
// Settings › Audio's Fast-forward sound: 0 mute (as before), 1 quieter (half the loudness), 2 full.
std::atomic<int> g_ffSound{0};
// The speed fast-forward runs at now (speed setting under the thermal cap), for its sound.
std::atomic<double> g_ffSpeedNow{1.0};
// Slow motion (approved Slow motion and Rewind mockup, 2026-11-08): percent of full speed, 0 = off. Its sound
// goes through writeFast at that speed (slower and lower), as Settings › Audio's Fast-forward sound says.
std::atomic<int> g_slowPct{0};
// The speed slow motion runs at this frame (0: not slowed), for the audio callbacks.
std::atomic<double> g_slowNow{0.0};
// Rewind (same mockup; rewind.h). Settings come from any thread and are applied on the emulation thread.
Rewind g_rewind;
std::atomic<bool> g_rewindCfgChanged{false}, g_rewindOnCfg{false};
std::atomic<int> g_rewindEveryCfg{2}, g_rewindMaxCfg{1800};
// The rewind hotkey is held; g_rewindActive: going back this frame (sound dropped, no achievements).
std::atomic<bool> g_rewindHeld{false}, g_rewindActive{false}, g_rewindAtOldest{false};
// Frames gone back since the rewind hotkey went down.
std::atomic<int> g_rewindBackFrames{0};
// Benchmark (dev env N64_BENCH=1): no speed cap, vsync off, audio dropped, settings unchanged and every frame
// presented, so the counter shows how fast the game could run: the headroom a full-speed game has.
bool g_bench = false;
// parallel-RDP synchronous mode outside fast-forward. Async by default (sync makes the CPU wait for
// the GPU at every RDP sync: ~10 ms/frame in Snap on the Thor vs 2-4 ms); per game in loadGame.
bool g_rdpSync = false;
// Games whose CPU reads what the RDP drew and that break with async parallel-RDP: they stay synchronous
// in fast-forward too (async there gave black screens or the New Tetris halt). Set in loadGame.
bool g_ffKeepSync = false;
// GameShark codes, one cheat per string (its codes joined by any separator): applied after
// load_game, and again between frames when the Cheats page changes them (setCheats).
std::vector<std::string> g_cheats;
std::mutex g_cheatsMutex;
std::atomic<bool> g_cheatsChanged{false};
// Core options changed while the game runs (Game settings from the pause menu): the UI thread queues
// them here and the emulation thread moves them into g_optionOverrides between frames, since it reads
// and writes that map itself (fast-forward) without a lock.
std::vector<std::pair<std::string, std::string>> g_liveOptions;
std::mutex g_liveOptionsMutex;
std::atomic<bool> g_liveOptionsChanged{false};
// Upscaling costs the GPU most of the fast-forward headroom (Odin 3 at 4x: Peach's Fury 1.5x, GoldenEye 1.03x),
// so fast-forward renders at 1x. The setting in use before is restored after ("" = none was overridden).
constexpr const char* kUpscaleKey = "mupen64plus-parallel-rdp-upscaling";
std::string g_upscaleBeforeFf;
bool g_upscaleFfApplied = false;
static void applyLiveOptions() {
    std::vector<std::pair<std::string, std::string>> next;
    {
        std::lock_guard<std::mutex> lk(g_liveOptionsMutex);
        next.swap(g_liveOptions);
    }
    for (auto& [k, v] : next) {
        // The upscale hotkeys during fast-forward: it keeps 1x, and the new value is the one it restores.
        if (k == kUpscaleKey && g_upscaleFfApplied) {
            g_upscaleBeforeFf = v;
            LOGI("option override (live, after fast-forward): %s = %s", k.c_str(), v.c_str());
            continue;
        }
        g_optionOverrides[k] = v;
        LOGI("option override (live): %s = %s", k.c_str(), v.c_str());
    }
    if (!next.empty()) g_optionsDirty = true;
}
// Debug: RDRAM dump requested from the UI thread, written by the emulation thread between frames.
std::mutex g_dumpMutex;
std::string g_dumpPath;

// Per N64 port (Controls, Phase 4: up to four players).
constexpr int kPorts = 4;
std::atomic<int> g_buttons[kPorts] = {};
std::atomic<int> g_tapped[kPorts] = {}; // presses not yet seen by a poll, so sub-frame taps still register
int g_polledButtons[kPorts] = {};       // emulation thread only
std::atomic<int> g_analog[kPorts][2][2] = {};
// Ports with a controller plugged in (bit per port), applied after load_game. Default: player 1 only.
std::atomic<int> g_portsPresent{1};
std::atomic<bool> g_portsChanged{false}; // set while a game runs: applied between frames

// The GameShark cartridge's button (hotkey, or the pause menu's Cheats). The core only exports libretro's
// functions, so its own event_set_gameshark can't be called; it runs a button code (88/89/A8/A9 writes,
// D8-DB tests) exactly like the plain code (80/81/A0/A1, D0-D3) while the button is down and skips it while
// up. So while it's held the core is given the plain codes instead (gameSharkHeld), between frames.
std::atomic<bool> g_gsButton{false};

// [line] with every button code's address made the plain one: "88123456 0001" -> "80123456 0001".
// Codes are 8 + 4 hex digits with any separators; only addresses (every other hex run) change.
std::string gameSharkHeld(const std::string& line) {
    std::string out = line;
    size_t i = 0;
    int run = 0;
    auto hex = [](char ch) { return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F'); };
    while (i < out.size()) {
        if (!hex(out[i])) { ++i; continue; }
        size_t start = i;
        while (i < out.size() && hex(out[i])) ++i;
        if (run++ % 2 == 0 && i - start == 8) {
            char a = (char)toupper(out[start]), b = (char)toupper(out[start + 1]);
            const char* to = nullptr;
            if (a == '8' && b == '8') to = "80"; else if (a == '8' && b == '9') to = "81";
            else if (a == 'A' && b == '8') to = "A0"; else if (a == 'A' && b == '9') to = "A1";
            else if (a == 'D' && b == '8') to = "D0"; else if (a == 'D' && b == '9') to = "D1";
            else if (a == 'D' && b == 'A') to = "D3"; else if (a == 'D' && b == 'B') to = "D2";
            if (to) { out[start] = to[0]; out[start + 1] = to[1]; }
        }
    }
    return out;
}

// Gives the core the cheats in g_cheats, replacing what it had. On the emulation thread (or before it starts).
void applyCheats() {
    std::lock_guard<std::mutex> lk(g_cheatsMutex);
    core.cheat_reset();
    const bool held = g_gsButton.load();
    for (size_t i = 0; i < g_cheats.size(); ++i) {
        const std::string line = held ? gameSharkHeld(g_cheats[i]) : g_cheats[i];
        core.cheat_set((unsigned)i, true, line.c_str());
        LOGI("cheat %zu: %s", i, line.c_str());
    }
    if (g_cheats.empty()) LOGI("cheats: none");
}

void applyPorts() {
    int m = g_portsPresent.load();
    for (unsigned p = 0; p < (unsigned)kPorts; ++p)
        core.set_controller_port_device(p, (m >> p) & 1 ? RETRO_DEVICE_JOYPAD : RETRO_DEVICE_NONE);
    LOGI("controller ports present: 0x%x", m);
}
unsigned g_lastW = 0, g_lastH = 0;
int g_statRuns = 0, g_statPresents = 0, g_statDupes = 0; // emulation thread only
double g_statRunMs = 0, g_statWaitMs = 0;                 // time in retro_run / beginFrame
double g_statAudioWaitMs = 0;                             // audio sync before each frame
// Hitches: the slowest frame (beginFrame + retro_run + endFrame; no speed cap, audio wait or pause) and the
// frames over 33 ms, which skip a frame even at normal speed: what averages hide, such as a shader compile.
double g_statSlowestMs = 0; int g_statSlowFrames = 0;
// Settings › On screen's frame times graph: each frame's work (noteFrameTime: acquire, emulation and
// present, without the speed cap's or audio's waits; GLideN64's includes its vsync wait), the last
// kFrameRing of them. Written by the emulation thread, read by the UI (a torn read bends one bar).
// The time between frames was tried first: on the Thor's 120 Hz screen audio sync spreads it 8-37 ms
// at full speed, so half the bars looked slow while no frame was.
constexpr uint32_t kFrameRing = 128;
float g_frameRing[kFrameRing];
std::atomic<uint32_t> g_frameRingCount{0};

// Send reports (reports/ in the app): the whole run's numbers, read by sessionStats when the session ends.
// Frame times as a histogram of 1 ms buckets (the last holds 99 ms and more) for the 95th and 99th
// percentiles; the stats window's underruns, poll->display latency and thermal readings added up.
// Written by the emulation thread; a read from the UI thread that tears is off by a frame.
constexpr int kSessBuckets = 100;
std::atomic<uint32_t> g_sessFrameHist[kSessBuckets];
std::atomic<uint32_t> g_sessSlowFrames{0};
std::atomic<int> g_sessUnderruns{0}, g_sessLatWindows{0}, g_sessThermalMax{-1}, g_sessGovernorDrops{0};
std::atomic<double> g_sessLatSum{0}, g_sessLatMax{0};
std::atomic<float> g_sessHeadroomMax{-1};
std::chrono::steady_clock::time_point g_loadStart;   // loadGame's start, for the time to the first frame
std::atomic<double> g_firstFrameMs{-1};
std::atomic<int> g_sessInterpreter{-1};             // the CPU core loadGame chose: 1 interpreter, 0 dynarec

void noteFrameTime(double ms) {
    uint32_t n = g_frameRingCount.load(std::memory_order_relaxed);
    g_frameRing[n % kFrameRing] = (float)ms;
    g_frameRingCount.store(n + 1, std::memory_order_release);
    if (ms > g_statSlowestMs) g_statSlowestMs = ms;
    if (ms > 33.0) { ++g_statSlowFrames; g_sessSlowFrames.fetch_add(1, std::memory_order_relaxed); }
    g_sessFrameHist[std::min(std::max((int)ms, 0), kSessBuckets - 1)].fetch_add(1, std::memory_order_relaxed);
}
// For the counter ("60 FPS | 20 DL/s | 100%"): emulated frames (retro_run calls, the game's speed) and new
// frames presented (what the game drew: OoT 20 a second once repeats are skipped).
std::atomic<uint32_t> g_framesRun{0}, g_framesDrawn{0};
// A new frame drawn; the first one notes the time since loadGame started (reports: how long a boot takes).
void noteDrawn() {
    if (g_framesDrawn.fetch_add(1) == 0)
        g_firstFrameMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - g_loadStart).count();
}
// The core asked to shut down (RETRO_ENVIRONMENT_SHUTDOWN): it could not start the game; MainActivity polls it.
std::atomic<bool> g_coreShutDown{false};
// The core said it could not load the 64DD IPL (its region's file, else IPL.n64, is missing): why a disk didn't start,
// as against a disk or disk save it could not read.
std::atomic<bool> g_ddIplMissing{false};
// Rumble Pak, players 1-4 (players 2-4 since 2026-11-07; before, the core's calls for ports 1-3 were
// refused): the core switches each motor on/off; a UI-side thread waits in waitRumble for any player's
// motor to change and drives that player's controller (or the device's) vibrator. Polling it from a
// per-vsync callback instead cost ~60 ms of CPU per second on the Duo (120 Hz, debug build).
std::atomic<uint16_t> g_rumbleWeak[4], g_rumbleStrong[4];   // static storage: start at 0
std::mutex g_rumbleMutex;
std::condition_variable g_rumbleChanged;
// Bit p set: player p+1's motor is on.
int rumbleMask() {
    int m = 0;
    for (int p = 0; p < 4; ++p) if (std::max(g_rumbleWeak[p].load(), g_rumbleStrong[p].load()) > 0) m |= 1 << p;
    return m;
}
bool setRumble(unsigned port, retro_rumble_effect effect, uint16_t strength) {
    if (port > 3) return false;
    int was = rumbleMask();
    (effect == RETRO_RUMBLE_STRONG ? g_rumbleStrong[port] : g_rumbleWeak[port]) = strength;
    int now = rumbleMask();
    if (now != was) {
        LOGI("rumble player %u %s", port + 1, (now >> port) & 1 ? "on" : "off");
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
    {   // any level: the core sends its errors at a lower one than RETRO_LOG_ERROR
        char line[512];
        va_list cp; va_copy(cp, ap);
        vsnprintf(line, sizeof line, fmt, cp);
        va_end(cp);
        if (strstr(line, "Failed to load DD IPL ROM")) g_ddIplMissing = true;
    }
    joeyLogV(level >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : level == RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, "core", fmt, ap);
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
    case RETRO_ENVIRONMENT_SHUTDOWN:
        // The core could not start the game (e.g. a 64DD disk whose region has no IPL BIOS); it then runs no frames.
        LOGE("core: shut down, the game did not start");
        g_coreShutDown = true;
        return true;
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
        else { g_lastW = w; g_lastH = h; ++g_statPresents; noteDrawn(); }
        g_vk.videoRefresh(data, w, h);
        return;
    }
    if (!data) { ++g_statDupes; return; }     // dupe frame: keep previous
    g_lastW = w; g_lastH = h;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        g_egl.present(w, h, g_hw.bottom_left_origin);
        ++g_statPresents;
        noteDrawn();
    }
    // Software frames are not expected from mupen64plus-next with a HW context.
}

void audio_sample(int16_t l, int16_t r) {
    if (g_bench || g_paused) return; // paused: a frame advance step, whose sound would pile up in the paused stream
    if (g_rewindActive) return; // going back: no sound
    int16_t s[2] = {l, r};
    if (double sl = g_slowNow.load(); sl > 0) { if (int m = g_ffSound.load()) g_audio.writeFast(s, 1, sl, m == 1 ? 0.25f : 1.0f); return; }
    if (g_fastForward) { if (int m = g_ffSound.load()) g_audio.writeFast(s, 1, g_ffSpeedNow.load(), m == 1 ? 0.25f : 1.0f); return; }
    g_audio.write(s, 1);
}
size_t g_frameAudio = 0; // audio frames the core gave during this retro_run (N64_FRAME_TRACE)
size_t audio_batch(const int16_t* d, size_t frames) {
    if (g_bench || g_paused) return frames; // paused: a frame advance step (the stream is paused, nothing drains)
    if (g_rewindActive) return frames; // going back: no sound
    // A blocking write would throttle fast-forward: its sound (if any) goes through writeFast; slow motion's too.
    if (double sl = g_slowNow.load(); sl > 0) { if (int m = g_ffSound.load()) g_audio.writeFast(d, frames, sl, m == 1 ? 0.25f : 1.0f); }
    else if (g_fastForward) { if (int m = g_ffSound.load()) g_audio.writeFast(d, frames, g_ffSpeedNow.load(), m == 1 ? 0.25f : 1.0f); }
    else g_audio.write(d, frames);
    g_frameAudio += frames;
    return frames;
}
// Dev: N64_FRAME_TRACE=1 logs 300 frames from frame 900 on (after the state load), 30 per line: audio frames the
// core gave in each retro_run, ms waited for audio room before it, ms between frame starts. Finds bursty pacing.
void traceFrame(double audioWaitMs) {
    static const bool on = getenv("N64_FRAME_TRACE") && *getenv("N64_FRAME_TRACE") == '1';
    static std::chrono::steady_clock::time_point last;
    static std::string a, w, s;
    static int n = 0;
    auto now = std::chrono::steady_clock::now();
    double since = std::chrono::duration<double, std::milli>(now - last).count();
    last = now;
    size_t audio = g_frameAudio;
    g_frameAudio = 0;
    uint32_t f = g_framesRun.load();
    if (!on || f < 900 || f >= 1200) return;
    char b[32];
    snprintf(b, sizeof b, "%zu ", audio); a += b;
    snprintf(b, sizeof b, "%.1f ", audioWaitMs); w += b;
    snprintf(b, sizeof b, "%.1f ", since); s += b;
    if (++n == 30) {
        LOGI("trace: audio frames %s| audio wait ms %s| frame start gap ms %s", a.c_str(), w.c_str(), s.c_str());
        a.clear(); w.clear(); s.clear(); n = 0;
    }
}
// Taps are held for kMinPressPolls polls: some games (Pokemon Snap's tutorial) ignore a press
// that is visible for a single frame.
constexpr int kMinPressPolls = 4;
int g_tapHold[kPorts][16] = {};
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
    for (int p = 0; p < kPorts; ++p) {
        int taps = g_tapped[p].exchange(0), held = 0;
        for (int i = 0; i < 16; ++i) {
            if (taps & (1 << i)) g_tapHold[p][i] = kMinPressPolls;
            if (g_tapHold[p][i] > 0) { --g_tapHold[p][i]; held |= 1 << i; }
        }
        g_polledButtons[p] = g_buttons[p].load() | held;
    }
    if (g_vulkan) {
        timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); // the clock display timing reports in
        g_vk.notePoll(ts.tv_sec * 1000000000LL + ts.tv_nsec);
    }
}

int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port >= (unsigned)kPorts) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
        if (g_pollPending) { g_pollPending = false; sampleButtons(); }
        int mask = g_polledButtons[port];
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)mask;
        return (mask >> id) & 1;
    }
    if (device == RETRO_DEVICE_ANALOG && index < 2 && id < 2)
        return (int16_t)g_analog[port][index][id].load();
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

std::string gpuClockText();
// Logs emulated frames/s (retro_run calls), presented and duped frames every 5 s.
void logStats() {
    using clock = std::chrono::steady_clock;
    static clock::time_point start = clock::now();
    ++g_statRuns;
    ++g_framesRun;
    double secs = std::chrono::duration<double>(clock::now() - start).count();
    if (secs < 5.0) return;
    char lat[256] = "";
    double latMean, latMax; int uneven;
    if (g_vulkan && g_vk.takeLatency(latMean, latMax, uneven))
        snprintf(lat, sizeof lat, ", poll->display %.1f ms (max %.1f), %d uneven%s", latMean, latMax, uneven,
                 g_vk.takeCadence().c_str());
    double fillMs, targetMs; int underruns;
    g_audio.takeStats(!g_fastForward && !g_bench && g_statRuns / secs >= 58.5, fillMs, targetMs, underruns);
    Thermal::update();
    // Reports: underruns and lag from the third window on. The first 10 s are the boot and the audio buffer
    // settling (Super Mario 64 on the Thor 2026-11-08: 89 + 40 underruns and a 486 ms lag max, then none).
    static int windows = 0;
    const bool settled = ++windows > 2;
    if (settled) g_sessUnderruns += underruns;
    if (settled && lat[0]) {
        g_sessLatSum = g_sessLatSum.load() + latMean; ++g_sessLatWindows;
        if (latMax > g_sessLatMax.load()) g_sessLatMax = latMax;
    }
    {
        int st = Thermal::status(); float hr = Thermal::headroom();
        if (st > g_sessThermalMax.load()) g_sessThermalMax = st;
        if (!std::isnan(hr) && hr > g_sessHeadroomMax.load()) g_sessHeadroomMax = hr;
        g_sessGovernorDrops = Thermal::governorDrops();
    }
    LOGI("stats: %.1f emu fps, %.1f presented, %.1f dupes (%ux%u), run %.2f ms, wait %.2f ms, "
         "audio wait %.2f ms, fill %.0f/%.0f ms, %d underruns, device %.0f ms%s, slowest frame %.1f ms, %d over 33 ms%s%s",
         g_statRuns / secs, g_statPresents / secs, g_statDupes / secs, g_lastW, g_lastH,
         g_statRunMs / g_statRuns, g_statWaitMs / g_statRuns, g_statAudioWaitMs / g_statRuns, fillMs,
         targetMs, underruns, g_audio.outputLatencyMs(), lat, g_statSlowestMs, g_statSlowFrames,
         gpuClockText().c_str(), Thermal::text().c_str());
    g_statRuns = g_statPresents = g_statDupes = 0;
    g_statRunMs = g_statWaitMs = g_statAudioWaitMs = g_statSlowestMs = 0;
    g_statSlowFrames = 0;
    start = clock::now();
}

// GPU clock for the stats line, where the app may read it (Adreno kgsl sysfs; "" elsewhere or when SELinux denies it)
std::string gpuClockText() {
    static bool readable = true;
    if (!readable) return "";
    FILE* f = fopen("/sys/class/kgsl/kgsl-3d0/gpuclk", "r");
    long hz = 0;
    if (!f || fscanf(f, "%ld", &hz) != 1 || hz <= 0) { if (f) fclose(f); readable = false; LOGI("GPU clock: not readable here"); return ""; }
    fclose(f);
    return ", gpu " + std::to_string(hz / 1000000) + " MHz";
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

// ---- save states --------------------------------------------------------------
// The core's state (retro_serialize: the M64+SAVE data) is taken and put back on the emulation
// thread between frames, the only place the core can do it. The UI side (StateFiles.kt) wraps it in
// RetroArch's state format and writes the file, so this side never touches the data folder.
// One request at a time: the JNI call waits for the emulation thread to serve it, also while the
// game is paused (a pause menu saves with the game paused).
struct StateRequest {
    bool save = true;
    // A picture instead of a state: the last frame the core drew, top-down RGBA8 in [data], [w] x [h]
    // (screenshots, the picture beside a state slot).
    bool capture = false;
    // The pause menu's Reset: the core's retro_reset, between frames like the rest.
    bool reset = false;
    unsigned w = 0, h = 0;
    std::vector<uint8_t> data;   // filled by a save, read by a load
    std::vector<uint8_t> achv;   // the achievements' progress with it (RetroArch's ACHV block)
    bool done = false, ok = false;
};
std::mutex g_stateMutex;
std::condition_variable g_stateCv;
StateRequest* g_stateReq = nullptr;
std::atomic<bool> g_statePending{false};
// mupen64plus-next can't serialize before its emulation has started, so a request waits for the first
// frame. 1 frame (Joey 2026-11-06, was 30 like the dev N64_STATE load, which keeps 30): the Thor's
// 14-game test loaded all 56 Continues either way, done ~400 ms sooner. Dev env N64_STATE_READY=<frames>.
int stateReadyFrames() {
    static const int n = getenv("N64_STATE_READY") && *getenv("N64_STATE_READY") ? atoi(getenv("N64_STATE_READY")) : 1;
    return n;
}

// Serves a waiting request. [ready]: the core has run long enough. Too early, a request waits for a
// later frame, unless [failEarly] (paused: no frame is coming), when it fails.
void serveStateRequest(bool ready, bool failEarly) {
    if (!g_statePending) return;
    std::lock_guard<std::mutex> lk(g_stateMutex);
    StateRequest* r = g_stateReq;
    if (!r) { g_statePending = false; return; }
    if (!ready && !failEarly) return;
    auto t0 = std::chrono::steady_clock::now();
    const char* what = r->reset ? "reset" : r->capture ? "capture" : r->save ? "save" : "load";
    if (!ready) {
        r->ok = false;
        LOGE("state: %s asked for before the core was ready", what);
    } else if (r->reset) {
        core.reset();
        Cheevos::reset();
        r->ok = true;
        g_rewind.clear(); // rewind starts over from here
    } else if (r->capture) {
        r->ok = g_vulkan ? g_vk.capture(r->data, r->w, r->h)
                         : g_egl.capture((int)g_lastW, (int)g_lastH, g_hw.bottom_left_origin, r->data);
        if (!g_vulkan) { r->w = g_lastW; r->h = g_lastH; }
        if (!r->ok) r->data.clear();
    } else if (r->save) {
        size_t n = core.serialize_size();
        r->data.resize(n);
        r->ok = n > 0 && core.serialize(r->data.data(), n);
        if (!r->ok) r->data.clear();
        else Cheevos::captureProgress(r->achv);
    } else if (r->data.size() < 44 || memcmp(r->data.data(), "M64+SAVE", 8) != 0) {
        // Not the core's data. mupen64plus-next must never see it: its libretro load returns on a wrong
        // magic without unlocking savestates_lock, and the emulation thread then hangs (found on the Thor
        // 2026-11-06 with a damaged file); it also reads the 44-byte header from shorter data.
        r->ok = false;
        LOGE("state: not mupen64plus-next state data (%zu bytes), not loaded", r->data.size());
    } else {
        // Note: retro_unserialize returns true even when the core turns the state down (another ROM,
        // an incompatible version); the core logs why.
        r->ok = core.unserialize(r->data.data(), r->data.size());
        // Achievements carry on from the progress saved with the state, or start over without one.
        if (r->ok) Cheevos::stateLoaded(r->achv.data(), r->achv.size());
        if (r->ok) g_rewind.clear(); // rewind starts over from the loaded moment
    }
    if (ready)
        joeyLog(r->ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "joey64host", "state: %s %s, %zu bytes, %.1f ms", what, r->ok ? "done" : "FAILED", r->data.size(),
             std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    r->done = true;
    g_stateReq = nullptr;
    g_statePending = false;
    g_stateCv.notify_all();
}

// Hands [req] to the emulation thread and waits for it, at most [timeoutMs]. False if it wasn't served.
bool runStateRequest(StateRequest& req, int timeoutMs) {
    std::unique_lock<std::mutex> lk(g_stateMutex);
    if (!g_running || g_stateReq) return false;
    g_stateReq = &req;
    g_statePending = true;
    { std::lock_guard<std::mutex> pl(g_pauseMutex); }   // so a paused emulation thread can't miss the wake-up
    g_pauseCv.notify_all();
    bool served = g_stateCv.wait_for(lk, std::chrono::milliseconds(timeoutMs), [&] { return req.done || !g_running; });
    if (!req.done) {
        if (g_stateReq == &req) { g_stateReq = nullptr; g_statePending = false; }
        LOGE("state: %s not served (%s)", req.reset ? "reset" : req.capture ? "capture" : req.save ? "save" : "load", served ? "game stopped" : "timed out");
        return false;
    }
    return true;
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

// The core's worker threads (parallel-RDP's command worker, fence and pipeline threads, ...): they are not named,
// so they carry the process name like the emulation thread; the Java main thread (tid == pid) is left out.
std::vector<int32_t> coreWorkerThreads() {
    std::vector<int32_t> out;
    char self[32] = {0}, name[32];
    if (FILE* f = fopen("/proc/self/comm", "r")) { if (!fgets(self, sizeof self, f)) self[0] = 0; fclose(f); }
    const pid_t me = gettid(), pid = getpid();
    if (DIR* d = opendir("/proc/self/task")) {
        while (dirent* e = readdir(d)) {
            pid_t tid = atoi(e->d_name);
            if (tid <= 0 || tid == me || tid == pid) continue;
            std::string p = "/proc/self/task/" + std::to_string(tid) + "/comm";
            FILE* f = fopen(p.c_str(), "r");
            if (!f) continue;
            if (fgets(name, sizeof name, f) && strcmp(name, self) == 0) out.push_back(tid);
            fclose(f);
        }
        closedir(d);
    }
    return out;
}

// Emulation thread priority: nice -19 (Android's THREAD_PRIORITY_URGENT_AUDIO, mupen64plus-ae's "high priority
// thread"), the default since 2026-11-06 (Joey's decision once the Odin 3 compat run with it, odin3-priocompat2, was
// 82/82; uncapped speed measured the same as nice 0 on all four devices).
// Dev: N64_THREAD_PRIO=<nice> sets another value (0 = Android's normal, the old default; -10 urgent display).
// N64_THREAD_PRIO_ALL=1 also gives the other threads already running in the process (parallel-RDP workers,
// audio) the same value.
void setEmuThreadPriority() {
    const char* env = getenv("N64_THREAD_PRIO");
    int nice = env && *env ? atoi(env) : -19;
    if (nice == 0) return;
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
    // EXPERIMENT (Performance mode's Battery saver, 2026-11-08): N64_PIN=0 leaves the threads where the
    // scheduler puts them: no big-core pinning and no split.
    const char* pinEnv = getenv("N64_PIN");
    const bool pin = !(pinEnv && *pinEnv == '0');
    if (pin) pinToBigCores(); else LOGI("emu thread not pinned (N64_PIN=0)");
    setEmuThreadPriority();
    // DEV (N64_GPU_TURBO=2): Adreno GPU clock pinned by a KGSL power constraint held by a keep-alive context
    // (DroidDeck's way, gpu_clock_pin.cpp); kept for the Portal slow-mode check (GPU stuck at 220 MHz). Mode 1
    // (adrenotools max clocks) was removed 2026-11-05 (Joey): no fps gain on the Odin 3 or Duo, more heat.
    const char* turboEnv = getenv("N64_GPU_TURBO");
    GpuClockPin clockPin;
    if (turboEnv && *turboEnv == '2') LOGI("GPU clock pin %s", clockPin.start() ? "on" : "failed");
    // DEV (N64_GPU_COUNTERS=1): Adreno GPU clock and busy % every 5 s, read through a KGSL context of our own
    GpuCounters gpuCounters;
    // Dev (item 31 heat runs): N64_FF_START=1 starts in fast-forward, as if R3 had been pressed
    if (getenv("N64_FF_START") && *getenv("N64_FF_START") == '1') g_fastForward = true;
    if (getenv("N64_GPU_COUNTERS") && *getenv("N64_GPU_COUNTERS") == '1') LOGI("GPU counters %s", gpuCounters.start() ? "on" : "failed");
    // EXPERIMENT: ADPF performance hint session for this thread (Android 13+)
    const char* hintEnv = getenv("N64_PERF_HINT");
    // N64_PERF_HINT=2 also adds the core's worker threads at the first thread split (WatermelonDS), and
    // N64_PERF_HINT_FF_US=<us> sets the target while uncapped (bench/fast-forward; default 1000 us, 0 = keep 8333).
    const int hintMode = hintEnv ? atoi(hintEnv) : 0;
    const bool useHint = hintMode >= 1;
    const char* hintFfEnv = getenv("N64_PERF_HINT_FF_US");
    const int64_t hintFfNs = (hintFfEnv && *hintFfEnv ? atoll(hintFfEnv) : 1000) * 1000;
    bool hintThreadsAdded = false;
    PerfHint perfHint;
    // EXPERIMENT (rewind_test.h): N64_REWIND_TEST=<frames between snapshots>.
    const int rewindEvery = getenv("N64_REWIND_TEST") ? atoi(getenv("N64_REWIND_TEST")) : 0;
    RewindTest rewindTest;
    const char* noSplit = getenv("N64_PIN_SPLIT");
    const bool split = pin && !(noSplit && *noSplit == '0');
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
    auto ffNext = std::chrono::steady_clock::now(), ffLastShown = ffNext, capNext = ffNext, rewindNext = ffNext;
    int rewindTick = 0;
    bool rewindStepped = false;
    if (!g_vulkan) g_egl.makeCurrent();
    while (g_running) {
        if (g_paused) flushSave();
        {
            std::unique_lock<std::mutex> lk(g_pauseMutex);
            for (;;) {
                // Wakes once a second while paused so rc_client can keep the session alive (pings, retries).
                if (!g_pauseCv.wait_for(lk, std::chrono::seconds(1), [] { return !g_paused || !g_running || g_statePending || g_stepFrames > 0; })) {
                    lk.unlock(); Cheevos::idle(); lk.lock();
                    continue;
                }
                if (!g_paused || !g_running) break;
                // Frame advance: one frame while staying paused (its sound is dropped, see audio_batch).
                if (g_stepFrames > 0) { --g_stepFrames; break; }
                // Paused with a state request (the pause menu): served without running a frame.
                lk.unlock();
                serveStateRequest(g_framesRun >= (uint32_t)stateReadyFrames(), true);
                lk.lock();
            }
        }
        if (!g_running) break;
        serveStateRequest(g_framesRun >= (uint32_t)stateReadyFrames(), false);
        if (g_portsChanged.exchange(false)) applyPorts(); // a controller given to a player mid-game
        if (g_cheatsChanged.exchange(false)) applyCheats(); // the Cheats page, from the pause menu
        if (g_liveOptionsChanged.exchange(false)) applyLiveOptions(); // Game settings, from the pause menu
        applySurfaceIfChanged();
        bool hasWindow = g_vulkan ? g_vk.hasWindow() : g_egl.hasWindow();
        if (!hasWindow) { std::this_thread::sleep_for(std::chrono::milliseconds(16)); continue; }
        if (g_bench && !benchApplied) {
            benchApplied = true;
            if (g_vulkan) g_vk.setUnthrottled(true);
            else g_egl.setSwapInterval(0);
            LOGI("benchmark mode: uncapped, vsync off, audio dropped");
        }
        // Rewind (rewind.h): settings from the UI, and the hotkey held. Going back runs at about normal speed:
        // one moment every [every] frames, each shown by running one frame from it, the picture held between.
        if (g_rewindCfgChanged.exchange(false)) g_rewind.configure(g_rewindOnCfg, g_rewindEveryCfg, g_rewindMaxCfg);
        const bool rewinding = g_rewindHeld && g_rewind.on() && !g_paused && !g_bench;
        if (rewinding != g_rewindActive) {
            g_rewindActive = rewinding;
            if (rewinding) { rewindTick = g_rewind.every(); rewindStepped = false; g_rewindBackFrames = 0; g_rewindAtOldest = false; rewindNext = std::chrono::steady_clock::now(); }
            else if (rewindStepped) {
                // Achievements carry on from the progress kept with the moment rewind stopped at.
                const auto& p = g_rewind.baseProgress();
                Cheevos::stateLoaded(p.data(), p.size());
                LOGI("rewind: went back %d frames", g_rewindBackFrames.load());
            }
        }
        if (rewinding) {
            rewindNext += std::chrono::microseconds((long long)(1e6 / g_av.timing.fps));
            auto now = std::chrono::steady_clock::now();
            if (rewindNext > now) std::this_thread::sleep_until(rewindNext);
            else if (now - rewindNext > std::chrono::milliseconds(100)) rewindNext = now;
            bool step = ++rewindTick >= g_rewind.every();
            if (step) {
                rewindTick = 0;
                step = g_rewind.stepBack([](const uint8_t* d, size_t n, const uint8_t*, size_t) { core.unserialize(d, n); });
                if (step) { rewindStepped = true; g_rewindBackFrames += g_rewind.every(); g_rewindAtOldest = false; }
                else g_rewindAtOldest = true;
            }
            if (!step) continue; // the picture holds until the next moment
        }
        // Slow motion: the speed cap below runs at its speed, with no audio wait (its sound is resampled).
        const int slowPct = g_slowPct.load();
        // pacing comes from vsync + blocking audio writes (M4 refines); fast-forward swaps
        // both for a sleep that caps the speed
        bool ff = g_fastForward && !rewinding;
        const bool slow = slowPct > 0 && !ff && !rewinding && !g_bench;
        g_slowNow = slow ? slowPct / 100.0 : 0.0;
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
            const double speed = std::min(std::max(g_ffSpeedPct.load(), 100) / 100.0, (double)Thermal::fastForwardSpeed());
            g_ffSpeedNow = speed;
            ffNext += std::chrono::microseconds((long long)(1e6 / (g_av.timing.fps * speed)));
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
        if (!ff && !g_bench && !g_paused && !rewinding) { // paused: a frame advance step, no audio to wait for
            auto t0 = std::chrono::steady_clock::now();
            g_audio.setPanelRefreshNs(g_vulkan ? g_vk.refreshNs() : 0);
            if (!slow) g_audio.waitForRoom();
            double aw = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            g_statAudioWaitMs += aw;
            traceFrame(aw);
            // Speed cap. Audio sync and vsync normally pace, but a game that stops producing audio
            // (New Tetris) ran at the panel rate on 120 Hz: 2x. 2% slack keeps it out of the way
            // of audio pacing and rate control.
            auto now = std::chrono::steady_clock::now();
            // Dev: N64_SLOW_PCT=<n> caps the game at n% of full speed, as a device that can't keep up (time stretch test).
            static const double capSpeed = getenv("N64_SLOW_PCT") ? std::clamp(atoi(getenv("N64_SLOW_PCT")), 10, 100) / 100.0 : 1.02;
            capNext += std::chrono::microseconds((long long)(1e6 / (g_av.timing.fps * (slow ? slowPct / 100.0 : capSpeed))));
            if (capNext > now) std::this_thread::sleep_until(capNext);
            else if (now - capNext > std::chrono::milliseconds(50)) capNext = now; // behind: don't sprint
        }
        if (g_vulkan) {
            auto t0 = std::chrono::steady_clock::now();
            if (!g_vk.beginFrame(show)) continue;   // swapchain recreated, try again
            auto t1 = std::chrono::steady_clock::now();
            core.run();
            auto t3 = std::chrono::steady_clock::now();
            g_vk.endFrame();
            g_statWaitMs += std::chrono::duration<double, std::milli>(t1 - t0).count();
            auto t2 = std::chrono::steady_clock::now();
            g_statRunMs += std::chrono::duration<double, std::milli>(t2 - t1).count();
            double frameMs = std::chrono::duration<double, std::milli>(t2 - t0).count();
            noteFrameTime(frameMs);
            // Dev: N64_HITCH_LOG=1 logs every frame over 33 ms split into acquire (beginFrame), emulation
            // (retro_run, which includes parallel-RDP's scanout) and present (endFrame), to find what a hitch is.
            static const bool hitchLog = getenv("N64_HITCH_LOG") && *getenv("N64_HITCH_LOG") == '1';
            if (hitchLog && frameMs > 33.0)
                LOGI("hitch: %.1f ms frame: acquire %.1f, emulation %.1f, present %.1f ms (frame %u)", frameMs,
                     std::chrono::duration<double, std::milli>(t1 - t0).count(),
                     std::chrono::duration<double, std::milli>(t3 - t1).count(),
                     std::chrono::duration<double, std::milli>(t2 - t3).count(), g_framesRun.load());
        } else {
            auto t0 = std::chrono::steady_clock::now();
            glBindFramebuffer(GL_FRAMEBUFFER, g_egl.fbo());
            core.run(); // includes the swap, so the vsync wait lands here
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            g_statRunMs += ms;
            noteFrameTime(ms);
        }
        if (!rewinding) {
            Cheevos::doFrame();
            if (g_framesRun > 60)
                g_rewind.record([] { return core.serialize_size(); }, [](void* d, size_t n) { return core.serialize(d, n); },
                                [](std::vector<uint8_t>& p) { Cheevos::captureProgress(p); });
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
        if (rewindEvery > 0 && g_framesRun > 60)
            rewindTest.frame(rewindEvery, [] { return core.serialize_size(); }, [](void* d, size_t n) { return core.serialize(d, n); });
        if (useHint) {
            perfHint.setTargetNs((ff || g_bench) && hintFfNs > 0 ? hintFfNs : 1000000000LL / 60 / 2);
            perfHint.reportFrame();
        }
        if (split && --splitIn == 0) {
            splitBigCores(); splitIn = 600;
            if (hintMode == 2 && !hintThreadsAdded) { perfHint.addThreads(coreWorkerThreads()); hintThreadsAdded = true; }
        }
    }
    serveStateRequest(false, true);   // a request that came too late fails now, not at its timeout
    flushSave();
    Cheevos::gameUnloaded();
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

// For the session log: what was loaded, so a tester's log names the exact dump (header name, CRCs, size, byte order).
std::string romSummary(const std::vector<uint8_t>& rom) {
    if (rom.size() < 0x40) return "too small for an N64 ROM";
    auto at = [&](size_t i) -> uint8_t {
        switch (rom[0]) {
            case 0x37: return rom[i ^ 1];
            case 0x40: return rom[i ^ 3];
            default:   return rom[i];
        }
    };
    auto be32 = [&](size_t i) { return (uint32_t)at(i) << 24 | (uint32_t)at(i + 1) << 16 | (uint32_t)at(i + 2) << 8 | at(i + 3); };
    std::string name;
    for (size_t i = 0x20; i < 0x34; ++i) { uint8_t c = at(i); name += (c >= 0x20 && c < 0x7F) ? (char)c : '?'; }
    while (!name.empty() && name.back() == ' ') name.pop_back();
    char out[200];
    snprintf(out, sizeof out, "header \"%s\", id %s, version %u, CRC %08X %08X, %zu bytes, %s", name.c_str(),
             romGameId(rom).c_str(), at(0x3F), be32(0x10), be32(0x14), rom.size(),
             rom[0] == 0x37 ? "v64 (byte-swapped)" : rom[0] == 0x40 ? "n64 (little-endian)" : rom[0] == 0x80 ? "z64" : "unknown byte order");
    return out;
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
        && sym(core.unserialize, "retro_unserialize")
        && sym(core.serialize_size, "retro_serialize_size")
        && sym(core.serialize, "retro_serialize")
        && sym(core.reset, "retro_reset");
    if (!ok) return JNI_FALSE;
    core.load_game_special = reinterpret_cast<decltype(core.load_game_special)>(dlsym(core.handle, "retro_load_game_special"));

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

// Settings › Video (display_opts.h): the picture's shape (0:0 = stretch), integer scaling, black frames.
JNIEXPORT void JNICALL JNI(setDisplay)(JNIEnv*, jobject, jint aspW, jint aspH, jboolean integer, jboolean bfi) {
    auto& o = Display::opts();
    o.aspW = aspW; o.aspH = aspH; o.integer = integer; o.bfi = bfi;
}

// Settings › Video › Colour: percent (-50..50) and gamma in tenths; Sharpening: 0 off, 1 CAS, 2 FSR.
JNIEXPORT void JNICALL JNI(setColour)(JNIEnv*, jobject, jint brightness, jint contrast, jint saturation, jint gamma) {
    auto& o = Display::opts();
    o.brightness = brightness; o.contrast = contrast; o.saturation = saturation; o.gamma = gamma;
}

JNIEXPORT void JNICALL JNI(setSharpen)(JNIEnv*, jobject, jint mode, jint strength) {
    auto& o = Display::opts();
    o.sharpen = mode; o.strength = strength;
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

JNIEXPORT void JNICALL JNI(setDdCart)(JNIEnv* env, jobject, jstring path) {
    const char* s = env->GetStringUTFChars(path, nullptr);
    g_ddCart = s;
    env->ReleaseStringUTFChars(path, s);
}

JNIEXPORT jboolean JNICALL JNI(loadGame)(JNIEnv* env, jobject, jstring rom, jstring saveName) {
    g_loadStart = std::chrono::steady_clock::now();
    g_coreShutDown = false;
    g_ddIplMissing = false;
    if (!g_vulkan) {
        if (!g_egl.init()) return JNI_FALSE;
        applySurfaceIfChanged();
    }

    const char* p = env->GetStringUTFChars(rom, nullptr);
    // A 64DD disk: the core reads the disk (and the cartridge, if any) itself; buf holds the cartridge for the
    // per-game rules below, empty for a disk on its own.
    const bool disk = isDiskPath(p);
    FILE* f = fopen(disk ? g_ddCart.c_str() : p, "rb");
    std::vector<uint8_t> buf;
    if (f) {
        // A failed ftell (-1) became a huge resize, and a short read left zeros counted as ROM.
        long n = fseek(f, 0, SEEK_END) == 0 ? ftell(f) : -1;
        if (n > 0 && fseek(f, 0, SEEK_SET) == 0) { buf.resize((size_t)n); buf.resize(fread(buf.data(), 1, buf.size(), f)); }
        if (buf.empty()) LOGE("can't read the ROM %s", p);
        fclose(f);
    }
    // Sync by default on Adreno (MainActivity). Elsewhere, games that break with async parallel-RDP (their
    // CPU reads the framebuffer, which async can serve stale): Pokemon Snap
    // (photo checks), The New Tetris (async: halts on a "secret cheat, power off" screen after ~10 s),
    // Resident Evil 2 and Cruis'n USA (async: black screen), Yoshi's Story and Spider-Man (async: the intro
    // draws, then every frame renders black from ~25 s; on the Flip's Mali since 2026-11-05, flip-blk-*, and
    // Yoshi's Story on Adreno in fast-forward). An explicit option override (--es opts) wins.
    static const char* const kSyncRdpGames[] = {"NPF", "NRI", "NRE", "NCU", "NYS", "NSL"};
    std::string id = romGameId(buf);
    auto idIn = [&](auto& list) { return std::any_of(std::begin(list), std::end(list), [&](const char* g) { return id.compare(0, 3, g) == 0; }); };
    g_ffKeepSync = idIn(kSyncRdpGames);
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
    // Smoother timing: CountPerOp=1 (Project64's counter factor 1) for games that ran below their frame rate on
    // hardware, where MainActivity sets N64_SMOOTHER=1 (strong devices). Odin 3 (odin3-smooth): GoldenEye 49.7 -> 59.9,
    // Conker 21.4 -> 28.6, Mario Kart 64 26.5 -> 28.1, Yoshi's Story 28.6 -> 30.0 fps. --es opts wins.
    static const char* const kSmootherGames[] = {"NGE", "NFU", "NKT", "NYS"};
    const char* smoother = getenv("N64_SMOOTHER");
    if (smoother && *smoother == '1' && idIn(kSmootherGames) && !g_optionOverrides.count("mupen64plus-CountPerOp"))
        g_optionOverrides["mupen64plus-CountPerOp"] = "1";
    g_sessInterpreter = g_optionOverrides["mupen64plus-cpucore"] == "dynamic_recompiler" ? 0 : 1;
    LOGI("game id %s, parallel-RDP %s, %s", id.c_str(), g_rdpSync ? "synchronous" : "async",
         g_optionOverrides["mupen64plus-cpucore"].c_str());
    LOGI("ROM %s: %s", p, romSummary(buf).c_str());
    for (auto& [k, v] : g_optionOverrides) LOGI("option in effect: %s = %s", k.c_str(), v.c_str());
    retro_game_info info{ p, buf.data(), buf.size(), nullptr };
    bool ok;
    if (disk) {
        // The core looks for the IPL BIOS (the 64DD's boot ROM) in <system>/Mupen64plus: the disk's region's own
        // (IPL_JPN.n64, IPL_USA.n64, IPL_DEV.n64), else IPL.n64.
        std::string dir = g_systemDir + "/Mupen64plus/";
        bool ipl = false;
        for (const char* n : {"IPL_JPN.n64", "IPL_USA.n64", "IPL_DEV.n64", "IPL.n64"}) ipl = ipl || access((dir + n).c_str(), R_OK) == 0;
        retro_game_info dd[2] = { { p, nullptr, 0, nullptr }, { g_ddCart.c_str(), nullptr, 0, nullptr } };
        LOGI("64DD disk %s, %s", p, g_ddCart.empty() ? "no cartridge" : ("cartridge " + g_ddCart).c_str());
        if (!ipl) { LOGE("64DD: no IPL BIOS: the data folder's bios/ has none of IPL_JPN.n64, IPL_USA.n64, IPL_DEV.n64, IPL.n64 (copied to %s)", dir.c_str()); ok = false; }
        else if (!core.load_game_special) { LOGE("64DD: the core has no retro_load_game_special"); ok = false; }
        else if (!g_ddCart.empty() && buf.empty()) ok = false; // can't read the cartridge (logged above)
        else ok = core.load_game_special(1 /* RETRO_GAME_TYPE_DD */, dd, g_ddCart.empty() ? 1 : 2);
    } else {
        ok = !buf.empty() && core.load_game(&info);
    }
    env->ReleaseStringUTFChars(rom, p);
    if (!ok) { LOGE("retro_load_game failed"); return JNI_FALSE; }

    g_portsChanged = false;
    applyPorts();
    g_cheatsChanged = false;
    applyCheats();

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
    Cheevos::gameLoaded(buf.data(), buf.size(), static_cast<uint8_t*>(core.get_memory_data(RETRO_MEMORY_SYSTEM_RAM)),
                        core.get_memory_size(RETRO_MEMORY_SYSTEM_RAM));
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

// Frame advance: while paused, the game runs one more frame and stays paused. Does nothing if not paused.
JNIEXPORT void JNICALL JNI(stepFrame)(JNIEnv*, jobject) {
    if (!g_paused) return;
    { std::lock_guard<std::mutex> pl(g_pauseMutex); if (g_stepFrames < 4) ++g_stepFrames; }
    g_pauseCv.notify_all();
}

JNIEXPORT void JNICALL JNI(pause)(JNIEnv*, jobject, jboolean p) {
    if (!p) g_stepFrames = 0;
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
    {
        std::lock_guard<std::mutex> lk(g_cheatsMutex);
        g_cheats.emplace_back(c);
    }
    env->ReleaseStringUTFChars(code, c);
}

// Core options for a running game (keys[i] = values[i]): taken between frames, a paused game's on resume.
JNIEXPORT void JNICALL JNI(setOptionsLive)(JNIEnv* env, jobject, jobjectArray keys, jobjectArray values) {
    std::vector<std::pair<std::string, std::string>> next;
    jsize n = std::min(env->GetArrayLength(keys), env->GetArrayLength(values));
    for (jsize i = 0; i < n; ++i) {
        auto ks = (jstring)env->GetObjectArrayElement(keys, i);
        auto vs = (jstring)env->GetObjectArrayElement(values, i);
        const char* k = env->GetStringUTFChars(ks, nullptr);
        const char* v = env->GetStringUTFChars(vs, nullptr);
        next.emplace_back(k, v);
        env->ReleaseStringUTFChars(ks, k);
        env->ReleaseStringUTFChars(vs, v);
        env->DeleteLocalRef(ks);
        env->DeleteLocalRef(vs);
    }
    {
        std::lock_guard<std::mutex> lk(g_liveOptionsMutex);
        for (auto& kv : next) g_liveOptions.push_back(std::move(kv));
    }
    g_liveOptionsChanged = true;
}

// Replaces every cheat; a running game takes them between frames (a paused one when it resumes).
JNIEXPORT void JNICALL JNI(setCheats)(JNIEnv* env, jobject, jobjectArray codes) {
    std::vector<std::string> next;
    jsize n = env->GetArrayLength(codes);
    for (jsize i = 0; i < n; ++i) {
        auto s = (jstring)env->GetObjectArrayElement(codes, i);
        const char* c = env->GetStringUTFChars(s, nullptr);
        next.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    {
        std::lock_guard<std::mutex> lk(g_cheatsMutex);
        g_cheats.swap(next);
    }
    g_cheatsChanged = true;
}

// The GameShark button down or up; the game takes it between frames.
JNIEXPORT void JNICALL JNI(setGameShark)(JNIEnv*, jobject, jboolean down) {
    if (g_gsButton.exchange(down) == (bool)down) return;
    LOGI("GameShark button %s", down ? "down" : "up");
    g_cheatsChanged = true;
}

JNIEXPORT void JNICALL JNI(dumpRam)(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    { std::lock_guard<std::mutex> lk(g_dumpMutex); g_dumpPath = p; }
    env->ReleaseStringUTFChars(path, p);
}

JNIEXPORT void JNICALL JNI(setFastForward)(JNIEnv*, jobject, jboolean on) {
    g_fastForward = on;
}

JNIEXPORT void JNICALL JNI(setFastForwardSpeed)(JNIEnv*, jobject, jint percent) {
    g_ffSpeedPct = std::clamp((int)percent, 100, 400);
}

JNIEXPORT void JNICALL JNI(setSlowMotion)(JNIEnv*, jobject, jint percent) {
    g_slowPct = percent <= 0 ? 0 : std::clamp((int)percent, 10, 99);
}

JNIEXPORT void JNICALL JNI(setRewind)(JNIEnv*, jobject, jboolean on, jint every, jint maxMoments) {
    g_rewindOnCfg = on; g_rewindEveryCfg = std::max(1, (int)every); g_rewindMaxCfg = std::max(1, (int)maxMoments);
    g_rewindCfgChanged = true;
}

JNIEXPORT void JNICALL JNI(setRewinding)(JNIEnv*, jobject, jboolean held) {
    g_rewindHeld = held;
}

// [moments that can still be gone back to, most kept, frames gone back since the press, 1 at the oldest].
JNIEXPORT jintArray JNICALL JNI(rewindInfo)(JNIEnv* env, jobject) {
    jint v[4] = {g_rewind.moments(), g_rewind.maxMoments(), g_rewindBackFrames.load(), g_rewindAtOldest ? 1 : 0};
    jintArray a = env->NewIntArray(4);
    env->SetIntArrayRegion(a, 0, 4, v);
    return a;
}

// Settings › Audio. Volume, mute, mono and fast-forward sound apply at once; latency when the stream
// next opens (call before loadGame).
JNIEXPORT void JNICALL JNI(setAudio)(JNIEnv*, jobject, jint volume, jboolean mute, jboolean mono, jint ffSound, jint latency) {
    g_audio.setMix((int)volume, mute, mono);
    g_audio.setLatencyMode(std::clamp((int)latency, 0, 2));
    g_ffSound = std::clamp((int)ffSound, 0, 2);
}

JNIEXPORT jint JNICALL JNI(rumble)(JNIEnv*, jobject) {
    return (jint)rumbleMask();
}

JNIEXPORT jint JNICALL JNI(waitRumble)(JNIEnv*, jobject, jint wasMask, jint timeoutMs) {
    std::unique_lock<std::mutex> lock(g_rumbleMutex);
    g_rumbleChanged.wait_for(lock, std::chrono::milliseconds(timeoutMs), [&] { return rumbleMask() != (int)wasMask; });
    return (jint)rumbleMask();
}

// 0 running, 1 the core shut down, 2 it shut down after it could not load the 64DD IPL.
JNIEXPORT jint JNICALL JNI(coreShutDown)(JNIEnv*, jobject) { return !g_coreShutDown ? 0 : g_ddIplMissing ? 2 : 1; }

JNIEXPORT jint JNICALL JNI(framesRun)(JNIEnv*, jobject) {
    return (jint)g_framesRun.load();
}
JNIEXPORT jint JNICALL JNI(framesDrawn)(JNIEnv*, jobject) {
    return (jint)g_framesDrawn.load();
}
// Send reports: the run's numbers so far into out (see NativeBridge.sessionStats for the order); returns how many.
JNIEXPORT jint JNICALL JNI(sessionStats)(JNIEnv* env, jobject, jdoubleArray out) {
    uint32_t hist[kSessBuckets]; uint64_t total = 0;
    for (int i = 0; i < kSessBuckets; ++i) { hist[i] = g_sessFrameHist[i].load(std::memory_order_relaxed); total += hist[i]; }
    auto pct = [&](double q) -> double {
        if (!total) return -1;
        uint64_t want = (uint64_t)std::ceil(total * q), seen = 0;
        for (int i = 0; i < kSessBuckets; ++i) { seen += hist[i]; if (seen >= want) return i + 1; }
        return kSessBuckets;
    };
    int lw = g_sessLatWindows.load();
    double v[] = {
        (double)g_sessUnderruns.load(), lw ? g_sessLatSum.load() / lw : -1, lw ? g_sessLatMax.load() : -1,
        (double)g_sessThermalMax.load(), (double)g_sessHeadroomMax.load(), (double)g_sessGovernorDrops.load(),
        g_rdpSync ? 1.0 : 0.0, (double)g_sessInterpreter.load(),
        g_firstFrameMs.load(), (double)total, pct(0.95), pct(0.99), (double)g_sessSlowFrames.load(),
    };
    jsize n = std::min((jsize)(sizeof v / sizeof v[0]), env->GetArrayLength(out));
    env->SetDoubleArrayRegion(out, 0, n, v);
    return n;
}
JNIEXPORT jdouble JNICALL JNI(contentFps)(JNIEnv*, jobject) {
    return g_av.timing.fps;
}
// The newest frame times (ms), oldest first, into out; returns how many were written.
JNIEXPORT jint JNICALL JNI(frameTimes)(JNIEnv* env, jobject, jfloatArray out) {
    uint32_t n = g_frameRingCount.load(std::memory_order_acquire);
    uint32_t want = std::min<uint32_t>({n, kFrameRing, (uint32_t)env->GetArrayLength(out)});
    float tmp[kFrameRing];
    for (uint32_t i = 0; i < want; ++i) tmp[i] = g_frameRing[(n - want + i) % kFrameRing];
    env->SetFloatArrayRegion(out, 0, (jsize)want, tmp);
    return (jint)want;
}

// The session log file (LogFiles.kt), and lines from the Kotlin side so both halves land in one file in order
JNIEXPORT void JNICALL JNI(openLog)(JNIEnv* env, jobject, jstring path) {
    if (!path) { joeyLogOpen(nullptr); return; }
    const char* p = env->GetStringUTFChars(path, nullptr);
    joeyLogOpen(p);
    env->ReleaseStringUTFChars(path, p);
}

JNIEXPORT void JNICALL JNI(log)(JNIEnv* env, jobject, jint prio, jstring tag, jstring msg) {
    const char* t = env->GetStringUTFChars(tag, nullptr);
    const char* m = env->GetStringUTFChars(msg, nullptr);
    joeyLog(prio, t, "%s", m);
    env->ReleaseStringUTFChars(msg, m);
    env->ReleaseStringUTFChars(tag, t);
}

JNIEXPORT void JNICALL JNI(stop)(JNIEnv*, jobject) {
    if (!g_running) return;
    g_running = false;
    g_pauseCv.notify_all();
    if (g_thread.joinable()) g_thread.join();
    { std::lock_guard<std::mutex> lk(g_stateMutex); }
    g_stateCv.notify_all();
}

// The achievements' progress taken with this thread's last saveState (stateProgress hands it over).
thread_local std::vector<uint8_t> t_stateProgress;

// The core's state between frames (paused or not), or null if it couldn't be taken in [timeoutMs].
JNIEXPORT jbyteArray JNICALL JNI(saveState)(JNIEnv* env, jobject, jint timeoutMs) {
    StateRequest req;
    req.save = true;
    t_stateProgress.clear();
    if (!runStateRequest(req, timeoutMs) || !req.ok) return nullptr;
    t_stateProgress.swap(req.achv);
    jbyteArray out = env->NewByteArray((jsize)req.data.size());
    if (out) env->SetByteArrayRegion(out, 0, (jsize)req.data.size(), reinterpret_cast<const jbyte*>(req.data.data()));
    return out;
}

// The last frame the core drew, between frames (paused or not): 8 bytes of width and height (little-endian
// u32 each), then top-down RGBA8 rows. Null if there's none or it took over [timeoutMs].
JNIEXPORT jbyteArray JNICALL JNI(captureFrame)(JNIEnv* env, jobject, jint timeoutMs) {
    StateRequest req;
    req.capture = true;
    if (!runStateRequest(req, timeoutMs) || !req.ok || req.data.size() != (size_t)req.w * req.h * 4) return nullptr;
    jbyteArray out = env->NewByteArray((jsize)(req.data.size() + 8));
    if (!out) return nullptr;
    uint8_t head[8];
    for (int i = 0; i < 4; ++i) { head[i] = (uint8_t)(req.w >> (8 * i)); head[4 + i] = (uint8_t)(req.h >> (8 * i)); }
    env->SetByteArrayRegion(out, 0, 8, reinterpret_cast<const jbyte*>(head));
    env->SetByteArrayRegion(out, 8, (jsize)req.data.size(), reinterpret_cast<const jbyte*>(req.data.data()));
    return out;
}

// The pause menu's Reset (the console's reset button), between frames; also while paused. False if
// it wasn't served in [timeoutMs].
JNIEXPORT jboolean JNICALL JNI(resetGame)(JNIEnv*, jobject, jint timeoutMs) {
    StateRequest req;
    req.reset = true;
    return runStateRequest(req, timeoutMs) && req.ok ? JNI_TRUE : JNI_FALSE;
}

// Puts [data] (the core's state, unwrapped) back between frames. Waits for the core to be ready, so
// it can be called right after start(); false if it failed or wasn't served in [timeoutMs].
JNIEXPORT jbyteArray JNICALL JNI(stateProgress)(JNIEnv* env, jobject) {
    if (t_stateProgress.empty()) return nullptr;
    jbyteArray out = env->NewByteArray((jsize)t_stateProgress.size());
    if (out) env->SetByteArrayRegion(out, 0, (jsize)t_stateProgress.size(), reinterpret_cast<const jbyte*>(t_stateProgress.data()));
    t_stateProgress.clear();
    return out;
}

JNIEXPORT jboolean JNICALL JNI(loadState)(JNIEnv* env, jobject, jbyteArray data, jbyteArray achv, jint timeoutMs) {
    StateRequest req;
    req.save = false;
    jsize n = env->GetArrayLength(data);
    req.data.resize((size_t)n);
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(req.data.data()));
    if (achv) {
        jsize a = env->GetArrayLength(achv);
        req.achv.resize((size_t)a);
        env->GetByteArrayRegion(achv, 0, a, reinterpret_cast<jbyte*>(req.achv.data()));
    }
    return runStateRequest(req, timeoutMs) && req.ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI(setButtons)(JNIEnv*, jobject, jint port, jint mask) {
    if (port < 0 || port >= kPorts) return;
    g_tapped[port] |= mask & ~g_buttons[port].exchange(mask);
}

JNIEXPORT void JNICALL JNI(setAnalog)(JNIEnv*, jobject, jint port, jint i, jint x, jint y) {
    if (port < 0 || port >= kPorts || i < 0 || i > 1) return;
    g_analog[port][i][0] = x; g_analog[port][i][1] = y;
}

// Which ports have a controller (bit per port); takes effect at the next loadGame.
JNIEXPORT void JNICALL JNI(setPortsPresent)(JNIEnv*, jobject, jint mask) {
    g_portsPresent = (mask & ((1 << kPorts) - 1)) | 1; // player 1 is always plugged in
    g_portsChanged = true; // applied between frames if a game is running; loadGame applies it anyway
}

} // extern "C"
