// Settings › Video › Shader, Sides of the picture, Overlay and Colour's colour-blind help (approved
// Picture mockup 2026-11-08, scratchpad build_picture2.py): the window's last pass on both renderers.
// Every pixel of the window: inside the picture, the picture through the shader stack and the
// colour-blind step; outside it, the sides; then the overlay image over all of it.
// Written for Joey64 (GPL-3.0-or-later); no RetroArch shader code.
//
// The backend (post_look.comp, look_gl.frag) defines:
//   vec3 pictureAt(vec2 uv)   the picture at uv (0,0 = its top left), linear
//   vec4 sidesAt(vec2 uv)     the sides image at uv (window, 0,0 = top left)
//   vec4 overlayAt(vec2 uv)   the overlay image at uv
//   vec3 previousAt(ivec2 p)  the last frame's output at window pixel p (top-left origin)
//   ivec2 sidesSize(), overlaySize()
// and these values (push constants on Vulkan, uniforms on GLES):
//   ivec4 L_RECT      the picture in the window: x, y (from the top), width, height
//   vec4  L_SRC       the picture's size in source pixels (w, h), the window's size (w, h)
//   vec4  L_MISC      N64 lines the shaders draw at, sharp-pixels factor, overlay opacity 0..1, unused
//   uvec4 L_IDS       up to four shaders, in order (0 = none)
//   uvec4 L_PA, L_PB  each shader's settings: shader i has bytes 0..3 of L_PA[i] then 0..3 of L_PB[i]
//   uvec4 L_MODE      sides (0 none, 1 blurred game, 2 Joey64 frame, 3 your image, 4 a RetroArch
//                     overlay: the image over the game and around it), colour-blind
//                     (0 off, 1 red-weak, 2 green-weak, 3 blue-weak), overlay on (1), unused

#define SH_SHARP 1u
#define SH_SMOOTH 2u
#define SH_CRT_LIGHT 3u
#define SH_CRT_HEAVY 4u
#define SH_LCD 5u
#define SH_NTSC 6u

float lookParam(int i, int k) {
    uint v = k < 4 ? L_PA[i] : L_PB[i];
    return float((v >> uint(8 * (k & 3))) & 255u);
}

vec3 rgb2yiq(vec3 c) {
    return vec3(dot(c, vec3(0.299, 0.587, 0.114)), dot(c, vec3(0.596, -0.274, -0.322)), dot(c, vec3(0.211, -0.523, 0.312)));
}
vec3 yiq2rgb(vec3 y) {
    return vec3(dot(y, vec3(1.0, 0.956, 0.621)), dot(y, vec3(1.0, -0.272, -0.647)), dot(y, vec3(1.0, -1.106, 1.703)));
}

// The source's own pixel size in picture uv (an upscaled picture still has the N64's pixels).
vec2 nativePixel() {
    float k = max(1.0, floor(L_SRC.y / max(L_MISC.x, 1.0) + 0.5));
    return k / L_SRC.xy;
}

// How the picture is read: the first sampling shader in the stack (Sharp pixels, Smooth, NTSC) decides.
vec3 samplePicture(vec2 uv) {
    for (int i = 0; i < 4; ++i) {
        uint id = L_IDS[i];
        if (id == SH_SHARP && L_MISC.y > 1.0) {
            // Nearest to k times the size, then linear: only the pixel edges blend.
            vec2 texel = uv * L_SRC.xy, fl = floor(texel), d = fract(texel) - 0.5;
            float r = 0.5 - 0.5 / L_MISC.y;
            return pictureAt((fl + (d - clamp(d, -r, r)) * L_MISC.y + 0.5) / L_SRC.xy);
        }
        if (id == SH_SMOOTH) {
            vec2 h = 0.5 / L_SRC.xy * max(1.0, L_SRC.y / max(L_MISC.x, 1.0));
            return 0.25 * (pictureAt(uv + vec2(-h.x, -h.y)) + pictureAt(uv + vec2(h.x, -h.y)) +
                           pictureAt(uv + vec2(-h.x, h.y)) + pictureAt(uv + vec2(h.x, h.y)));
        }
        if (id == SH_NTSC) {
            // Composite video: luma a little soft, chroma smeared wider along the line.
            float blur = lookParam(i, 0) / 100.0, bleed = lookParam(i, 1) / 100.0;
            float px = nativePixel().x;
            vec3 y = vec3(0.0); float wy = 0.0; vec2 iq = vec2(0.0); float wc = 0.0;
            for (int t = -3; t <= 3; ++t) {
                vec3 s = rgb2yiq(pictureAt(uv + vec2(float(t) * px * 0.5, 0.0)));
                float a = exp(-float(t * t) / (0.5 + 3.0 * blur));
                float b = exp(-float(t * t) / (0.5 + 8.0 * bleed));
                y.x += s.x * a; wy += a; iq += s.yz * b; wc += b;
            }
            vec3 r = vec3(y.x / wy, iq / wc);
            r.yz *= 1.0 + 0.25 * bleed;
            return clamp(yiq2rgb(r), 0.0, 1.0);
        }
    }
    return pictureAt(uv);
}

