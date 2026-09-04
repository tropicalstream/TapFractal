#version 300 es
precision highp float; precision highp int;
precision highp sampler2D;
// =====================================================================================
// TapFractal — common.glsl (core-owned). Prepended to every sceneN.frag; footer.glsl is
// appended. Scene files define ONLY `vec3 render(vec2 uv)` (linear RGB, may exceed 1.0)
// plus their own helpers. See docs/SHADER_CONTRACT.md and docs/CORE_API.md.
// =====================================================================================

// ---- Part A: SPEC contract (uniforms verbatim; C1: the uFov comment is corrected) ----
uniform vec2  uEyeRes;      // per-eye viewport size in the FBO (e.g. 320×240)
uniform vec2  uEyeOrigin;   // viewport origin in the FBO
uniform int   uEye;         // 0 left, 1 right
uniform float uTime, uDt;   // seconds since scene start, frame dt
uniform vec3  uCamPos;      // per-eye camera position (stereo offset already applied)
uniform mat3  uCamRot;      // columns: right, up, forward
uniform float uUvShift;     // per-eye horizontal uv shift for convergence
uniform float uFov;         // 2·tan(half vertical fov)  (C1; default 0.35)
uniform vec4  uSluurp;       // xyz pos, w radius
uniform float uSluurpGlow;   // 0..1 flick energy
uniform vec3  uAnchor;      // rope anchor
uniform vec4  uRope[12];    // rope points xyz (w unused); count in uRopeN
uniform int   uRopeN;
uniform vec4  uTargets[6];  // xyz + radius (radius<=0 inactive)
uniform float uTargetPulse[6]; // 0..1 hit-burst age (1 = just hit)
uniform float uZoom;        // continuous depth level (0..)
uniform float uBeat, uEnergy, uBass, uTreble; // 0..1
uniform float uHue;         // 0..1 global hue drift
uniform float uQuality;     // 0.3..1 — scale ray-step budgets by this
uniform float uFade;        // 0..1 crossfade weight (scene drawn dim while fading)
// Part A helper prototypes (the SPEC lists them in shorthand; these are the compilable forms):
vec2  eyeUV();               // ((gl_FragCoord.xy-uEyeOrigin) - 0.5*uEyeRes)/uEyeRes.y + vec2(uUvShift,0)
vec3  rayDir(vec2 uv);       // normalize(uCamRot * vec3(uv*uFov, 1.0))
vec3  pal(float t, vec3 a, vec3 b, vec3 c, vec3 d); // IQ cosine palette
mat2  rot2(float a); mat3 rotX(float a); mat3 rotY(float a); mat3 rotZ(float a);
float hash11(float p); float hash21(vec2 p); float noise3(vec3 p); // cheap noise
float sdSphere(vec3 p, float r); float sdCapsule(vec3 p, vec3 a, vec3 b, float r); float smin(float a, float b, float k);
float toysDE(vec3 p, out int id);   // union of sluurp (id 1), rope capsules (id 2), targets (id 10+i) — raymarch scenes min() this into their DE
vec3  toyColor(int id, vec3 p, vec3 n); // emissive colour for those ids (uses uSluurpGlow / uTargetPulse)
vec3  toys2D(vec2 p, vec3 base);    // 2D scenes: draws sluurp/rope/targets as glowing discs over base (uses xy of the uniforms)

// ---- Part B: additions (all with safe defaults) ----
// B.1 toy state
uniform vec4  uSluurpVel;      // xyz velocity (world u/s); w = impact squash 0..1 (1 = just hit, decays at 12 Hz). Default 0
uniform vec3  uSluurpEye;      // unit look direction of the iris (CPU-smoothed: nearest target within 4 u, else the camera).
                              // All-zero → helpers fall back to the nearest active target in-shader
