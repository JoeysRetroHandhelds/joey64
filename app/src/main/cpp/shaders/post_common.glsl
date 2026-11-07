// Settings › Video's present pass on Vulkan (vk_post.cpp): shared by every post_*.comp.
// binding 0: what is read (the core's picture, or FSR's upscaled one); binding 1: what is written.
layout(set = 0, binding = 0) uniform sampler2D imgSrc;
layout(set = 0, binding = 1, rgba8) uniform writeonly image2D imgDst;

// One block for every pass (one pipeline layout). c0..c3: AMD's constants (CasSetup, FsrEasuCon,
// FsrRcasCon); colour: brightness, contrast factor, saturation factor, gamma exponent;
// extra: source width, height, sharp-bilinear factor (plain pass), colour on (1) or off (0).
layout(push_constant) uniform Push {
    uvec4 c0, c1, c2, c3;
    vec4 colour;
    vec4 extra;
} pc;

#define COLOUR pc.colour
#define COLOUR_ON pc.extra.w
#include "colour.glsl"

ivec2 srcSize() { return ivec2(pc.extra.xy); }
