#pragma once
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <cstdint>
#include <vector>

// Owns the EGL display/context and an offscreen FBO the core renders into.
// Each frame the FBO is blitted to the window surface (handles scaling/letterbox).
class EglContext {
public:
    bool init();                              // display + context + pbuffer, no window yet
    bool attachWindow(ANativeWindow* win);    // nullptr detaches
    bool makeCurrent();
    void setSwapInterval(int interval);       // 1 = vsync, 0 = unthrottled (fast-forward)
    void ensureFbo(int w, int h);             // (re)create FBO at core's max size
    GLuint fbo() const { return fbo_; }
    void present(int srcW, int srcH, bool bottomLeftOrigin);
    // The FBO's last frame ([srcW] x [srcH], as presented) as top-down RGBA8. Context must be current.
    bool capture(int srcW, int srcH, bool bottomLeftOrigin, std::vector<uint8_t>& rgba);
    void destroy();
    bool hasWindow() const { return window_ != EGL_NO_SURFACE; }

private:
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLConfig config_ = nullptr;
    EGLSurface window_ = EGL_NO_SURFACE;
    EGLSurface pbuffer_ = EGL_NO_SURFACE;
    GLuint fbo_ = 0, color_ = 0, depth_ = 0;
    int fboW_ = 0, fboH_ = 0;
    int swapInterval_ = 1;
};