uniform float uRopeTension;   // 0 slack … 1 max stretch (0.15 u). Rope colour cyan→magenta, brightness 0.6+1.2·t
uniform float uRopeFree;      // 1 during free flight (rope slack after a flick), decays τ 0.2 s after re-tether
uniform float uTargetLife[6]; // 1 fresh … 0 expiring (last 4 s of a target's gaze-gated life); 1 for immortal targets
uniform vec4  uToyBounds;     // xyz centre, w radius: sphere enclosing sluurp + rope + active targets.
vec3  toysEdge(vec2 uv);      // additive edge-of-frame eye-shine for off-screen targets. Scenes add it last: return col + toysEdge(uv);
// B.2 events and global pulses
uniform float uHitPulse;      // 1 on any hit, decays τ 0.18 s (post ripple; scenes may read it)
uniform float uHitFlash;      // 1 on any hit or room flash, decays τ 0.7 s
uniform float uTransition;    // −1→0 while this scene is the OUTGOING side of a crossfade, 0→1 while INCOMING, 0 when idle
uniform float uAttract;       // 0..1 attract-mode weight
uniform float uLookAway;      // 0..1 = smoothstep(cone, cone+20°, angle(gaze, +z)) for the scene's interestCone
// B.3 navigation and head
uniform float uSpeed;         // current flight speed in world u/s; 0 in HOVER / 2D
uniform float uTravel;        // distance flown since scene start (u); in the 2D room the gated dive distance (the dive itself is folded into uZoom)
uniform vec3  uGravity;       // real-world DOWN in scene coordinates; unit length. (0,−1,0) when head tracking is off
uniform vec3  uHeadRate;      // head angular rate (yaw, pitch, roll) rad/s, smoothed
uniform float uEyeSep;        // signed half-interaxial s for this eye (− left, + right; 0 when mono)
// B.4 depth output and rotation-exact reprojection
float gDepth = -1.0;          // GLOBAL. Scenes MAY set it to the hit distance t along rayDir (−1 = sky/2D). Written by footer.glsl.
uniform sampler2D uPrevDepth; // last frame's depth attachment (whole FBO)
uniform mat3  uPrevCamRotT;   // TRANSPOSE of last frame's uCamRot for this eye
uniform vec3  uPrevCamPos;    // last frame's per-eye camera position
uniform vec2  uPrevEyeOrigin, uPrevEyeRes;   // last frame's eye rect in the FBO (rung may have changed)
// Core additions (not in the contract; safe to ignore)
uniform int   uIs2D;          // 1 when the toy uniforms are per-eye PROJECTED (xy = eye-uv, z = depth, w = uv radius)
uniform vec4  uRopeGroups[3]; // bounding spheres of rope segments 0-3 / 4-7 / 8-10 (xyz centre, w radius; w <= 0 → no bound)

// ---- helper implementations ----
vec2 eyeUV() { return ((gl_FragCoord.xy - uEyeOrigin) - 0.5 * uEyeRes) / uEyeRes.y + vec2(uUvShift, 0.0); }
vec3 rayDir(vec2 uv) { return normalize(uCamRot * vec3(uv * uFov, 1.0)); }
vec3 pal(float t, vec3 a, vec3 b, vec3 c, vec3 d) { return a + b * cos(6.2831853 * (c * t + d)); }
mat2 rot2(float a) { float c = cos(a), s = sin(a); return mat2(c, s, -s, c); }
mat3 rotX(float a) { float c = cos(a), s = sin(a); return mat3(1.0, 0.0, 0.0,  0.0, c, s,  0.0, -s, c); }
mat3 rotY(float a) { float c = cos(a), s = sin(a); return mat3(c, 0.0, -s,  0.0, 1.0, 0.0,  s, 0.0, c); }
mat3 rotZ(float a) { float c = cos(a), s = sin(a); return mat3(c, s, 0.0,  -s, c, 0.0,  0.0, 0.0, 1.0); }
float hash11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
float hash21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float hash31(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }
float noise3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash31(i + vec3(0, 0, 0)), hash31(i + vec3(1, 0, 0)), f.x),
                   mix(hash31(i + vec3(0, 1, 0)), hash31(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash31(i + vec3(0, 0, 1)), hash31(i + vec3(1, 0, 1)), f.x),
                   mix(hash31(i + vec3(0, 1, 1)), hash31(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}
float sdSphere(vec3 p, float r) { return length(p) - r; }
float sdCapsule(vec3 p, vec3 a, vec3 b, float r) {
    vec3 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-8), 0.0, 1.0);
    return length(pa - ba * h) - r;
}
float smin(float a, float b, float k) { float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0); return mix(b, a, h) - k * h * (1.0 - h); }

