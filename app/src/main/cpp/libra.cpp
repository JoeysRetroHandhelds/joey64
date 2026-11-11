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
#define LIBRA_FUNCS(X) X(libra_preset_create) X(libra_preset_free) X(libra_preset_set_param) X(libra_preset_get_param) \
    X(libra_preset_get_runtime_params) X(libra_preset_free_runtime_params) \
    X(libra_error_write) X(libra_error_free_string) X(libra_error_free) \
    X(libra_vk_filter_chain_create) X(libra_vk_filter_chain_frame) X(libra_vk_filter_chain_free) X(libra_vk_filter_chain_set_param) \
    X(libra_gl_filter_chain_create) X(libra_gl_filter_chain_frame) X(libra_gl_filter_chain_free) X(libra_gl_filter_chain_set_param)
struct Api {
#define DECL(f) PFN_##f f = nullptr;
    LIBRA_FUNCS(DECL)
#undef DECL
};

std::mutex g_apiMutex;

Api* api() {
    static Api a;
    static int state = 0; // 0 not tried, 1 loaded, -1 failed
    std::lock_guard<std::mutex> lk(g_apiMutex);
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

/** librashader's message for [err], which is freed. */
std::string errText(libra_error_t err) {
    Api* a = api();
    std::string r = "librashader failed";
    if (!a || !err) return r;
    char* msg = nullptr;
    if (a->libra_error_write(err, &msg) == 0 && msg) { r = msg; a->libra_error_free_string(&msg); }
    a->libra_error_free(&err);
    return r;
}

void setStatus(const std::string& path, const std::string& status) {
    auto& s = Libra::setting();
    std::lock_guard<std::mutex> lk(s.m);
    s.status = status; s.statusPath = path;
}

std::vector<std::pair<std::string, float>> currentParams() {
    auto& s = Libra::setting();
    std::lock_guard<std::mutex> lk(s.m);
    return s.params;
}

/** A preset from [path] with [params] set on it (a chain consumes the preset it's made from). */
bool loadPreset(const std::string& path, const std::vector<std::pair<std::string, float>>& params,
                libra_shader_preset_t* out, std::string& err) {
    Api* a = api();
    if (!a) { err = "librashader isn't in this build"; return false; }
    libra_error_t e = a->libra_preset_create(path.c_str(), out);
    if (e) { err = errText(e); return false; }
    for (auto& p : params) {
        libra_error_t pe = a->libra_preset_set_param(out, p.first.c_str(), p.second);
        if (pe) LOGE("librashader: setting %s: %s", p.first.c_str(), errText(pe).c_str());
    }
    return true;
}
} // namespace

Libra::Setting& Libra::setting() { static Setting s; return s; }

std::string Libra::presetPath() {
    const char* p = getenv("N64_SLANGP");
    if (p && *p) return p;
    auto& s = setting();
    std::lock_guard<std::mutex> lk(s.m);
    return s.path;
}

void Libra::set(const std::string& path, std::vector<std::pair<std::string, float>> params) {
    auto& s = setting();
    std::lock_guard<std::mutex> lk(s.m);
    // Same preset: new values go onto the running chain; a setting dropped (Reset) needs the preset's own
    // value back, so the chain is made again.
    bool same = path == s.path;
    if (same) for (auto& old : s.params) {
        bool kept = false;
        for (auto& p : params) if (p.first == old.first) { kept = true; break; }
        if (!kept) { same = false; break; }
    }
    if (same && params == s.params) return;
    s.path = path;
    s.params = std::move(params);
    if (same) s.paramGen++; else { s.gen++; s.status.clear(); s.statusPath.clear(); }
}

std::string Libra::status() {
    auto& s = setting();
    std::lock_guard<std::mutex> lk(s.m);
    if (s.status.empty()) return "";
    if (s.status == "ok") return "ok\n" + s.statusPath;
    return "error\n" + s.statusPath + "\n" + s.status;
}

bool Libra::available() { return api() != nullptr; }

bool Libra::params(const char* path, std::vector<ParamInfo>& out, std::string& err) {
    libra_shader_preset_t preset = nullptr;
    if (!loadPreset(path, {}, &preset, err)) return false;
    Api* a = api();
    libra_preset_param_list_t list{};
    libra_error_t e = a->libra_preset_get_runtime_params(&preset, &list);
    if (e) { err = errText(e); a->libra_preset_free(&preset); return false; }
    for (uint64_t i = 0; i < list.length; ++i) {
        const libra_preset_param_t& p = list.parameters[i];
        float v = p.initial;
        // The preset's own value (a .slangp can set its shaders' values); the shader's when it doesn't.
        if (libra_error_t ge = a->libra_preset_get_param(&preset, p.name, &v)) { errText(ge); v = p.initial; }
        out.push_back({p.name ? p.name : "", p.description ? p.description : "", v, p.minimum, p.maximum, p.step});
    }
    a->libra_preset_free_runtime_params(list);
    a->libra_preset_free(&preset);
    return true;
}

