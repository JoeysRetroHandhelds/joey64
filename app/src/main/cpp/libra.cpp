#include "libra.h"
#include "joeylog.h"
#include <EGL/egl.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>
#include <android/log.h>

#define LIBRA_RUNTIME_VULKAN
#define LIBRA_RUNTIME_OPENGL
#include "third_party/librashader/include/librashader.h"

#define LOGI(...) joeyLog(ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)
#define LOGE(...) joeyLog(ANDROID_LOG_ERROR, "joey64host", __VA_ARGS__)

namespace {
#define LIBRA_FUNCS(X) X(libra_preset_create) X(libra_preset_free) X(libra_error_write) X(libra_error_free_string) \
    X(libra_vk_filter_chain_create) X(libra_vk_filter_chain_frame) X(libra_vk_filter_chain_free) \
    X(libra_gl_filter_chain_create) X(libra_gl_filter_chain_frame) X(libra_gl_filter_chain_free)
struct Api {
#define DECL(f) PFN_##f f = nullptr;
    LIBRA_FUNCS(DECL)
#undef DECL
};

Api* api() {
    static Api a;
    static int state = 0; // 0 not tried, 1 loaded, -1 failed
    if (state == 0) {
        state = -1;
        // Next to the app's own libraries (jniLibs); it needs libc++_shared.so beside it.
        void* lib = dlopen("liblibrashader_capi.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) { LOGE("librashader: can't load: %s", dlerror()); return nullptr; }
        bool ok = true;
#define LOAD(f) a.f = reinterpret_cast<PFN_##f>(dlsym(lib, #f)); if (!a.f) { LOGE("librashader: no %s", #f); ok = false; }
        LIBRA_FUNCS(LOAD)
#undef LOAD
        if (!ok) return nullptr;
        state = 1;
        LOGI("librashader: loaded");
    }
    return state == 1 ? &a : nullptr;
}

void logError(const char* what, libra_error_t err) {
    Api* a = api();
    char* msg = nullptr;
    if (a && err && a->libra_error_write(err, &msg) == 0 && msg) {
        LOGE("librashader: %s: %s", what, msg);
        a->libra_error_free_string(&msg);
    } else {
        LOGE("librashader: %s failed", what);
    }
}

/** A fresh preset from the switch's path (a chain consumes the preset it's made from). */
bool loadPreset(libra_shader_preset_t* out) {
    const char* path = Libra::presetPath();
    Api* a = api();
    if (!path || !a) return false;
    libra_error_t err = a->libra_preset_create(path, out);
    if (err) { logError("preset", err); return false; }
    return true;
}
} // namespace

const char* Libra::presetPath() {
    const char* p = getenv("N64_SLANGP");
    return p && *p ? p : nullptr;
}

// ---- Vulkan ----------------------------------------------------------------

bool LibraVk::ready(VkInstance instance, VkPhysicalDevice gpu, VkDevice device, VkQueue queue,
                    PFN_vkGetInstanceProcAddr gipa, std::mutex& queueMutex, uint32_t framesInFlight) {
    if (chain_) return true;
    if (failed_ || !Libra::presetPath()) return false;
    failed_ = true; // until made
    libra_shader_preset_t preset = nullptr;
    if (!loadPreset(&preset)) return false;
    libra_device_vk_t dev{gpu, instance, device, queue, gipa};
    filter_chain_vk_opt_t opt{};
    opt.version = LIBRASHADER_CURRENT_VERSION;
    opt.frames_in_flight = framesInFlight;
    libra_vk_filter_chain_t chain = nullptr;
    libra_error_t err;
    {
        std::lock_guard<std::mutex> lk(queueMutex); // it uploads its look-up textures on this queue
        err = api()->libra_vk_filter_chain_create(&preset, dev, &opt, &chain);
    }
    if (err) { logError("vulkan chain", err); return false; }
    chain_ = chain;
    failed_ = false;
    LOGI("librashader: vulkan chain ready for %s", Libra::presetPath());
    return true;
}

void LibraVk::frame(VkCommandBuffer cmd, VkImage src, VkFormat srcFormat, uint32_t srcW, uint32_t srcH,
                    VkImage out, VkFormat outFormat, uint32_t outW, uint32_t outH) {
    if (!chain_) return;
    libra_vk_filter_chain_t chain = static_cast<libra_vk_filter_chain_t>(chain_);
    libra_image_vk_t in{src, srcFormat, srcW, srcH};
    libra_image_vk_t to{out, outFormat, outW, outH};
    libra_error_t err = api()->libra_vk_filter_chain_frame(&chain, cmd, frames_++, in, to, nullptr, nullptr, nullptr);
    static int fails = 0;
    if (err && fails++ % 600 == 0) logError("vulkan frame", err);
}

void LibraVk::destroy() {
    if (chain_ && api()) {
        libra_vk_filter_chain_t chain = static_cast<libra_vk_filter_chain_t>(chain_);
        api()->libra_vk_filter_chain_free(&chain);
    }
    chain_ = nullptr; failed_ = false; frames_ = 0;
}

// ---- OpenGL ES -------------------------------------------------------------

bool LibraGl::ready() {
    if (chain_) return true;
    if (failed_ || !Libra::presetPath()) return false;
    failed_ = true;
    libra_shader_preset_t preset = nullptr;
    if (!loadPreset(&preset)) return false;
    filter_chain_gl_opt_t opt{};
    opt.version = LIBRASHADER_CURRENT_VERSION;
    opt.glsl_version = 0; // from the context: GLSL ES 300/310/320 on GLES 3.x
    opt.use_dsa = false;
    opt.disable_cache = true;
    libra_gl_filter_chain_t chain = nullptr;
    auto loader = [](const char* name) -> const void* { return reinterpret_cast<const void*>(eglGetProcAddress(name)); };
    libra_error_t err = api()->libra_gl_filter_chain_create(&preset, loader, &opt, &chain);
    if (err) { logError("gl chain", err); return false; }
    chain_ = chain;
    failed_ = false;
    LOGI("librashader: gl chain ready for %s", Libra::presetPath());
    return true;
}

void LibraGl::frame(GLuint srcTex, int srcW, int srcH, GLuint outTex, int outW, int outH) {
    if (!chain_) return;
    libra_gl_filter_chain_t chain = static_cast<libra_gl_filter_chain_t>(chain_);
    libra_image_gl_t in{srcTex, GL_RGBA8, (uint32_t)srcW, (uint32_t)srcH};
    libra_image_gl_t to{outTex, GL_RGBA8, (uint32_t)outW, (uint32_t)outH};
    libra_error_t err = api()->libra_gl_filter_chain_frame(&chain, frames_++, in, to, nullptr, nullptr, nullptr);
    static int fails = 0;
    if (err && fails++ % 600 == 0) logError("gl frame", err);
}

void LibraGl::destroy() {
    if (chain_ && api()) {
        libra_gl_filter_chain_t chain = static_cast<libra_gl_filter_chain_t>(chain_);
        api()->libra_gl_filter_chain_free(&chain);
    }
    chain_ = nullptr; failed_ = false; frames_ = 0;
}
