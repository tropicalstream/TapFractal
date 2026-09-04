#version 300 es
precision highp float;
// Core-owned post pass: per eye at 640×480 on the screen. Order (DESIGN §7.1): breath → (echo already
// composed into uHdr) → bloom add → chromatic aberration (prism when Wild) → vignette → hue-preserving
// tone-map with core tint × exposure → VIBRANCE → gamma 1/2.2. HUD is composited afterwards by a separate pass.
//
// Waveguide vibrance (owner feedback 2026-09-03, "colours washed out"):
//  * tone-map cores are pulled toward the pixel's OWN hue at full saturation (leaned by uCoreTint, which is
//    normalised to max 1 so it can only tint, never lift toward white) — the old mix toward a near-white
//    coreTint × luma is what turned every bright core pastel;
//  * a vibrance stage after the tone-map, before gamma: k = 1 + 0.6·(1 − sat), chroma × 1.25·k about the
//    pixel's luma (weak colours are boosted most, already-saturated ones least), hue-preserving clamp;
//  * blacks stay exactly black: nothing below luma 0.01 is touched (0 → 0 everywhere in this file).
uniform sampler2D uHdr;          // composed hdr (scene + echo) at the FBO eye rect
uniform sampler2D uBloom;        // half-res bloom (tone-mapped, saturated colour — see bloom.frag)
uniform vec2  uScreenOrigin, uScreenRes;   // this eye's screen viewport
uniform vec2  uEyeOrigin, uEyeRes;         // this eye's rect in the FBO (mono: the left rect for both eyes)
uniform vec2  uFboSize;
uniform float uUvShift;
uniform int   uSceneId;
uniform float uEchoMix, uEchoZoom, uEchoRot;
uniform vec2  uEchoCentre;
uniform vec3  uEchoTint;
uniform vec3  uBloomTint; uniform float uBloomStrength;
uniform vec3  uCoreTint;
uniform float uAberr;
uniform float uBreath;
uniform float uExposure;
uniform int   uPrism;
uniform float uHitPulse;
uniform float uEnergy, uBeat;
// Vibrance amount. GLSL ES has no uniform initialisers, so an UNSET uniform reads 0.0 and is treated as the
// default 1.0; the core may later set it per Quality/setting: 0 or unset = 1.0 (default), > 0 = that strength
// (0.5 = half, 1.5 = stronger), < 0 = stage off. Nothing on the Kotlin side needs to change until then.
uniform float uVibrance;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec2 toTex(vec2 uv) {
    vec2 px = uEyeOrigin + 0.5 * uEyeRes + uv * uEyeRes.y;
    px = clamp(px, uEyeOrigin + 0.5, uEyeOrigin + uEyeRes - 0.5);
    return px / uFboSize;
}
vec3 hdrAt(vec2 uv) { return texture(uHdr, toTex(uv)).rgb; }
vec3 bloomAt(vec2 uv) {
    vec2 px = (uEyeOrigin + 0.5 * uEyeRes + uv * uEyeRes.y) * 0.5;
    vec2 lo = uEyeOrigin * 0.5 + 0.5, hi = (uEyeOrigin + uEyeRes) * 0.5 - 0.5;
    px = clamp(px, lo, hi);
    return texture(uBloom, px / (uFboSize * 0.5)).rgb;
}
// Hue-preserving clamp: scale the chroma about the pixel's luma until every channel is inside [0, 1].
// (A per-channel clamp shifts the hue and bleaches toward white — the pastel the waveguide punishes.)
vec3 clampHue(vec3 c) {
    float l = clamp(dot(c, LUMA), 0.0, 1.0);
    vec3 d = c - l;
    float mx = max(d.r, max(d.g, d.b)), mn = min(d.r, min(d.g, d.b));
    float t = 1.0;
    if (mx > 1.0 - l) t = min(t, (1.0 - l) / mx);
    if (mn < -l)      t = min(t, -l / mn);
    return l + d * t;
}
// The pixel's own hue at full saturation (max channel 1, min channel 0). Greys have no hue → white.
vec3 fullSat(vec3 c) {
    float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b));
    if (mx - mn < 1e-4) return vec3(1.0);
    return (c - mn) / (mx - mn);
}
vec3 tonemap(vec3 c, vec3 coreTint) {
    float l = dot(c, LUMA);
    vec3 m = c / (1.0 + l);                                   // Reinhard on luminance → hue kept
    float hot = smoothstep(1.2, 3.0, l);                      // only true cores
    if (hot > 0.0) {
        // Core rule: the core keeps ITS hue at full saturation, leaned by the scene's core tint. The tint is
        // normalised to max 1 (no white lift — a tint of (0.8,0.95,1) can only lean toward ice, never brighten).
        vec3 tint = coreTint / max(max(coreTint.r, coreTint.g), max(coreTint.b, 1e-3));
        vec3 core = fullSat(c) * tint;
        float lm = min(l / (1.0 + l), 1.0);                   // the core's tone-mapped luma
        core *= lm / max(dot(core, LUMA), 1e-3);              // same luma as the Reinhard result…
        core = clampHue(core);                                // …as saturated as that luma allows
        m = mix(m, core, hot * 0.6);
    }
    return clamp(m, 0.0, 1.0);
}
// Vibrance: boost weak colours more than strong ones, about the pixel's own luma (luma unchanged, hue kept).
vec3 vibrance(vec3 c, float amount) {
    float l = dot(c, LUMA);
    if (l < 0.01 || amount <= 0.0) return c;                  // blacks stay exactly black
    float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b));
    float sat = (mx - mn) / max(mx, 1e-4);
    float k = 1.0 + 0.6 * (1.0 - sat);
    float g = mix(1.0, 1.25 * k, amount * smoothstep(0.01, 0.03, l));   // ease in just above the black floor
    return clampHue(l + (c - l) * g);
}
void main() {
    vec2 uv = ((gl_FragCoord.xy - uScreenOrigin) - 0.5 * uScreenRes) / uScreenRes.y;
    vec2 c = uEchoCentre;
    uv = c + (uv - c) * (1.0 + uBreath);
    vec3 hdr;
    float a = uAberr;
    if (uPrism == 1) {
        vec2 d0 = vec2(1.0, 0.0), d1 = vec2(-0.5, 0.8660254), d2 = vec2(-0.5, -0.8660254);
        hdr.r = hdrAt(uv + d0 * a * length(uv - c) * 2.0 + (uv - c) * a).r;
        hdr.g = hdrAt(uv + d1 * a * length(uv - c) * 2.0).g;
        hdr.b = hdrAt(uv + d2 * a * length(uv - c) * 2.0 - (uv - c) * a).b;
    } else {
        hdr.r = hdrAt(c + (uv - c) * (1.0 + a)).r;
        hdr.g = hdrAt(uv).g;
        hdr.b = hdrAt(c + (uv - c) * (1.0 - a)).b;
    }
    // bloom.frag already tone-mapped + saturated its samples, so the halo carries the source's hue and the
    // luminance Reinhard below keeps it (uBloomStrength semantics unchanged).
    hdr += bloomAt(uv) * uBloomTint * uBloomStrength * (1.0 + 0.6 * uHitPulse);
    float vig = 1.0 - (0.35 * (1.0 - 0.1 * uHitPulse)) * smoothstep(0.55, 1.25, length(uv - c));
    hdr *= vig;
    vec3 m = tonemap(hdr * uExposure, uCoreTint);
    float vib = (uVibrance == 0.0) ? 1.0 : max(uVibrance, 0.0);
    m = vibrance(m, vib);
    m = pow(m, vec3(1.0 / 2.2));
    fragColor = vec4(m, 1.0);
}