// ---- Vulkan ----------------------------------------------------------------

bool LibraVk::stale() const { return chain_ && Libra::setting().gen.load() != gen_; }

bool LibraVk::ready(VkInstance instance, VkPhysicalDevice gpu, VkDevice device, VkQueue queue,
                    PFN_vkGetInstanceProcAddr gipa, std::mutex& queueMutex, uint32_t framesInFlight) {
    auto& s = Libra::setting();
    uint32_t g = s.gen.load();
    if (g != gen_) {
        if (chain_) {
            libra_vk_filter_chain_t chain = static_cast<libra_vk_filter_chain_t>(chain_);
            api()->libra_vk_filter_chain_free(&chain);
            chain_ = nullptr;
        }
        gen_ = g; failed_ = false; frames_ = 0;
    }
    if (chain_) {
        uint32_t pg = s.paramGen.load();
        if (pg != paramGen_) {
            paramGen_ = pg;
            libra_vk_filter_chain_t chain = static_cast<libra_vk_filter_chain_t>(chain_);
            for (auto& p : currentParams())
                if (libra_error_t e = api()->libra_vk_filter_chain_set_param(&chain, p.first.c_str(), p.second))
                    LOGE("librashader: setting %s: %s", p.first.c_str(), errText(e).c_str());
        }
        return true;
    }
    if (failed_) return false;
    std::string path = Libra::presetPath();
    if (path.empty()) return false;
    failed_ = true; // until made
    paramGen_ = s.paramGen.load();
    std::string err;
    libra_shader_preset_t preset = nullptr;
    if (!loadPreset(path, currentParams(), &preset, err)) {
        LOGE("librashader: %s: %s", path.c_str(), err.c_str());
        setStatus(path, err);
        return false;
    }
    libra_device_vk_t dev{gpu, instance, device, queue, gipa};
    filter_chain_vk_opt_t opt{};
    opt.version = LIBRASHADER_CURRENT_VERSION;
    opt.frames_in_flight = framesInFlight;
    libra_vk_filter_chain_t chain = nullptr;
    libra_error_t e;
    {
        std::lock_guard<std::mutex> lk(queueMutex); // it uploads its look-up textures on this queue
        e = api()->libra_vk_filter_chain_create(&preset, dev, &opt, &chain);
    }
    if (e) {
        err = errText(e);
        LOGE("librashader: vulkan chain for %s: %s", path.c_str(), err.c_str());
        setStatus(path, err);
        return false;
    }
    chain_ = chain;
    path_ = path;
    failed_ = false;
    setStatus(path, "ok");
    LOGI("librashader: vulkan chain ready for %s", path.c_str());
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
    if (err) { std::string m = errText(err); if (fails++ % 600 == 0) LOGE("librashader: vulkan frame: %s", m.c_str()); }
}

void LibraVk::destroy() {
    if (chain_ && api()) {
        libra_vk_filter_chain_t chain = static_cast<libra_vk_filter_chain_t>(chain_);
        api()->libra_vk_filter_chain_free(&chain);
    }
    chain_ = nullptr; failed_ = false; frames_ = 0; gen_ = Libra::setting().gen.load(); path_.clear();
}

// ---- OpenGL ES -------------------------------------------------------------

namespace {
const void* glLoader(const char* name) { return reinterpret_cast<const void*>(eglGetProcAddress(name)); }

bool makeGlChain(const std::string& path, const std::vector<std::pair<std::string, float>>& params,
                 libra_gl_filter_chain_t* out, std::string& err) {
    libra_shader_preset_t preset = nullptr;
    if (!loadPreset(path, params, &preset, err)) return false;
    filter_chain_gl_opt_t opt{};
    opt.version = LIBRASHADER_CURRENT_VERSION;
    opt.glsl_version = 0; // from the context: GLSL ES 300/310/320 on GLES 3.x
    opt.use_dsa = false;
    opt.disable_cache = true;
    libra_error_t e = api()->libra_gl_filter_chain_create(&preset, glLoader, &opt, out);
    if (e) { err = errText(e); return false; }
    return true;
}
} // namespace