// ---- toys: geometry ----
const float ROPE_R = 0.010;
const float TOY_PAD = 0.03;   // the CPU pads uToyBounds.w by this much beyond the tight sluurp+rope sphere
float toysDE(vec3 p, out int id) {
    id = 0; float d = 1e9;
    // uToyBounds encloses the sluurp + rope only (CORE_API.md deviation #3): outside it, db + TOY_PAD is a valid
    // lower bound for that group (never 0, so the marcher cannot "hit" the bounding sphere) and the
    // 12-primitive union is skipped; the six targets are tested individually.
    float db = length(p - uToyBounds.xyz) - uToyBounds.w;
    if (db > 0.0) d = db + TOY_PAD;
    else {
        // sluurp: squash & stretch ellipsoid (stretch along velocity, impact squash via uSluurpVel.w)
        if (uSluurp.w > 0.0) {
            vec3 q = p - uSluurp.xyz;
            float sp = length(uSluurpVel.xyz);
            float st = 1.0 + 0.35 * clamp(sp / 6.0, 0.0, 1.0);
            st = mix(st, 0.62, clamp(uSluurpVel.w, 0.0, 1.0));
            float sq = inversesqrt(st);
            vec3 vd = (sp > 1e-4) ? uSluurpVel.xyz / sp : vec3(0.0, 0.0, 1.0);
            float along = dot(q, vd); vec3 perp = q - along * vd;
            float dB = (length(perp / sq + vd * (along / st)) - uSluurp.w) * min(st, sq);
            if (dB < d) { d = dB; id = 1; }
        }
        // rope capsules, in three groups with their own bounding spheres (skip a group when outside its sphere)
        float rr = mix(ROPE_R, 0.007, uRopeFree);
        for (int g = 0; g < 3; g++) {
            int i0 = g * 4, i1 = min(i0 + 4, 11);
            if (i0 + 1 >= uRopeN) break;
            if (uRopeGroups[g].w > 0.0) {
                float dg = length(p - uRopeGroups[g].xyz) - uRopeGroups[g].w;
                if (dg > 0.0) { if (dg + TOY_PAD < d) d = dg + TOY_PAD; continue; }
            }
            for (int i = i0; i < i1; i++) {
                if (i + 1 >= uRopeN) break;
                float dr = sdCapsule(p, uRope[i].xyz, uRope[i + 1].xyz, rr);
                if (dr < d) { d = dr; id = 2; }
            }
        }
    }
    // targets: sphere, or an expanding shell while the hit pulse plays
    for (int i = 0; i < 6; i++) {
        float r = uTargets[i].w; if (r <= 0.0) continue;
        float pulse = uTargetPulse[i];
        float dt;
        if (pulse > 0.02) {
            float R = r * (1.0 + 3.0 * (1.0 - pulse));
            dt = abs(length(p - uTargets[i].xyz) - R) - 0.15 * r;
        } else {
            float life = uTargetLife[i];
            dt = length(p - uTargets[i].xyz) - r * (0.6 + 0.4 * life);
        }
        if (dt < d) { d = dt; id = 10 + i; }
    }
    return d;
}

