#version 310 es
// Settings › Video's Shader, Sides of the picture, Overlay and colour-blind help on GLES (egl_context.cpp,
// GLideN64): drawn over the whole window. The sums are look.glsl's, shared with Vulkan.
precision highp float;
precision highp int;
precision highp sampler2D;

uniform sampler2D imgPicture;   // the core's FBO, or the colour/sharpening pass's picture
uniform sampler2D imgSides;
uniform sampler2D imgOverlay;
uniform sampler2D imgPrevious;  // the last frame's window (LCD ghosting), GL rows
uniform ivec4 rect;             // the picture in the window, y from the top
uniform vec4 src;               // picture w, h; window w, h
uniform vec4 misc;              // lines, sharp factor, overlay opacity, unused
uniform uvec4 ids, pa, pb, mode;
uniform vec4 picUv;             // the picture's part of the texture (u, v scale) and whether its rows go up (z = 1)
out vec4 fragColor;

#define L_RECT rect
#define L_SRC src
#define L_MISC misc
#define L_IDS ids
#define L_PA pa
#define L_PB pb
#define L_MODE mode

vec3 pictureAt(vec2 uv) {
    uv = clamp(uv, 0.0, 1.0);
    if (picUv.z > 0.5) uv.y = 1.0 - uv.y;
    return textureLod(imgPicture, uv * picUv.xy, 0.0).rgb;
}
vec4 sidesAt(vec2 uv) { return textureLod(imgSides, uv, 0.0); }
vec4 overlayAt(vec2 uv) { return textureLod(imgOverlay, uv, 0.0); }
vec3 previousAt(ivec2 p) { return texelFetch(imgPrevious, ivec2(p.x, int(src.w) - 1 - p.y), 0).rgb; }
ivec2 sidesSize() { return textureSize(imgSides, 0); }
ivec2 overlaySize() { return textureSize(imgOverlay, 0); }

#include "look.glsl"

void main() {
    ivec2 p = ivec2(int(gl_FragCoord.x), int(src.w) - 1 - int(gl_FragCoord.y));
    fragColor = vec4(lookPixel(p), 1.0);
}