// The screen effects of every shader in the stack, in order, on a pixel of the picture.
vec3 screenEffects(vec3 c, vec2 uv, ivec2 p) {
    for (int i = 0; i < 4; ++i) {
        uint id = L_IDS[i];
        if (id == SH_CRT_LIGHT || id == SH_CRT_HEAVY) {
            float lines = max(L_MISC.x, 1.0);
            float scan = lookParam(i, 0) / 100.0;
            float glow = (id == SH_CRT_LIGHT ? lookParam(i, 1) : lookParam(i, 3)) / 100.0;
            // Glow: what's bright around this pixel bleeds in.
            if (glow > 0.0) {
                vec2 d = 1.5 * nativePixel();
                vec3 g = 0.25 * (pictureAt(uv + d) + pictureAt(uv - d) + pictureAt(uv + vec2(d.x, -d.y)) + pictureAt(uv + vec2(-d.x, d.y)));
                c += glow * 0.6 * g * g;
            }
            // The beam: bright in the middle of each line, darker between lines.
            float f = fract(uv.y * lines) - 0.5;
            float beam = exp(-f * f / 0.045);
            c *= mix(1.0, beam * 1.3, scan);
            if (id == SH_CRT_HEAVY) {
                float mask = lookParam(i, 1);
                if (mask < 2.0) { // 0 aperture grille, 1 slot mask
                    int col = p.x + (mask > 0.5 ? ((p.y / 2) & 1) * 2 : 0);
                    int m = col - 3 * (col / 3);
                    vec3 w = vec3(0.72);
                    if (m == 0) w.r = 1.0; else if (m == 1) w.g = 1.0; else w.b = 1.0;
                    c *= w * 1.18;
                }
                c *= 1.0 + lookParam(i, 4) / 100.0;
            }
        } else if (id == SH_LCD) {
            // The handheld LCD's grid: dark lines between the N64's pixels.
            float grid = lookParam(i, 0) / 100.0;
            vec2 cell = fract(uv / nativePixel());
            vec2 e = smoothstep(vec2(0.0), vec2(0.14), cell) * smoothstep(vec2(0.0), vec2(0.14), 1.0 - cell);
            c *= mix(1.0, e.x * e.y, grid * 0.85);
        }
    }
    return clamp(c, 0.0, 1.0);
}

// The curved glass of CRT heavy's Curve (0 off, 1 low, 2 high): where on the picture this pixel looks.
vec2 curveUv(vec2 uv) {
    for (int i = 0; i < 4; ++i) {
        if (L_IDS[i] != SH_CRT_HEAVY) continue;
        float k = lookParam(i, 2) * 0.06;
        if (k <= 0.0) return uv;
        vec2 d = uv - 0.5;
        return 0.5 + d * (1.0 + k * dot(d, d)) / (1.0 + k * 0.25);
    }
    return uv;
}

// LCD grid's Ghosting: how much of the last frame stays (0..1).
float ghosting() {
    for (int i = 0; i < 4; ++i) if (L_IDS[i] == SH_LCD) return lookParam(i, 1) / 100.0 * 0.6;
    return 0.0;
}

