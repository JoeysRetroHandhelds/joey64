#pragma once
// Settings › Video's picture options (approved 2026-11-07), shared by the Vulkan and GLES presents.
// Set from the UI thread (NativeBridge.setDisplay / setColour / setSharpen), read at each present.
#include <atomic>
#include <algorithm>
#include <cstdlib>
#include <cstdio>

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
};

inline Opts& opts() { static Opts o; return o; }

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
}

} // namespace Display
