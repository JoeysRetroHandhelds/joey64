// Minimal libretro frontend: dlopen the core, answer environment calls,
// provide a GLES3 HW render context, and run retro_run on a dedicated thread.
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstdarg>
#include <GLES3/gl3.h>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "libretro.h"   // from libretro-common, see README
#include "egl_context.h"
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
} core;

std::string g_systemDir, g_saveDir;
retro_hw_render_callback g_hw{};
bool g_hwRequested = false;
retro_system_av_info g_av{};

// Core options. Defaults from the core, overrides here.
// M3's profile DB will fill g_optionOverrides per ROM hash + GPU.
std::unordered_map<std::string, std::string> g_options;
std::unordered_map<std::string, std::string> g_optionOverrides = {
    {"mupen64plus-rdp-plugin", "gliden64"},   // M2 switches to "parallel" when Vulkan lands
    {"mupen64plus-rsp-plugin", "hle"},
    {"mupen64plus-cpucore", "dynamic_recompiler"},
};
std::atomic<bool> g_optionsDirty{true};

EglContext g_egl;
AudioOut g_audio;
std::mutex g_surfaceMutex;
ANativeWindow* g_pendingWindow = nullptr;
std::atomic<bool> g_surfaceChanged{false};

std::thread g_thread;
std::atomic<bool> g_running{false}, g_paused{false};
std::mutex g_pauseMutex;
std::condition_variable g_pauseCv;

std::atomic<int> g_buttons{0};
std::atomic<int> g_analog[2][2] = {};
unsigned g_lastW = 0, g_lastH = 0;
int g_statRuns = 0, g_statPresents = 0, g_statDupes = 0; // emulation thread only

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
        *static_cast<unsigned*>(data) = RETRO_HW_CONTEXT_OPENGLES3; return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        auto* hw = static_cast<retro_hw_render_callback*>(data);
        if (hw->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
            hw->context_type != RETRO_HW_CONTEXT_OPENGLES2 &&
            hw->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION) {
            LOGI("core requested unsupported HW context %d", hw->context_type);
            return false; // Vulkan → M2
        }
        hw->get_current_framebuffer = hw_get_current_framebuffer;
        hw->get_proc_address = hw_get_proc_address;
        g_hw = *hw; g_hwRequested = true;
        return true;
    }
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
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        return true;
    default:
        return false;
    }
}

void video_refresh(const void* data, unsigned w, unsigned h, size_t) {
    if (!data) { ++g_statDupes; return; }     // dupe frame: keep previous
    g_lastW = w; g_lastH = h;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        g_egl.present(w, h, g_hw.bottom_left_origin);
        ++g_statPresents;
    }
    // Software frames are not expected from mupen64plus-next with a HW context.
}

void audio_sample(int16_t l, int16_t r) { int16_t s[2] = {l, r}; g_audio.write(s, 1); }
size_t audio_batch(const int16_t* d, size_t frames) { g_audio.write(d, frames); return frames; }
void input_poll() {}

int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
        int mask = g_buttons.load();
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
    g_egl.attachWindow(g_pendingWindow);
}

// Logs emulated frames/s (retro_run calls), presented and duped frames every 5 s.
void logStats() {
    using clock = std::chrono::steady_clock;
    static clock::time_point start = clock::now();
    ++g_statRuns;
    double secs = std::chrono::duration<double>(clock::now() - start).count();
    if (secs < 5.0) return;
    LOGI("stats: %.1f emu fps, %.1f presented, %.1f dupes (%ux%u)", g_statRuns / secs,
         g_statPresents / secs, g_statDupes / secs, g_lastW, g_lastH);
    g_statRuns = g_statPresents = g_statDupes = 0;
    start = clock::now();
}

void runLoop() {
    g_egl.makeCurrent();
    while (g_running) {
        {
            std::unique_lock<std::mutex> lk(g_pauseMutex);
            g_pauseCv.wait(lk, [] { return !g_paused || !g_running; });
        }
        if (!g_running) break;
        applySurfaceIfChanged();
        if (!g_egl.hasWindow()) { std::this_thread::sleep_for(std::chrono::milliseconds(16)); continue; }
        glBindFramebuffer(GL_FRAMEBUFFER, g_egl.fbo());
        core.run();   // pacing comes from vsync + blocking audio writes (M4 refines)
        logStats();
    }
    if (g_hw.context_destroy) g_hw.context_destroy();
    core.unload_game();
    core.deinit();
    g_audio.close();
    g_egl.destroy();
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
        && sym(core.run, "retro_run");
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
JNIEXPORT jboolean JNICALL JNI(loadGame)(JNIEnv* env, jobject, jstring rom) {
    if (!g_egl.init()) return JNI_FALSE;
    applySurfaceIfChanged();

    const char* p = env->GetStringUTFChars(rom, nullptr);
    FILE* f = fopen(p, "rb");
    std::vector<uint8_t> buf;
    if (f) { fseek(f, 0, SEEK_END); buf.resize(ftell(f)); fseek(f, 0, SEEK_SET); fread(buf.data(), 1, buf.size(), f); fclose(f); }
    retro_game_info info{ p, buf.data(), buf.size(), nullptr };
    bool ok = !buf.empty() && core.load_game(&info);
    env->ReleaseStringUTFChars(rom, p);
    if (!ok) { LOGE("retro_load_game failed"); return JNI_FALSE; }

    core.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    core.get_system_av_info(&g_av);
    LOGI("game loaded: %ux%u max, %.2f fps, %.0f Hz", g_av.geometry.max_width, g_av.geometry.max_height,
         g_av.timing.fps, g_av.timing.sample_rate);

    g_egl.ensureFbo(g_av.geometry.max_width, g_av.geometry.max_height);
    if (g_hwRequested && g_hw.context_reset) g_hw.context_reset();
    g_audio.open((int)(g_av.timing.sample_rate + 0.5));

    eglMakeCurrent(eglGetCurrentDisplay(), EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); // release for run thread
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

JNIEXPORT void JNICALL JNI(stop)(JNIEnv*, jobject) {
    if (!g_running) return;
    g_running = false;
    g_pauseCv.notify_all();
    if (g_thread.joinable()) g_thread.join();
}

JNIEXPORT void JNICALL JNI(setButtons)(JNIEnv*, jobject, jint mask) { g_buttons = mask; }

JNIEXPORT void JNICALL JNI(setAnalog)(JNIEnv*, jobject, jint i, jint x, jint y) {
    if (i < 0 || i > 1) return;
    g_analog[i][0] = x; g_analog[i][1] = y;
}

} // extern "C"