// Colour's colour-blind help (daltonize): the colours that eye confuses, moved to ones it sees.
vec3 colourBlind(vec3 c, uint mode) {
    if (mode == 0u) return c;
    // RGB to LMS, the eye's three cones (Viénot, Brettel and Mollon 1999).
    mat3 toLms = mat3(17.8824, 3.45565, 0.0299566, 43.5161, 27.1554, 0.184309, 4.11935, 3.86714, 1.46709);
    mat3 toRgb = mat3(0.0809444479, -0.0102485335, -0.000365296938, -0.130504409, 0.0540193266, -0.00412161469,
                      0.116721066, -0.113614708, 0.693511405);
    vec3 lms = toLms * c;
    vec3 sim;
    if (mode == 1u) sim = vec3(2.02344 * lms.y - 2.52581 * lms.z, lms.y, lms.z);              // red-weak
    else if (mode == 2u) sim = vec3(lms.x, 0.494207 * lms.x + 1.24827 * lms.z, lms.z);         // green-weak
    else sim = vec3(lms.x, lms.y, -0.395913 * lms.x + 0.801109 * lms.y);                       // blue-weak
    vec3 err = c - toRgb * sim;
    // The lost difference, shifted to channels that eye still tells apart.
    vec3 shift = vec3(0.0, 0.7 * err.r + err.g, 0.7 * err.r + err.b);
    return clamp(c + shift, 0.0, 1.0);
}

// The picture shown with its shape kept, covering the whole window (the blurred sides, images).
vec2 coverUv(vec2 win, vec2 size) {
    float s = max(L_SRC.z / size.x, L_SRC.w / size.y);
    return (win * L_SRC.zw - 0.5 * L_SRC.zw) / (size * s) + 0.5;
}

vec3 sides(ivec2 p) {
    uint mode = L_MODE.x;
    vec2 win = (vec2(p) + 0.5) / L_SRC.zw;
    if (mode == 1u) { // the game, blurred and dimmed, covering the window
        vec2 aspect = vec2(float(L_RECT.z), float(L_RECT.w));
        // Kept off the picture's outer 6% (the N64's black border), then a 7x7 gaussian: a sparse ring
        // of taps showed hard bands wherever it crossed an edge (Thor 2026-11-08).
        vec2 uv = mix(vec2(0.06), vec2(0.94), coverUv(win, aspect));
        vec3 s = vec3(0.0);
        float wsum = 0.0;
        for (int j = -3; j <= 3; ++j) for (int i = -3; i <= 3; ++i) {
            float w = exp(-float(i * i + j * j) / 8.0);
            s += w * pictureAt(clamp(uv + vec2(float(i), float(j)) * 0.014, 0.0, 1.0));
            wsum += w;
        }
        s /= wsum;
        float l = dot(s, vec3(0.2126, 0.7152, 0.0722));
        return clamp(mix(vec3(l), s, 1.2) * 0.45, 0.0, 1.0);
    }
    if (mode == 2u || mode == 3u) {
        vec4 img = sidesAt(coverUv(win, vec2(sidesSize())));
        vec3 c = img.rgb * img.a;
        if (mode == 2u) { // the Joey64 frame's thin yellow edge beside the picture
            bool rows = p.y >= L_RECT.y && p.y < L_RECT.y + L_RECT.w;
            bool edge = (p.x >= L_RECT.x - 2 && p.x < L_RECT.x) || (p.x >= L_RECT.x + L_RECT.z && p.x < L_RECT.x + L_RECT.z + 2);
            if (rows && edge) c = vec3(0.976, 0.816, 0.212);
        }
        return c;
    }
    return vec3(0.0);
}

vec3 lookPixel(ivec2 p) {
    vec2 pr = (vec2(p) + 0.5 - vec2(L_RECT.xy)) / vec2(L_RECT.zw);
    vec3 c;
    if (pr.x >= 0.0 && pr.y >= 0.0 && pr.x < 1.0 && pr.y < 1.0) {
        vec2 uv = curveUv(pr);
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) c = vec3(0.0);
        else c = colourBlind(screenEffects(samplePicture(uv), uv, p), L_MODE.y);
        float g = ghosting();
        if (g > 0.0) c = mix(c, previousAt(p), g);
    } else c = sides(p);
    // A RetroArch overlay (approved mockup 2026-11-08): drawn over everything, as RetroArch does, so the game
    // shows through its see-through hole along with any glass or glare painted there.
    if (L_MODE.x == 4u) {
        vec4 b = sidesAt(coverUv((vec2(p) + 0.5) / L_SRC.zw, vec2(sidesSize())));
        c = mix(c, b.rgb, b.a);
    }
    if (L_MODE.z != 0u && L_MISC.z > 0.0) {
        vec4 o = overlayAt(coverUv((vec2(p) + 0.5) / L_SRC.zw, vec2(overlaySize())));
        c = mix(c, o.rgb, o.a * L_MISC.z);
    }
    return c;
}
