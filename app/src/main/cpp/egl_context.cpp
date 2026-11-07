#include "joeylog.h"
#include "egl_context.h"
#include "display_opts.h"
#include "post_consts.h"
#include "shaders/post_gl.h"
#include <android/log.h>
#include <initializer_list>
#include <cstring>
#define LOGE(...) joeyLog(ANDROID_LOG_ERROR, "joey64host", __VA_ARGS__)

bool EglContext::init() {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(display_, nullptr, nullptr)) { LOGE("eglInitialize failed"); return false; }

    const EGLint attrs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8, EGL_NONE };
    EGLint n = 0;
    if (!eglChooseConfig(display_, attrs, &config_, 1, &n) || n == 0) { LOGE("no EGL config"); return false; }

    // Ask for the newest ES 3.x: drivers may hide extensions (framebuffer fetch, dual source
    // blending) from a plain "3" context, and GLideN64 needs them for N64 coverage emulation.
    for (EGLint minor : {2, 1, 0}) {
        const EGLint ctxAttrs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, minor, EGL_NONE };
        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttrs);
        if (context_ != EGL_NO_CONTEXT) break;
    }
    if (context_ == EGL_NO_CONTEXT) { LOGE("eglCreateContext failed"); return false; }

    // Small pbuffer so the context can be current before a window exists.
    const EGLint pb[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    pbuffer_ = eglCreatePbufferSurface(display_, config_, pb);
    return makeCurrent();
}

bool EglContext::attachWindow(ANativeWindow* win) {
    if (window_ != EGL_NO_SURFACE) {
        eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
        eglDestroySurface(display_, window_);
        window_ = EGL_NO_SURFACE;
    }
    if (win) {
        window_ = eglCreateWindowSurface(display_, config_, win, nullptr);
        if (window_ == EGL_NO_SURFACE) { LOGE("window surface failed: 0x%x", eglGetError()); return false; }
        LOGE("egl: window surface %dx%d", ANativeWindow_getWidth(win), ANativeWindow_getHeight(win));
    }
    if (!makeCurrent()) { LOGE("egl: makeCurrent failed: 0x%x", eglGetError()); return false; }
    if (win) eglSwapInterval(display_, swapInterval_); // vsync; M4 replaces with Swappy
    return true;
}

void EglContext::setSwapInterval(int interval) {
    swapInterval_ = interval;
    if (window_ != EGL_NO_SURFACE) eglSwapInterval(display_, interval);
}

bool EglContext::makeCurrent() {
    EGLSurface s = window_ != EGL_NO_SURFACE ? window_ : pbuffer_;
    return eglMakeCurrent(display_, s, s, context_) == EGL_TRUE;
}

void EglContext::ensureFbo(int w, int h) {
    if (fbo_ && w == fboW_ && h == fboH_) return;
    if (fbo_) { glDeleteFramebuffers(1, &fbo_); glDeleteTextures(1, &color_); glDeleteRenderbuffers(1, &depth_); }
    glGenTextures(1, &color_);
    glBindTexture(GL_TEXTURE_2D, color_);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glGenRenderbuffers(1, &depth_);
    glBindRenderbuffer(GL_RENDERBUFFER, depth_);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);

    glGenFramebuffers(1, &fbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color_, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth_);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) LOGE("FBO incomplete");
    fboW_ = w; fboH_ = h;
}

