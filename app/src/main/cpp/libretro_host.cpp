// Minimal libretro frontend: dlopen the core, answer environment calls,
// provide a GLES3 or Vulkan HW render context, and run retro_run on a dedicated thread.
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <unistd.h>
#include <sched.h>
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

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "n64host", __VA_ARGS__)
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
    {"mupen64plus-cpucore", "dynamic_recompiler"},
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

std::thread g_thread;
std::atomic<bool> g_running{false}, g_paused{false};
std::mutex g_pauseMutex;
std::condition_variable g_pauseCv;
// Fast-forward: vsync off, audio dropped, emulation capped at kFastForwardSpeed x normal.
std::atomic<bool> g_fastForward{false};
// GameShark codes applied after load_game (M3: per-game compatibility fixes from the profile DB).
std::vector<std::string> g_cheats;
// Debug: RDRAM dump requested from the UI thread, written by the emulation thread between frames.
std::mutex g_dumpMutex;
std::string g_dumpPath;
constexpr int kFastForwardSpeed = 4;

std::atomic<int> g_buttons{0};
std::atomic<int> g_tapped{0}; // presses not yet seen by a poll, so sub-frame taps still register
int g_polledButtons = 0;      // emulation thread only
std::atomic<int> g_analog[2][2] = {};
unsigned g_lastW = 0, g_lastH = 0;
int g_statRuns = 0, g_statPresents = 0, g_statDupes = 0; // emulation thread only
double g_statRunMs = 0, g_statWaitMs = 0;                 // time in retro_run / beginFrame

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
    __android_log_vprint(level >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "core", fmt, ap);
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
        else { g_lastW = w; g_lastH = h; ++g_statPresents; }
        g_vk.videoRefresh(data, w, h);
        return;
    }
    if (!data) { ++g_statDupes; return; }     // dupe frame: keep previous
    g_lastW = w; g_lastH = h;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        g_egl.present(w, h, g_hw.bottom_left_origin);
        ++g_statPresents;
    }
    // Software frames are not expected from mupen64plus-next with a HW context.
}

void audio_sample(int16_t l, int16_t r) {
    if (g_fastForward) return;
    int16_t s[2] = {l, r}; g_audio.write(s, 1);
}
size_t audio_batch(const int16_t* d, size_t frames) {
    if (!g_fastForward) g_audio.write(d, frames); // blocking write would throttle fast-forward
    return frames;
}
// Taps are held for kMinPressPolls polls: some games (Pokemon Snap's tutorial) ignore a press
// that is visible for a single frame.
constexpr int kMinPressPolls = 4;
int g_tapHold[16] = {};
void input_poll() {
    int taps = g_tapped.exchange(0), held = 0;
    for (int i = 0; i < 16; ++i) {
        if (taps & (1 << i)) g_tapHold[i] = kMinPressPolls;
        if (g_tapHold[i] > 0) { --g_tapHold[i]; held |= 1 << i; }
    }
    g_polledButtons = g_buttons.load() | held;
}

int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
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
    if (!g_surfaceChanged.exchange(false)) return;
    std::lock_guard<std::mutex> lk(g_surfaceMutex);
    if (g_vulkan) g_vk.attachWindow(g_pendingWindow);
    else g_egl.attachWindow(g_pendingWindow);
}

// Logs emulated frames/s (retro_run calls), presented and duped frames every 5 s.
void logStats() {
    using clock = std::chrono::steady_clock;
    static clock::time_point start = clock::now();
    ++g_statRuns;
    double secs = std::chrono::duration<double>(clock::now() - start).count();
    if (secs < 5.0) return;
    LOGI("stats: %.1f emu fps, %.1f presented, %.1f dupes (%ux%u), run %.2f ms, wait %.2f ms",
         g_statRuns / secs, g_statPresents / secs, g_statDupes / secs, g_lastW, g_lastH,
         g_statRunMs / g_statRuns, g_statWaitMs / g_statRuns);
    g_statRuns = g_statPresents = g_statDupes = 0;
    g_statRunMs = g_statWaitMs = 0;
    start = clock::now();
}

