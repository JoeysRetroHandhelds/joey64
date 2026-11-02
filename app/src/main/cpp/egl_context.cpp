#include "egl_context.h"
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "n64host", __VA_ARGS__)

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

    const EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttrs);
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
        if (window_ == EGL_NO_SURFACE) { LOGE("window surface failed"); return false; }
        eglSwapInterval(display_, 1); // vsync; M4 replaces with Swappy
    }
    return makeCurrent();
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
    if (window_ == EGL_NO_SURFACE) return;
    EGLint winW = 0, winH = 0;
    eglQuerySurface(display_, window_, EGL_WIDTH, &winW);
    eglQuerySurface(display_, window_, EGL_HEIGHT, &winH);

    // 4:3 letterbox. M5 adds widescreen / integer-scale options.
    int dstH = winH, dstW = winH * 4 / 3;
    if (dstW > winW) { dstW = winW; dstH = winW * 3 / 4; }
    int x = (winW - dstW) / 2, y = (winH - dstH) / 2;

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo_);
    if (bottomLeftOrigin)
        glBlitFramebuffer(0, 0, srcW, srcH, x, y, x + dstW, y + dstH, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    else
        glBlitFramebuffer(0, srcH, srcW, 0, x, y, x + dstW, y + dstH, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    eglSwapBuffers(display_, window_);
}

void EglContext::destroy() {
    if (display_ == EGL_NO_DISPLAY) return;
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (window_) eglDestroySurface(display_, window_);
    if (pbuffer_) eglDestroySurface(display_, pbuffer_);
    if (context_) eglDestroyContext(display_, context_);
    eglTerminate(display_);
    display_ = EGL_NO_DISPLAY; context_ = EGL_NO_CONTEXT;
    window_ = pbuffer_ = EGL_NO_SURFACE;
}