bool LibraGl::ready() {
    auto& s = Libra::setting();
    uint32_t g = s.gen.load();
    if (g != gen_) {
        if (chain_) {
            libra_gl_filter_chain_t chain = static_cast<libra_gl_filter_chain_t>(chain_);
            api()->libra_gl_filter_chain_free(&chain);
            chain_ = nullptr;
        }
        gen_ = g; failed_ = false; frames_ = 0;
    }
    if (chain_) {
        uint32_t pg = s.paramGen.load();
        if (pg != paramGen_) {
            paramGen_ = pg;
            libra_gl_filter_chain_t chain = static_cast<libra_gl_filter_chain_t>(chain_);
            for (auto& p : currentParams())
                if (libra_error_t e = api()->libra_gl_filter_chain_set_param(&chain, p.first.c_str(), p.second))
                    LOGE("librashader: setting %s: %s", p.first.c_str(), errText(e).c_str());
        }
        return true;
    }
    if (failed_) return false;
    std::string path = Libra::presetPath();
    if (path.empty()) return false;
    failed_ = true;
    paramGen_ = s.paramGen.load();
    std::string err;
    libra_gl_filter_chain_t chain = nullptr;
    if (!makeGlChain(path, currentParams(), &chain, err)) {
        LOGE("librashader: gl chain for %s: %s", path.c_str(), err.c_str());
        setStatus(path, err);
        return false;
    }
    chain_ = chain;
    path_ = path;
    failed_ = false;
    setStatus(path, "ok");
    LOGI("librashader: gl chain ready for %s", path.c_str());
    return true;
}

void LibraGl::frame(GLuint srcTex, int srcW, int srcH, GLuint outTex, int outW, int outH) {
    if (!chain_) return;
    libra_gl_filter_chain_t chain = static_cast<libra_gl_filter_chain_t>(chain_);
    libra_image_gl_t in{srcTex, GL_RGBA8, (uint32_t)srcW, (uint32_t)srcH};
    libra_image_gl_t to{outTex, GL_RGBA8, (uint32_t)outW, (uint32_t)outH};
    libra_error_t err = api()->libra_gl_filter_chain_frame(&chain, frames_++, in, to, nullptr, nullptr, nullptr);
    static int fails = 0;
    if (err) { std::string m = errText(err); if (fails++ % 600 == 0) LOGE("librashader: gl frame: %s", m.c_str()); }
}

void LibraGl::destroy() {
    if (chain_ && api()) {
        libra_gl_filter_chain_t chain = static_cast<libra_gl_filter_chain_t>(chain_);
        api()->libra_gl_filter_chain_free(&chain);
    }
    chain_ = nullptr; failed_ = false; frames_ = 0; gen_ = Libra::setting().gen.load(); path_.clear();
}

// ---- Settings' previews: an offscreen GLES context of their own ------------