void EglContext::present(int srcW, int srcH, bool bottomLeftOrigin) {
    static int noWindow = 0, swapFails = 0;
    if (window_ == EGL_NO_SURFACE) { if (noWindow++ % 600 == 0) LOGE("egl: present with no window (%d)", noWindow); return; }
    EGLint winW = 0, winH = 0;
    eglQuerySurface(display_, window_, EGL_WIDTH, &winW);
    eglQuerySurface(display_, window_, EGL_HEIGHT, &winH);

    // Settings › Video: the shape and integer scaling (display_opts.h), as the Vulkan path.
    int x, y, dstW, dstH;
    Display::fit(winW, winH, srcW, srcH, x, y, dstW, dstH);

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo_);
    // Colour or sharpening changed: draw through the shaders (the core's GL state is its own again
    // at its next frame, glsm rebinds it); else the plain blit.
    const Display::Opts& o = Display::opts();
    if (o.postOn() && dstW > 0 && dstH > 0 && ensurePost() && (o.sharpen != 2 || ensureMid(dstW, dstH))) {
        uint32_t c[16] = {};
        float colour[4], extra[4] = {(float)srcW, (float)srcH, 0.0f, 0.0f};
        extra[3] = Post::colour(colour);
        bool flip = !bottomLeftOrigin;
        if (o.sharpen == 2) { // FSR: EASU into mid (the picture's own rows), then RCAS to the window
            Post::easu(c, (float)srcW, (float)srcH, (float)fboW_, (float)fboH_, (float)dstW, (float)dstH);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, midFbo_);
            drawPost(2, color_, 0, 0, dstW, dstH, false, c, colour, extra);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            memset(c, 0, sizeof(c));
            Post::rcas(c);
            drawPost(3, midTex_, x, y, dstW, dstH, flip, c, colour, extra);
        } else {
            if (o.sharpen == 1) Post::cas(c, (float)srcW, (float)srcH, (float)dstW, (float)dstH);
            drawPost(o.sharpen == 1 ? 1 : 0, color_, x, y, dstW, dstH, flip, c, colour, extra);
        }
    } else if (bottomLeftOrigin)
        glBlitFramebuffer(0, 0, srcW, srcH, x, y, x + dstW, y + dstH, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    else
        glBlitFramebuffer(0, srcH, srcW, 0, x, y, x + dstW, y + dstH, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    if (!eglSwapBuffers(display_, window_) && swapFails++ % 600 == 0)
        LOGE("egl: swap failed: 0x%x (%d times)", eglGetError(), swapFails);
}

// ---- Settings › Video's colour and sharpening ------------------------------

namespace {
GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024] = {};
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        LOGE("egl: present pass shader failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}
} // namespace

bool EglContext::ensurePost() {
    if (post_[0].prog) return true;
    if (postFailed_) return false;
    postFailed_ = true; // until all four are made
    // A triangle over the viewport, from the vertex index alone.
    static const char* vs = "#version 310 es\n"
        "void main() { vec2 p = vec2(float((gl_VertexID & 1) << 2), float((gl_VertexID & 2) << 1)) - 1.0;"
        " gl_Position = vec4(p, 0.0, 1.0); }\n";
    const char* fs[4] = {k_post_gl0_frag, k_post_gl1_frag, k_post_gl2_frag, k_post_gl3_frag};
    GLuint v = compile(GL_VERTEX_SHADER, vs);
    if (!v) return false;
    for (int i = 0; i < 4; ++i) {
        GLuint f = compile(GL_FRAGMENT_SHADER, fs[i]);
        if (!f) { glDeleteShader(v); destroyPost(); return false; }
        GLuint p = glCreateProgram();
        glAttachShader(p, v); glAttachShader(p, f);
        glLinkProgram(p);
        glDeleteShader(f);
        GLint ok = 0;
        glGetProgramiv(p, GL_LINK_STATUS, &ok);
        if (!ok) {
            char log[1024] = {};
            glGetProgramInfoLog(p, sizeof(log), nullptr, log);
            LOGE("egl: present pass %d link failed: %s", i, log);
            glDeleteProgram(p); glDeleteShader(v); destroyPost();
            return false;
        }
        PostProg& pp = post_[i];
        pp.prog = p;
        for (int k = 0; k < 4; ++k) { char n[3] = {'c', (char)('0' + k), 0}; pp.c[k] = glGetUniformLocation(p, n); }
        pp.colour = glGetUniformLocation(p, "colour");
        pp.extra = glGetUniformLocation(p, "extra");
        pp.place = glGetUniformLocation(p, "place");
        pp.flip = glGetUniformLocation(p, "flipRows");
        pp.src = glGetUniformLocation(p, "imgSrc");
    }
    glDeleteShader(v);
    glGenVertexArrays(1, &postVao_);
    postFailed_ = false;
    LOGE("egl: colour/sharpening present pass ready");
    return true;
}

bool EglContext::ensureMid(int w, int h) {
    if (midFbo_ && w == midW_ && h == midH_) return true;
    if (midFbo_) { glDeleteFramebuffers(1, &midFbo_); glDeleteTextures(1, &midTex_); midFbo_ = midTex_ = 0; }
    glGenTextures(1, &midTex_);
    glBindTexture(GL_TEXTURE_2D, midTex_);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glGenFramebuffers(1, &midFbo_);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, midFbo_);
    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, midTex_, 0);
    bool ok = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    if (!ok) { LOGE("egl: present pass FBO incomplete"); return false; }
    midW_ = w; midH_ = h;
    return true;
}