// ---- toys: colour ----
vec3 targetTint(int i) {
    if (i == 5) return vec3(1.0, 0.85, 0.3);                       // golden eye
    return pal(uHue + 0.13 * float(i), vec3(0.55, 0.45, 0.75), vec3(0.45, 0.45, 0.25), vec3(1.0, 1.0, 2.0), vec3(0.0, 0.45, 0.55));
}
float blinkNow() {
    float slot = floor(uTime / 3.7); float ph = uTime - slot * 3.7;
    return (hash11(slot + 7.0) < 0.45 && ph < 0.14) ? 1.0 : 0.0;
}
vec3 toyColor(int id, vec3 p, vec3 n) {
    if (id == 1) {
        vec3 eye = uSluurpEye;
        if (dot(eye, eye) < 0.5) {
            float best = 1e9; vec3 tgt = uCamPos;
            for (int i = 0; i < 6; i++) if (uTargets[i].w > 0.0) {
                float dd = distance(uTargets[i].xyz, uSluurp.xyz); if (dd < best) { best = dd; tgt = uTargets[i].xyz; }
            }
            eye = tgt - uSluurp.xyz;
        }
        eye = normalize(eye);
        float ca = dot(n, eye);
        float irisR = 0.55 + 0.05 * uBass, pupilR = 0.90 - 0.10 * uEnergy + 0.06 * uSluurpGlow;
        float blink = blinkNow();
        vec3 sclera = vec3(0.10, 0.02, 0.22) * 1.3;
        float veins = smoothstep(0.72, 1.0, noise3(n * 8.0 + uTime * 0.25)) * 0.5;
        vec3 col = sclera + vec3(0.0, 0.55, 0.9) * veins;
        float iris = smoothstep(irisR - 0.04, irisR + 0.04, ca) * (1.0 - blink);
        float ringT = fract(ca * 6.0 + uTime * 0.3);
        vec3 irisCol = mix(vec3(0.05, 0.8, 1.0), vec3(1.0, 0.27, 0.9), 0.5 + 0.5 * sin(ca * 26.0 + uTime * 1.5 + ringT * 2.0));
        irisCol *= 0.8 + 0.4 * hash21(floor(vec2(atan(n.y, n.x), ca) * 24.0));
        col = mix(col, irisCol * (1.4 + 1.6 * uSluurpGlow), iris);
        float pupil = smoothstep(pupilR - 0.025, pupilR + 0.025, ca) * (1.0 - blink);
        col = mix(col, vec3(0.0), pupil);                               // black pupil = transparent hole
        vec3 vdir = normalize(uCamPos - p);
        float rim = pow(1.0 - clamp(abs(dot(n, vdir)), 0.0, 1.0), 2.5);
        col += vec3(0.3, 0.6, 1.0) * (0.25 + 0.6 * uSluurpGlow) * rim * 2.0;
        col += vec3(0.3, 0.6, 1.0) * 0.6 * uSluurpGlow * 0.3;
        return col;
    }
    if (id == 2) {
        vec3 c = mix(vec3(0.0, 0.9, 1.0), vec3(1.0, 0.27, 0.85), uRopeTension) * (0.6 + 1.2 * uRopeTension);
        c += vec3(1.0) * smoothstep(0.95, 1.0, uRopeTension) * 2.0;
        c = mix(c, vec3(0.55, 0.3, 1.0) * 0.35, uRopeFree);
        float run = fract(dot(p, vec3(7.0)) - uTime * 2.0);           // faint travelling shimmer
        return c * (0.85 + 0.3 * run);
    }
    if (id >= 10 && id < 16) {
        int i = id - 10;
        vec3 c = uTargets[i].xyz;
        float pulse = uTargetPulse[i], life = uTargetLife[i];
        vec3 tint = targetTint(i);
        if (pulse > 0.02) {
            float spark = step(0.90, hash21(floor(vec2(atan(n.z, n.x), n.y) * 12.0) + floor(uTime * 8.0)));
            return tint * (4.0 * pulse) * (0.6 + 0.8 * spark) + vec3(1.0) * spark * pulse;
        }
        vec3 look = normalize(uSluurp.xyz - c);
        float ca = dot(n, look);
        float iris = smoothstep(0.62, 0.70, ca), pupil = smoothstep(0.90, 0.94, ca);
        vec3 col = tint * 0.45;
        col = mix(col, mix(vec3(0.1, 0.9, 1.0), tint, 0.5 + 0.5 * sin(ca * 30.0 - uTime * 2.0)) * 1.8, iris);
        col = mix(col, vec3(0.0), pupil);
        vec3 vdir = normalize(uCamPos - p);
        col += tint * pow(1.0 - clamp(abs(dot(n, vdir)), 0.0, 1.0), 3.0) * 1.2;
        float flick = mix(1.0, 0.7 + 0.3 * step(0.5, fract(uTime * 2.0)), 1.0 - clamp(life, 0.0, 1.0));
        if (i == 5) col *= 1.2 + 0.4 * sin(uTime * 6.0);
        return col * flick * (1.0 + 0.3 * uBass);
    }
    return vec3(0.0);
}

