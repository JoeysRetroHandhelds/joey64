// Settings › Video › Colour, the same sums as VideoTab.colourMap (the launcher's preview):
// contrast around mid grey, brightness added, gamma, then saturation around Rec. 709 luma.
// COLOUR = (brightness, contrast factor, saturation factor, gamma exponent); COLOUR_ON = 0 or 1.
vec3 applyColour(vec3 x) {
    if (COLOUR_ON == 0.0) return x;
    vec3 y = clamp((x - 0.5) * COLOUR.y + 0.5 + COLOUR.x, 0.0, 1.0);
    y = pow(y, vec3(COLOUR.w));
    float l = dot(y, vec3(0.2126, 0.7152, 0.0722));
    return clamp(mix(vec3(l), y, COLOUR.z), 0.0, 1.0);
}