void EglContext::drawPost(int pass, GLuint tex, int x, int y, int w, int h, bool flip, const uint32_t* c,
                          const float* colour, const float* extra) {
    const PostProg& pp = post_[pass];
    glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glViewport(x, y, w, h);
    glUseProgram(pp.prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glBindSampler(0, 0);
    glUniform1i(pp.src, 0);
    for (int k = 0; k < 4; ++k) if (pp.c[k] >= 0) glUniform4uiv(pp.c[k], 1, c + 4 * k);
    glUniform4fv(pp.colour, 1, colour);
    glUniform4fv(pp.extra, 1, extra);
    glUniform4i(pp.place, x, y, w, h);
    glUniform1i(pp.flip, flip ? 1 : 0);
    glBindVertexArray(postVao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glUseProgram(0);
}

void EglContext::destroyPost() {
    for (auto& pp : post_) { if (pp.prog) glDeleteProgram(pp.prog); pp.prog = 0; }
    if (postVao_) glDeleteVertexArrays(1, &postVao_);
    if (midFbo_) glDeleteFramebuffers(1, &midFbo_);
    if (midTex_) glDeleteTextures(1, &midTex_);
    postVao_ = midFbo_ = midTex_ = 0; midW_ = midH_ = 0;
}

bool EglContext::capture(int srcW, int srcH, bool bottomLeftOrigin, std::vector<uint8_t>& rgba) {
    if (!fbo_ || srcW <= 0 || srcH <= 0 || srcW > fboW_ || srcH > fboH_) return false;
    std::vector<uint8_t> raw((size_t)srcW * srcH * 4);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo_);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, srcW, srcH, GL_RGBA, GL_UNSIGNED_BYTE, raw.data());
    if (GLenum e = glGetError()) { LOGE("egl: capture: glReadPixels 0x%x", e); return false; }
    // glReadPixels starts at the bottom row; a bottom-left-origin frame is then upright when flipped.
    rgba.resize(raw.size());
    size_t row = (size_t)srcW * 4;
    for (int y = 0; y < srcH; ++y) {
        int from = bottomLeftOrigin ? srcH - 1 - y : y;
        memcpy(&rgba[y * row], &raw[from * row], row);
    }
    for (size_t i = 3; i < rgba.size(); i += 4) rgba[i] = 255;
    return true;
}

void EglContext::destroy() {
    if (display_ == EGL_NO_DISPLAY) return;
    destroyPost(); // the context is still current here
    postFailed_ = false;
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (window_) eglDestroySurface(display_, window_);
    if (pbuffer_) eglDestroySurface(display_, pbuffer_);
    if (context_) eglDestroyContext(display_, context_);
    eglTerminate(display_);
    display_ = EGL_NO_DISPLAY; context_ = EGL_NO_CONTEXT;
    window_ = pbuffer_ = EGL_NO_SURFACE;
}