// ---- toys: 2D (projected uniforms: xy = eye-uv, z = view depth, w = uv radius) ----
float sdSeg2(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a; float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-8), 0.0, 1.0);
    return length(pa - ba * h);
}
vec3 toys2D(vec2 uv, vec3 base) {
    vec3 col = base;
    // rope (never thinner than ~2.5 FBO pixels — thin lines vanish on the waveguide)
    float rr = max(mix(0.0045, 0.003, uRopeFree), 1.25 / max(uEyeRes.y, 1.0));
    vec3 ropeC = mix(vec3(0.0, 0.9, 1.0), vec3(1.0, 0.27, 0.85), uRopeTension) * (0.6 + 1.2 * uRopeTension);
    ropeC = mix(ropeC, vec3(0.55, 0.3, 1.0) * 0.35, uRopeFree);
    float dRope = 1e9;
    for (int i = 0; i < 11; i++) {
        if (i + 1 >= uRopeN) break;
        if (uRope[i].z <= 0.02 || uRope[i + 1].z <= 0.02) continue;
        dRope = min(dRope, sdSeg2(uv, uRope[i].xy, uRope[i + 1].xy));
    }
    float ropeA = smoothstep(rr + 0.002, rr - 0.001, dRope);
    col = mix(col, ropeC, ropeA);
    col += ropeC * 0.5 * exp(-max(dRope - rr, 0.0) * 90.0);
    // targets
    for (int i = 0; i < 6; i++) {
        float r = uTargets[i].w; if (r <= 0.0 || uTargets[i].z <= 0.02) continue;
        vec2 c = uTargets[i].xy; float d = length(uv - c);
        float pulse = uTargetPulse[i], life = uTargetLife[i];
        vec3 tint = targetTint(i);
        if (pulse > 0.02) {
            float R = r * (1.0 + 3.0 * (1.0 - pulse));
            float ring = exp(-pow((d - R) / (0.25 * r + 1e-4), 2.0));
            col += tint * ring * 4.0 * pulse;
            continue;
        }
        float rl = r * (0.6 + 0.4 * life);
        float disc = smoothstep(rl, rl * 0.85, d);
        vec2 look = normalize(uSluurp.xy - c + vec2(1e-5));
        float q = dot(normalize(uv - c + vec2(1e-6)), look) * clamp(d / rl, 0.0, 1.0);
        vec2 irisC = c + look * rl * 0.25; float di = length(uv - irisC);
        float iris = smoothstep(rl * 0.62, rl * 0.55, di), pupil = smoothstep(rl * 0.3, rl * 0.25, di);
        vec3 tc = mix(tint * 0.5, mix(vec3(0.1, 0.9, 1.0), tint, 0.5 + 0.5 * sin(q * 12.0 - uTime * 2.0)) * 1.8, iris);
        tc = mix(tc, vec3(0.0), pupil);
        float flick = mix(1.0, 0.7 + 0.3 * step(0.5, fract(uTime * 2.0)), 1.0 - clamp(life, 0.0, 1.0));
        if (i == 5) tc *= 1.2 + 0.4 * sin(uTime * 6.0);
        col = mix(col, tc * flick, disc);
        col += tint * 0.6 * exp(-max(d - rl, 0.0) * 40.0) * flick;
    }
    // sluurp
    if (uSluurp.w > 0.0 && uSluurp.z > 0.02) {
        vec2 c = uSluurp.xy; float r = uSluurp.w * (1.0 + 0.04 * sin(uTime * 1.57) + 0.06 * uBass);
        float d = length(uv - c);
        float disc = smoothstep(r, r * 0.9, d);
        float blink = blinkNow();
        vec2 look; float best = 1e9; look = normalize(-c + vec2(1e-5));
        for (int i = 0; i < 6; i++) if (uTargets[i].w > 0.0 && uTargets[i].z > 0.02) {
            float dd = distance(uTargets[i].xy, c); if (dd < best) { best = dd; look = normalize(uTargets[i].xy - c + vec2(1e-6)); }
        }
        vec2 irisC = c + look * r * 0.3; float di = length(uv - irisC);
        float irisR = r * (0.55 + 0.05 * uBass), pupilR = r * (0.25 + 0.06 * uSluurpGlow);
        vec3 sclera = vec3(0.10, 0.02, 0.22) * 1.3 + vec3(0.0, 0.55, 0.9) * smoothstep(0.72, 1.0, noise3(vec3(uv * 40.0, uTime * 0.25))) * 0.5;
        vec3 irisCol = mix(vec3(0.05, 0.8, 1.0), vec3(1.0, 0.27, 0.9), 0.5 + 0.5 * sin(di / r * 20.0 + uTime * 1.5)) * (1.4 + 1.6 * uSluurpGlow);
        vec3 bc = mix(sclera, irisCol, smoothstep(irisR, irisR * 0.9, di) * (1.0 - blink));
        bc = mix(bc, vec3(0.0), smoothstep(pupilR, pupilR * 0.85, di) * (1.0 - blink));
        float rim = smoothstep(r * 0.75, r, d);
        bc += vec3(0.3, 0.6, 1.0) * rim * (0.6 + 1.2 * uSluurpGlow);
        col = mix(col, bc, disc);
        col += vec3(0.3, 0.6, 1.0) * (0.4 + 0.8 * uSluurpGlow) * exp(-max(d - r, 0.0) * 30.0);
    }
    return col;
}