// ---- save RAM ---------------------------------------------------------------
// The core keeps EEPROM/SRAM/FlashRAM/Controller Pak in RETRO_MEMORY_SAVE_RAM; persisting it is
// the frontend's job. One .srm per ROM (named by content hash), written atomically when it
// changes: checked every ~2 s, on pause (Android may kill us after onPause) and on exit.
std::string g_savePath;
std::vector<uint8_t> g_lastSave;

void loadSave() {
    auto* mem = static_cast<uint8_t*>(core.get_memory_data(RETRO_MEMORY_SAVE_RAM));
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!mem || !size) { LOGI("save: core exposes no save RAM"); return; }
    if (FILE* f = fopen(g_savePath.c_str(), "rb")) {
        size_t n = fread(mem, 1, size, f);
        fclose(f);
        LOGI("save: loaded %zu/%zu bytes from %s", n, size, g_savePath.c_str());
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

void runLoop() {
    pinToBigCores();
    int framesSinceSaveCheck = 0;
    bool ffApplied = false;
    auto ffNext = std::chrono::steady_clock::now(), ffLastShown = ffNext;
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
        // pacing comes from vsync + blocking audio writes (M4 refines); fast-forward swaps
        // both for a sleep that caps the speed
        bool ff = g_fastForward;
        if (ff != ffApplied) {
            ffApplied = ff;
            if (g_vulkan) g_vk.setUnthrottled(ff);
            else g_egl.setSwapInterval(ff ? 0 : 1);
            // Synchronous parallel-RDP makes the CPU wait for the GPU at every RDP sync, which caps
            // fast-forward near 1.7x on the Thor; async reaches 4x. CPU reads of the framebuffer
            // (Pokemon Snap's photos) can be stale while async, so it's on only while fast-forwarding.
            if (g_vulkan) {
                g_optionOverrides["mupen64plus-parallel-rdp-synchronous"] = ff ? "False" : "True";
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
    }
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

#define JNI(name) Java_com_joeysretro_n64_NativeBridge_##name

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
        && sym(core.cheat_set, "retro_cheat_set");
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

// Note: loadGame runs on the UI thread and makes the context current there;
// start() hands the context to the emulation thread.
JNIEXPORT void JNICALL JNI(setVulkanDriver)(JNIEnv* env, jobject, jstring dir, jstring name, jstring hooks, jstring tmp) {
    auto str = [env](jstring s) { const char* c = env->GetStringUTFChars(s, nullptr); std::string r = c; env->ReleaseStringUTFChars(s, c); return r; };
    VkContext::setCustomDriver(str(dir).c_str(), str(name).c_str(), str(hooks).c_str(), str(tmp).c_str());
}

JNIEXPORT void JNICALL JNI(setRenderer)(JNIEnv*, jobject, jboolean vulkan) {
    g_vulkan = vulkan;
    g_optionOverrides["mupen64plus-rdp-plugin"] = vulkan ? "parallel" : "gliden64";
    g_optionOverrides["mupen64plus-rsp-plugin"] = vulkan ? "parallel" : "hle";
    g_optionsDirty = true;
}

JNIEXPORT jboolean JNICALL JNI(loadGame)(JNIEnv* env, jobject, jstring rom) {
    if (!g_vulkan) {
        if (!g_egl.init()) return JNI_FALSE;
        applySurfaceIfChanged();
    }

    const char* p = env->GetStringUTFChars(rom, nullptr);
    FILE* f = fopen(p, "rb");
    std::vector<uint8_t> buf;
    if (f) { fseek(f, 0, SEEK_END); buf.resize(ftell(f)); fseek(f, 0, SEEK_SET); fread(buf.data(), 1, buf.size(), f); fclose(f); }
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

    uint64_t hash = 1469598103934665603ull; // FNV-1a of the ROM: stable save name per game
    for (uint8_t b : buf) hash = (hash ^ b) * 1099511628211ull;
    char name[32];
    snprintf(name, sizeof name, "/%016llx.srm", (unsigned long long)hash);
    g_savePath = g_saveDir + name;
    loadSave();
    core.get_system_av_info(&g_av);
    LOGI("game loaded: %ux%u max, %.2f fps, %.0f Hz", g_av.geometry.max_width, g_av.geometry.max_height,
         g_av.timing.fps, g_av.timing.sample_rate);

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
