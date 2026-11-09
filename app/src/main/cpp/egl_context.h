#pragma once
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <cstdint>
#include <vector>
#include "libra.h"

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

    // Settings › Video's colour and sharpening (shaders/post_gl.frag): made on first use, only while
    // one of them is changed. Passes: 0 colour only, 1 CAS, 2 FSR EASU (into mid), 3 FSR RCAS.
    struct PostProg { GLuint prog = 0; GLint c[4], colour, extra, place, flip, src; };
    PostProg post_[4];
    GLuint postVao_ = 0, midTex_ = 0, midFbo_ = 0;
    int midW_ = 0, midH_ = 0;
    bool postFailed_ = false;
    bool ensurePost();
    bool ensureMid(int w, int h);
    void drawPost(int pass, GLuint tex, int x, int y, int w, int h, bool flip, const uint32_t* c,
                  const float* colour, const float* extra);
    void destroyPost();

    // Settings › Video's Shader, Sides of the picture, Overlay and colour-blind help (shaders/look_gl.frag,
    // look.glsl): one pass over the whole window into one of two window textures (LCD ghosting reads the
    // other), then blitted to the window. With colour or sharpening on, those draw the picture into pic first.
    struct LookProg { GLuint prog = 0; GLint rect, src, misc, ids, pa, pb, mode, picUv, pic, sides, overlay, prev; };
    LookProg look_;
    GLuint lookTex_[2] = {}, lookFbo_[2] = {};
    int lookW_ = 0, lookH_ = 0, lookCur_ = 0;
    GLuint picTex_ = 0, picFbo_ = 0;
    int picW_ = 0, picH_ = 0;
    GLuint sidesTex_ = 0, overlayTex_ = 0, dummyTex_ = 0;
    uint32_t sidesSeen_ = 0, overlaySeen_ = 0;
    bool lookFailed_ = false;
    bool ensureLook();
    bool ensureLookTargets(int w, int h);
    bool ensurePic(int w, int h);
    void uploadLookImage(GLuint& tex, uint32_t& seen, bool sides);
    void drawLook(GLuint picTex, const float* picUv, int imgW, int imgH, int coreH, int winW, int winH,
                  int x, int y, int w, int h);
    void destroyLook();
    // Your own RetroArch preset (libra.h): the picture drawn upright into libraIn at its own size (through
    // Colour when that's on), the preset draws libraOut at the shown size, which is blitted in or drawn by
    // the Picture pass (Sides, Overlay).
    LibraGl libra_;
    GLuint libraInTex_ = 0, libraInFbo_ = 0, libraOutTex_ = 0, libraOutFbo_ = 0;
    int libraInW_ = 0, libraInH_ = 0, libraOutW_ = 0, libraOutH_ = 0;
    bool drawLibra(int srcW, int srcH, bool bottomLeftOrigin, int winW, int winH, int x, int y, int dstW, int dstH);
    void destroyLibra();
};
