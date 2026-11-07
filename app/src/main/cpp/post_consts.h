#pragma once
// Constants for Settings › Video's colour and sharpening shaders, shared by the Vulkan (vk_post.cpp)
// and GLES (egl_context.cpp) presents. The sharpening ones are AMD's setup functions (CasSetup in
// ffx_cas.h, FsrEasuCon / FsrRcasCon in ffx_fsr1.h, MIT), for their 32-bit shader paths (the packed
// half copies they also fill are left 0).
#include "display_opts.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

namespace Post {

inline uint32_t asU(float f) { uint32_t u; memcpy(&u, &f, 4); return u; }

inline float strength() { return std::clamp(Display::opts().strength.load(), 0, 100) / 100.0f; }

// CasSetup: c[0..7] = const0, const1. Strength 0..1 is CAS's sharpness.
inline void cas(uint32_t* c, float inW, float inH, float outW, float outH) {
    c[0] = asU(inW / outW); c[1] = asU(inH / outH);
    c[2] = asU(0.5f * inW / outW - 0.5f); c[3] = asU(0.5f * inH / outH - 0.5f);
    c[4] = asU(-1.0f / (8.0f + (5.0f - 8.0f) * strength())); c[5] = 0;
    c[6] = asU(8.0f * inW / outW); c[7] = 0;
}

// FsrEasuCon: c[0..15] = con0..con3. vp = the picture inside the texture, size = the texture.
inline void easu(uint32_t* c, float vpW, float vpH, float sizeW, float sizeH, float outW, float outH) {
    c[0] = asU(vpW / outW); c[1] = asU(vpH / outH);
    c[2] = asU(0.5f * vpW / outW - 0.5f); c[3] = asU(0.5f * vpH / outH - 0.5f);
    c[4] = asU(1.0f / sizeW); c[5] = asU(1.0f / sizeH);
    c[6] = asU(1.0f / sizeW); c[7] = asU(-1.0f / sizeH);
    c[8] = asU(-1.0f / sizeW); c[9] = asU(2.0f / sizeH);
    c[10] = asU(1.0f / sizeW); c[11] = asU(2.0f / sizeH);
    c[12] = asU(0.0f); c[13] = asU(4.0f / sizeH); c[14] = c[15] = 0;
}

// FsrRcasCon: c[0..3]. Strength 100% = 0 stops (AMD's maximum), 0% = 2 stops less.
inline void rcas(uint32_t* c) {
    c[0] = asU(std::exp2(-2.0f * (1.0f - strength()))); c[1] = c[2] = c[3] = 0;
}

// The colour step's (brightness, contrast factor, saturation factor, gamma exponent); 1 if any changed.
inline float colour(float* v) {
    const Display::Opts& o = Display::opts();
    v[0] = o.brightness / 100.0f;
    v[1] = 1.0f + o.contrast / 100.0f;
    v[2] = 1.0f + o.saturation / 100.0f;
    v[3] = 10.0f / (float)std::max(1, o.gamma.load());
    return o.colourOn() ? 1.0f : 0.0f;
}

} // namespace Post
