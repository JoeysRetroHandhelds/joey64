#pragma once
// Settings › Video's picture options (approved 2026-11-07), shared by the Vulkan and GLES presents.
// Set from the UI thread (NativeBridge.setDisplay / setColour / setSharpen), read at each present.
#include <atomic>
#include <algorithm>
#include <cstdlib>
#include <cstdio>
#include <cstdint>
#include <cmath>
#include <mutex>
#include <vector>

namespace Display {

struct Opts {
    std::atomic<int> aspW{4}, aspH{3};   // the picture's shape; 0:0 = stretch to the window
    std::atomic<bool> integer{false};     // whole-number scale of the image's height
    std::atomic<bool> bfi{false};         // black frame between pictures (screens at twice the game's rate)
    std::atomic<int> brightness{0}, contrast{0}, saturation{0}; // percent, -50..50
    std::atomic<int> gamma{10};           // tenths, 5..20
    std::atomic<int> sharpen{0};          // 0 off, 1 CAS, 2 FSR
    std::atomic<int> strength{50};        // percent
    bool colourOn() const { return brightness || contrast || saturation || gamma != 10; }
    bool postOn() const { return colourOn() || sharpen != 0; }

    // Settings › Video's Picture rows (approved mockup 2026-11-08; shaders/look.glsl): the shader stack
    // (ids, 0 = none; each shader's settings as bytes: 0..3 in pa, 4..7 in pb), Shader scale (0 Auto,
    // 1 the game's size, 2 2x, 3 3x, 4 the screen's), Sides of the picture (0 none, 1 blurred game,
    // 2 Joey64 frame, 3 your image), colour-blind help (0 off, 1 red-, 2 green-, 3 blue-weak), the
    // overlay's opacity (percent, 0 = off) and Place the game (tenths of a percent of the window; w 0 = centred).
    std::atomic<uint32_t> ids[4] = {}, pa[4] = {}, pb[4] = {};
    std::atomic<int> scale{0}, sides{0}, colourBlind{0}, overlay{0};
    std::atomic<int> placeX{0}, placeY{0}, placeW{0};
    std::atomic<int> portraitTop{-1};     // touch controls in portrait: the picture's top, per mille of the height (-1: centred)
    bool shaderOn() const { for (auto& i : ids) if (i) return true; return false; }
    // sharpBlit: the renderer draws Sharp pixels on its own with its blit (Vulkan's two-step blit, or its
    // colour pass), so Sharp pixels alone needs no Picture pass there.
    bool lookOn(bool sharpBlit = false) const {
        bool shader = false;
        for (auto& i : ids) if (i && !(sharpBlit && i == 1)) shader = true;
        return shader || sides != 0 || colourBlind != 0 || overlay != 0;
    }
    bool has(uint32_t id) const { for (auto& i : ids) if (i == id) return true; return false; }
};

inline Opts& opts() { static Opts o; return o; }

// The sides and overlay images (RGBA8, top row first), handed over from the UI thread; gen counts
// changes so each renderer uploads a new one once.
struct Image {
    std::mutex m;
    std::vector<uint8_t> rgba;
    int w = 0, h = 0;
    std::atomic<uint32_t> gen{0};
    void set(const uint8_t* p, int iw, int ih) {
        std::lock_guard<std::mutex> l(m);
        if (p && iw > 0 && ih > 0) { rgba.assign(p, p + (size_t)iw * ih * 4); w = iw; h = ih; } else { rgba.clear(); w = h = 0; }
        gen++;
    }
};
inline Image& sidesImage() { static Image i; return i; }
inline Image& overlayImage() { static Image i; return i; }

// The values look.glsl reads (both renderers fill them the same way).
struct LookPush {
    int32_t rect[4];
    float src[4];
    float misc[4];
    uint32_t ids[4], pa[4], pb[4], mode[4];
};
static_assert(sizeof(LookPush) == 112, "look push block");

// imgW x imgH: the picture in the pixels it is read at; rect: where it is in the winW x winH window (y from the top).
inline LookPush lookPush(int winW, int winH, int imgW, int imgH, int coreH, int x, int y, int w, int h, float uvScale) {
    Opts& o = opts();
    LookPush p{};
    p.rect[0] = x; p.rect[1] = y; p.rect[2] = w; p.rect[3] = h;
    p.src[0] = (float)imgW; p.src[1] = (float)imgH; p.src[2] = (float)winW; p.src[3] = (float)winH;
    // The N64's own lines: an upscaled picture is a whole number of times 240 (or 480) lines.
    // coreH: the core's picture height (imgH is the colour/sharpening pass's when that runs first).
    int k = std::max(1, (int)std::lround(coreH / 240.0));
    float lines = (float)coreH / (float)k;
    switch (o.scale.load()) {
        case 2: lines *= 2; break;
        case 3: lines *= 3; break;
        case 4: lines = (float)h; break;
        default: break;
    }
    p.misc[0] = lines;
    p.misc[1] = imgH > 0 ? (float)std::max(1, (h + imgH - 1) / imgH) : 1.0f; // sharp pixels' whole-number step
    p.misc[2] = o.overlay / 100.0f;
    p.misc[3] = uvScale;
    for (int i = 0; i < 4; ++i) { p.ids[i] = o.ids[i]; p.pa[i] = o.pa[i]; p.pb[i] = o.pb[i]; }
    p.mode[0] = (uint32_t)o.sides.load();
    p.mode[1] = (uint32_t)o.colourBlind.load();
    p.mode[2] = o.overlay > 0 ? 1u : 0u;
    return p;
}


// Dev switch N64_ASPECT=W:H (from before the menu): wins over the setting while it's set.
inline bool envAspect(int& w, int& h) {
    const char* a = getenv("N64_ASPECT");
    return a && sscanf(a, "%d:%d", &w, &h) == 2 && w > 0 && h > 0;
}

// Where an imgW x imgH picture goes in a winW x winH window: centred at the chosen shape, or the
// whole window for stretch. Integer scaling makes the height a whole multiple of the image's
// (the width follows the shape), unless even 1x doesn't fit (then it scales down as usual).
inline void fit(int winW, int winH, int imgW, int imgH, int& x, int& y, int& w, int& h) {
    const Opts& o = opts();
    int aw = o.aspW, ah = o.aspH;
    int ew, eh;
    if (envAspect(ew, eh)) { aw = ew; ah = eh; }
    // Place the game (your own sides image, or a RetroArch overlay's hole): there, at the picture's shape.
    if ((o.sides == 3 || o.sides == 4) && o.placeW > 0 && aw > 0 && ah > 0) {
        w = std::max(16, (int)((long long)winW * o.placeW / 1000));
        h = (int)((long long)w * ah / aw);
        x = (int)((long long)winW * o.placeX / 1000);
        y = (int)((long long)winH * o.placeY / 1000);
        return;
    }
    if (aw <= 0 || ah <= 0) {
        w = winW; h = winH;
        if (o.integer && imgH > 0 && winH >= imgH) h = winH / imgH * imgH;
    } else {
        h = winH; w = (int)((long long)winH * aw / ah);
        if (w > winW) { w = winW; h = (int)((long long)winW * ah / aw); }
        if (o.integer && imgH > 0) {
            int k = h / imgH;
            if (k >= 1) { h = imgH * k; w = (int)((long long)h * aw / ah); }
        }
    }
    x = (winW - w) / 2; y = (winH - h) / 2;
    // Touch controls in portrait (approved mockup 2026-11-09, screen 6): the game on top, the controls below.
    int top = o.portraitTop;
    if (top >= 0 && winH > winW) y = std::min(y, (int)((long long)winH * top / 1000));
}

} // namespace Display