namespace {
struct Preview {
    EGLDisplay d = EGL_NO_DISPLAY;
    EGLContext c = EGL_NO_CONTEXT;
    EGLSurface s = EGL_NO_SURFACE;
    libra_gl_filter_chain_t chain = nullptr;
    std::string path;
    std::vector<std::pair<std::string, float>> applied;
    GLuint inTex = 0, outTex = 0, outFbo = 0;
    int inW = 0, inH = 0, outW = 0, outH = 0;
    size_t frames = 0;
};
std::mutex g_prevMutex;
Preview g_prev;

void prevFreeGl() {
    Preview& p = g_prev;
    if (p.chain && api()) api()->libra_gl_filter_chain_free(&p.chain);
    p.chain = nullptr; p.path.clear(); p.applied.clear();
    if (p.outFbo) glDeleteFramebuffers(1, &p.outFbo);
    if (p.inTex) glDeleteTextures(1, &p.inTex);
    if (p.outTex) glDeleteTextures(1, &p.outTex);
    p.inTex = p.outTex = p.outFbo = 0; p.inW = p.inH = p.outW = p.outH = 0;
}

void prevDestroy(bool current) {
    Preview& p = g_prev;
    if (current) prevFreeGl();
    else { p.chain = nullptr; p.path.clear(); p.applied.clear(); p.inTex = p.outTex = p.outFbo = 0; p.inW = p.inH = p.outW = p.outH = 0; }
    if (p.d != EGL_NO_DISPLAY) {
        eglMakeCurrent(p.d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (p.s != EGL_NO_SURFACE) eglDestroySurface(p.d, p.s);
        if (p.c != EGL_NO_CONTEXT) eglDestroyContext(p.d, p.c);
    }
    // The display isn't terminated: the game's own GLES context may share it.
    p.d = EGL_NO_DISPLAY; p.c = EGL_NO_CONTEXT; p.s = EGL_NO_SURFACE;
}

bool prevMakeCurrent(std::string& err) {
    Preview& p = g_prev;
    for (int attempt = 0; attempt < 2; ++attempt) {
        if (p.c == EGL_NO_CONTEXT) {
            p.d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
            if (p.d == EGL_NO_DISPLAY || !eglInitialize(p.d, nullptr, nullptr)) { err = "no EGL display"; return false; }
            const EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                                      EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
            EGLConfig cfg; EGLint n = 0;
            if (!eglChooseConfig(p.d, cfgAttr, &cfg, 1, &n) || n < 1) { err = "no EGL config"; return false; }
            const EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
            p.c = eglCreateContext(p.d, cfg, EGL_NO_CONTEXT, ctxAttr);
            const EGLint pbAttr[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            p.s = eglCreatePbufferSurface(p.d, cfg, pbAttr);
            if (p.c == EGL_NO_CONTEXT || p.s == EGL_NO_SURFACE) { err = "no EGL context"; prevDestroy(false); return false; }
        }
        if (eglMakeCurrent(p.d, p.s, p.s, p.c)) return true;
        // The display was terminated under it (a GLES game ended): start again.
        prevDestroy(false);
    }
    err = "can't use the EGL context";
    return false;
}
} // namespace

bool Libra::preview(const char* path, const std::vector<std::pair<std::string, float>>& params, const uint8_t* rgba, int w, int h,
                    int outW, int outH, std::vector<uint8_t>& out, std::string& err) {
    std::lock_guard<std::mutex> lk(g_prevMutex);
    if (!api()) { err = "librashader isn't in this build"; return false; }
    if (!prevMakeCurrent(err)) return false;
    Preview& p = g_prev;
    // Same preset with settings only added or changed: onto the chain; else a new chain.
    bool keep = p.chain && p.path == path;
    if (keep) for (auto& old : p.applied) {
        bool kept = false;
        for (auto& n : params) if (n.first == old.first) { kept = true; break; }
        if (!kept) { keep = false; break; }
    }
    if (!keep) {
        if (p.chain) { api()->libra_gl_filter_chain_free(&p.chain); p.chain = nullptr; }
        p.path.clear(); p.frames = 0;
        if (!makeGlChain(path, params, &p.chain, err)) { p.chain = nullptr; eglMakeCurrent(p.d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); return false; }
        p.path = path;
    } else {
        for (auto& n : params) api()->libra_gl_filter_chain_set_param(&p.chain, n.first.c_str(), n.second);
    }
    p.applied = params;
    if (!p.inTex || p.inW != w || p.inH != h) {
        if (p.inTex) glDeleteTextures(1, &p.inTex);
        glGenTextures(1, &p.inTex);
        glBindTexture(GL_TEXTURE_2D, p.inTex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
        p.inW = w; p.inH = h;
    }
    // GL rows go up: the picture's bottom row first.
    std::vector<uint8_t> up((size_t)w * h * 4);
    for (int y = 0; y < h; ++y) memcpy(&up[(size_t)y * w * 4], rgba + (size_t)(h - 1 - y) * w * 4, (size_t)w * 4);
    glBindTexture(GL_TEXTURE_2D, p.inTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, up.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    if (!p.outTex || p.outW != outW || p.outH != outH) {
        if (p.outFbo) glDeleteFramebuffers(1, &p.outFbo);
        if (p.outTex) glDeleteTextures(1, &p.outTex);
        glGenTextures(1, &p.outTex);
        glBindTexture(GL_TEXTURE_2D, p.outTex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, outW, outH);
        glGenFramebuffers(1, &p.outFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, p.outFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, p.outTex, 0);
        p.outW = outW; p.outH = outH;
    }
    glBindTexture(GL_TEXTURE_2D, 0);
    libra_image_gl_t in{p.inTex, GL_RGBA8, (uint32_t)w, (uint32_t)h};
    libra_image_gl_t to{p.outTex, GL_RGBA8, (uint32_t)outW, (uint32_t)outH};
    // A few frames, so presets that read their previous frame settle.
    for (int i = 0; i < 3; ++i) {
        if (libra_error_t e = api()->libra_gl_filter_chain_frame(&p.chain, p.frames++, in, to, nullptr, nullptr, nullptr)) {
            err = errText(e);
            eglMakeCurrent(p.d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            return false;
        }
    }
    std::vector<uint8_t> raw((size_t)outW * outH * 4);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, p.outFbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, outW, outH, GL_RGBA, GL_UNSIGNED_BYTE, raw.data());
    GLenum ge = glGetError();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    eglMakeCurrent(p.d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (ge) { err = "GL error " + std::to_string(ge); return false; }
    out.resize(raw.size());
    for (int y = 0; y < outH; ++y) memcpy(&out[(size_t)y * outW * 4], &raw[(size_t)(outH - 1 - y) * outW * 4], (size_t)outW * 4);
    return true;
}

void Libra::previewClose() {
    std::lock_guard<std::mutex> lk(g_prevMutex);
    Preview& p = g_prev;
    if (p.c == EGL_NO_CONTEXT) return;
    std::string err;
    bool current = eglMakeCurrent(p.d, p.s, p.s, p.c);
    prevDestroy(current);
}
