#version 300 es
precision highp float;
// Core-owned separable 5-tap bloom at half FBO resolution. Pass 0 (uDir = (1,0)): luminance threshold +
// horizontal blur from the scene texture (2× downsample). Pass 1 (uDir = (0,1)): vertical blur of pass 0.
// Taps clamp to the eye's own rect so nothing bleeds across the eye seam (DESIGN §1.4 hygiene).
//
// The bloom carries HUE (owner feedback 2026-09-03): pass 0 samples are tone-mapped (luminance Reinhard,
// hue kept) and saturation-boosted BEFORE they are blurred, so a hot core contributes its colour, not a
// multi-stop HDR value that summed with its neighbours into a pale haze; pass 1 re-saturates the blurred
// result so mixed hues stay vivid. uBloomStrength keeps its meaning (post.frag adds the halo as before).
uniform sampler2D uSrc;
uniform vec2  uDir;          // (1,0) or (0,1)
uniform float uStep;         // tap spacing in SOURCE texels
uniform float uThreshold;    // pass 0 only (0 in pass 1)
uniform vec2  uEyeRect;      // (eyeW, eyeH) of one eye rect IN SOURCE TEXELS
uniform int   uMono;         // 1 → only the left eye rect exists
uniform vec2  uSrcSize, uDstSize;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 fetch(vec2 px) { return texture(uSrc, px / uSrcSize).rgb; }
// Saturation × s about the pixel's luma, chroma scaled back (hue kept) if a channel would leave [0, 1].
vec3 satBoost(vec3 c, float s) {
    float l = dot(c, LUMA);
    vec3 d = (c - l) * s;
    float mx = max(d.r, max(d.g, d.b)), mn = min(d.r, min(d.g, d.b));
    float t = 1.0;
    if (mx > 1.0 - l) t = min(t, (1.0 - l) / mx);
    if (mn < -l)      t = min(t, -l / mn);
    return l + d * t;
}
vec3 thresh(vec3 c) {
    if (uThreshold <= 0.0) return c;
    float l = dot(c, LUMA);
    c *= smoothstep(uThreshold, uThreshold + 0.5, l);
    l = dot(c, LUMA);
    c /= (1.0 + l);                   // tone-mapped (hue kept): a 3-stop core is 0.75, not 3.0
    return satBoost(c, 1.35);          // saturated: the halo is the core's colour
}
void main() {
    vec2 srcPx = gl_FragCoord.xy * (uSrcSize / uDstSize);
    float eyeIdx = (uMono == 1) ? 0.0 : clamp(floor(srcPx.x / uEyeRect.x), 0.0, 1.0);
    vec2 lo = vec2(eyeIdx * uEyeRect.x, 0.0) + 0.5;
    vec2 hi = vec2((eyeIdx + 1.0) * uEyeRect.x, uEyeRect.y) - 0.5;
    const float w0 = 0.2270270, w1 = 0.3162162, w2 = 0.0702703;
    vec2 d = uDir * uStep;
    vec3 acc = thresh(fetch(clamp(srcPx, lo, hi))) * w0;
    acc += thresh(fetch(clamp(srcPx + d, lo, hi))) * w1;
    acc += thresh(fetch(clamp(srcPx - d, lo, hi))) * w1;
    acc += thresh(fetch(clamp(srcPx + 2.0 * d, lo, hi))) * w2;
    acc += thresh(fetch(clamp(srcPx - 2.0 * d, lo, hi))) * w2;
    if (uThreshold <= 0.0) acc = satBoost(acc, 1.25);   // pass 1: blurred (mixed) hues stay vivid
    fragColor = vec4(acc, 1.0);
}