// ---- edge-of-frame eye-shine for off-screen targets ----
vec3 toysEdge(vec2 uv) {
    vec3 acc = vec3(0.0);
    float aspect = uEyeRes.x / max(uEyeRes.y, 1.0);
    vec2 half_ = vec2(0.5 * aspect, 0.5) - 0.07;
    vec2 g = uv - vec2(uUvShift, 0.0);                       // geometric uv (screen-centred)
    for (int i = 0; i < 6; i++) {
        if (uTargets[i].w <= 0.0) continue;
        vec2 tuv; float depth; vec2 dir;
        if (uIs2D == 1) {
            tuv = uTargets[i].xy - vec2(uUvShift, 0.0); depth = uTargets[i].z;
            dir = (depth > 0.0) ? tuv : -tuv;
        } else {
            vec3 d = transpose(uCamRot) * (uTargets[i].xyz - uCamPos);
            depth = d.z;
            tuv = d.xy / (max(abs(d.z), 1e-3) * uFov);
            dir = (depth > 0.0) ? tuv : -tuv;
        }
        bool off = (depth <= 0.05) || (abs(tuv.x) > half_.x) || (abs(tuv.y) > half_.y);
        if (!off) continue;
        if (dot(dir, dir) < 1e-6) dir = vec2(0.0, -1.0);
        float s = min(half_.x / max(abs(dir.x), 1e-4), half_.y / max(abs(dir.y), 1e-4));
        vec2 e = dir * s;                                        // clamped to the frame edge
        vec2 inward = -normalize(dir);
        float dd = length(g - e);
        float blob = exp(-dd * dd / (0.05 * 0.05));
        vec2 rel = g - e; float ang = acos(clamp(dot(normalize(rel + vec2(1e-6)), inward), -1.0, 1.0));
        float arc = exp(-pow((length(rel) - 0.09) / 0.012, 2.0)) * step(ang, 1.05) * step(0.02, length(rel));
        arc *= 0.6 + 0.4 * step(0.5, fract(ang * 3.0 / 1.05));  // 6-segment feel
        vec3 tint = (i == 5) ? vec3(1.0, 0.85, 0.3) * (1.0 + 0.5 * sin(uTime * 6.0)) : targetTint(i);
        acc += tint * (0.35 + 0.35 * uBass) * (blob + 0.7 * arc);
    }
    return acc;
}

// ---- rotation-exact reprojection (B.4). Sign convention: uUvShift is + for the left eye, − for the right,
// so that the anchor (D_c = 1.4 u) lands at geometric uv.x = 0 in both eyes (CORE_API.md deviation #1).
// uvGeom = eyeUV() - vec2(uUvShift, 0.0). Returns the previous frame's GEOMETRIC uv (same space as uvGeom).
vec2 reprojectPrev(vec2 uvGeom) {
    vec3 dw = uCamRot * vec3((uvGeom + vec2(uUvShift, 0.0)) * uFov, 1.0);
    vec3 dp = uPrevCamRotT * dw;                       // into the previous camera frame
    if (dp.z <= 0.05) return vec2(-10.0);              // behind the previous camera → invalid
    return dp.xy / (dp.z * uFov) - vec2(uUvShift, 0.0);
}
float startT(vec2 uvGeom, vec3 ro, vec3 rd) {        // conservative ray start from last frame's depth; 0 when unusable
    vec2 uvp = reprojectPrev(uvGeom);
    if (abs(uvp.x) > 0.66 || abs(uvp.y) > 0.5) return 0.0;
    vec2 tc = (uvp * uPrevEyeRes.y + 0.5 * uPrevEyeRes + uPrevEyeOrigin) / vec2(textureSize(uPrevDepth, 0));
    float tp = texture(uPrevDepth, tc).r;
    if (tp <= 0.0) return 0.0;
    vec3 dirPrev = normalize(transpose(uPrevCamRotT) * vec3((uvp + vec2(uUvShift, 0.0)) * uFov, 1.0));
    vec3 p = uPrevCamPos + tp * dirPrev;               // world point hit last frame
    return max(dot(p - ro, rd) * 0.9, 0.0);            // 10 % margin for translation; scenes with flight use ×0.85 more
}
// =====================================================================================
// scene code follows (must define: vec3 render(vec2 uv))
// =====================================================================================
