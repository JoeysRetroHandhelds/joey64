#version 310 es
// Settings › Video's present pass on GLES (egl_context.cpp, GLideN64): one source, four passes
// (PASS 0 colour only, 1 CAS, 2 FSR EASU into a texture, 3 FSR RCAS), the same as the Vulkan
// compute shaders. devtools/build-shaders.sh flattens each pass (glslc -E) into shaders/post_gl.h.
precision highp float;
precision highp int;
precision highp sampler2D;

uniform sampler2D imgSrc;        // the core's FBO (the picture fills its bottom-left srcW x srcH) or EASU's result
uniform uvec4 c0, c1, c2, c3;    // AMD's constants (post_consts.h)
uniform vec4 colour;             // brightness, contrast factor, saturation factor, gamma exponent
uniform vec4 extra;              // picture width, height, unused, colour on (1)
uniform ivec4 place;             // where this pass draws: x, y, width, height
uniform int flipRows;            // 1: the picture's first row is its top (glReadPixels order), so draw it upside down
out vec4 fragColor;

#define COLOUR colour
#define COLOUR_ON extra.w
#include "colour.glsl"

ivec2 srcSize() { return ivec2(extra.xy); }

// This fragment's pixel in the picture's own rows.
ivec2 outPixel() {
    ivec2 p = ivec2(gl_FragCoord.xy) - place.xy;
    if (flipRows != 0) p.y = place.w - 1 - p.y;
    return p;
}

#if PASS != 0
#define A_GPU 1
#define A_GLSL 1
#include "ffx_a.h"
#endif

#if PASS == 1
AF3 CasLoad(ASU2 p) { return texelFetch(imgSrc, clamp(p, ivec2(0), srcSize() - 1), 0).rgb; }
void CasInput(inout AF1 r, inout AF1 g, inout AF1 b) {}
#include "ffx_cas.h"
#elif PASS == 2
#define FSR_EASU_F 1
AF4 FsrEasuRF(AF2 p) { return textureGather(imgSrc, p, 0); }
AF4 FsrEasuGF(AF2 p) { return textureGather(imgSrc, p, 1); }
AF4 FsrEasuBF(AF2 p) { return textureGather(imgSrc, p, 2); }
#include "ffx_fsr1.h"
#elif PASS == 3
#define FSR_RCAS_F 1
AF4 FsrRcasLoadF(ASU2 p) { return texelFetch(imgSrc, clamp(p, ivec2(0), textureSize(imgSrc, 0) - 1), 0); }
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}
#include "ffx_fsr1.h"
#endif

void main() {
    ivec2 p = outPixel();
    vec3 c;
#if PASS == 0
    // Linear, as the plain blit: the picture's part of the texture across the drawn area.
    vec2 uv = (vec2(p) + 0.5) / vec2(place.zw) * extra.xy / vec2(textureSize(imgSrc, 0));
    c = textureLod(imgSrc, uv, 0.0).rgb;
#elif PASS == 1
    CasFilter(c.r, c.g, c.b, AU2(p), c0, c1, false);
#elif PASS == 2
    FsrEasuF(c, AU2(p), c0, c1, c2, c3);
#else
    FsrRcasF(c.r, c.g, c.b, AU2(p), c0);
#endif
#if PASS != 2
    c = applyColour(c);
#endif
    fragColor = vec4(c, 1.0);
}
