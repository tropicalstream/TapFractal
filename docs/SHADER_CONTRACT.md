# TapFractal — Shader Contract

Part A is the SPEC's contract **verbatim** (frozen; scene authors and the core work in parallel against it).
Part B lists the **additions** this design needs — new uniforms, globals and helpers only. Nothing in Part A
is renamed, retyped or removed; every addition has a safe default so a scene that ignores it loses nothing.
Part C records semantic clarifications (comments, not code changes) and core-owned post-pass notes.

---

## Part A — SPEC contract (verbatim)

## Shader contract (fixed so scene authors and the core can work in parallel)
The app prepends `assets/shaders/common.glsl` to every `assets/shaders/sceneN.frag` and appends `assets/shaders/footer.glsl`. Scene files define ONLY `vec3 render(vec2 uv)` (linear RGB, may exceed 1.0) plus their own helpers. Provided by common.glsl:
```
#version 300 es
precision highp float; precision highp int;
uniform vec2  uEyeRes;      // per-eye viewport size in the FBO (e.g. 320×240)
uniform vec2  uEyeOrigin;   // viewport origin in the FBO
uniform int   uEye;         // 0 left, 1 right
uniform float uTime, uDt;   // seconds since scene start, frame dt
uniform vec3  uCamPos;      // per-eye camera position (stereo offset already applied)
uniform mat3  uCamRot;      // columns: right, up, forward
uniform float uUvShift;     // per-eye horizontal uv shift for convergence
uniform float uFov;         // tan(half vertical fov)
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
vec2 eyeUV();               // ((gl_FragCoord.xy-uEyeOrigin) - 0.5*uEyeRes)/uEyeRes.y + vec2(uUvShift,0)
vec3 rayDir(vec2 uv);       // normalize(uCamRot * vec3(uv*uFov, 1.0))
vec3 pal(float t, vec3 a, vec3 b, vec3 c, vec3 d); // IQ cosine palette
mat2 rot2(float a); mat3 rotX/rotY/rotZ(float a);
float hash11(float), hash21(vec2); float noise3(vec3); // cheap noise
float sdSphere(vec3 p, float r); float sdCapsule(vec3 p, vec3 a, vec3 b, float r); float smin(float a, float b, float k);
float toysDE(vec3 p, out int id);   // union of sluurp (id 1), rope capsules (id 2), targets (id 10+i) — raymarch scenes min() this into their DE
vec3  toyColor(int id, vec3 p, vec3 n); // emissive colour for those ids (uses uSluurpGlow / uTargetPulse)
vec3  toys2D(vec2 p, vec3 base);    // 2D scenes: draws sluurp/rope/targets as glowing discs over base (uses xy of the uniforms)
```
footer.glsl: `void main(){ vec3 c = render(eyeUV()); fragColor = vec4(max(c,0.0)*uFade, 1.0); }` (tone-mapping happens in post).
Post pass `assets/shaders/post.frag`: samples the scene FBO + previous echo FBO; bloom (5-tap), chromatic aberration `0.002 + 0.01*uEnergy`, vignette, tone-map `c/(1+c)` then gamma 1/2.2, echo mix; writes to the screen viewport for each eye.

Kotlin side: `Scene` base class per slot in `scenes/` (name, frag asset, music asset, palette params, `de(p)` CPU mirror of the shader DE (or inside-test for 2D), `cruiseSpeed` per level, `is2D`, per-zoom-level parameter mapping, spawn cone). Scene authors may edit ONLY their own `scenes/<Name>.kt` and `assets/shaders/scene<N>.frag`.

---

## Part B — Additions (additive only; all declared in `common.glsl` after the Part A block)

### B.1 Toy state (producer: core toy physics; consumers: `toysDE/toyColor/toys2D` and any scene)
```glsl
uniform vec4  uSluurpVel;      // xyz velocity (world u/s); w = impact squash 0..1 (1 = just hit, decays at 12 Hz). Default 0
uniform vec3  uSluurpEye;      // unit look direction of the iris (CPU-smoothed: nearest target within 4 u, else the camera).
                              // All-zero → helpers fall back to the nearest active target in-shader
uniform float uRopeTension;   // 0 slack … 1 max stretch (0.15 u). Rope colour cyan→magenta, brightness 0.6+1.2·t
uniform float uRopeFree;      // 1 during free flight (rope slack after a flick), decays τ 0.2 s after re-tether
uniform float uTargetLife[6]; // 1 fresh … 0 expiring (last 4 s of a target's gaze-gated life); 1 for immortal targets
uniform vec4  uToyBounds;     // xyz centre, w radius: sphere enclosing sluurp + rope + active targets.
                              // toysDE() begins: float db = length(p - uToyBounds.xyz) - uToyBounds.w; if (db > 0.0) { id = 0; return db; }
vec3  toysEdge(vec2 uv);      // additive edge-of-frame eye-shine for off-screen targets (uv radius 0.05 blob + 6-segment arc,
                              // brightness 0.35 + 0.35·uBass, gold + pulsing for the golden eye). Scenes add it last: return col + toysEdge(uv);
```
2D scenes: for `is2D` the core uploads **projected** toy uniforms per eye — `uSluurp/uAnchor/uRope[i]/uTargets[i]`
`.xy` = eye-uv (same space as `eyeUV()`), `.z` = view depth, `.w` = projected radius in uv — so `toys2D()` and
scene code can use `xy` directly (e.g. the Tidepool's `uSluurp.xy − uAnchor.xy` Julia offset).

### B.2 Events and global pulses (producer: core)
```glsl
uniform float uHitPulse;      // 1 on any hit, decays τ 0.18 s. Post: bloom ×(1+0.6p), aberration +0.008p, echo mix +0.06p (cap 0.92),
                              // echo zoom one notch while p > 0.3, vignette −10 %. Scenes may read it for a fast ripple
uniform float uHitFlash;      // 1 on any hit or room flash, decays τ 0.7 s. Scene reactions: Hive flashbulb (lamp R += 4·f),
                              // Nave power kick (+0.5·f), Tidepool ring radius (+0.3·f), Throat hit ring; also set to 1 by the
                              // Throat→Hive transition
uniform float uTransition;    // −1→0 while this scene is the OUTGOING side of a crossfade, 0→1 while INCOMING, 0 when idle
uniform float uAttract;       // 0..1 attract-mode weight (six targets allowed, auto-flight; dim HUD-ish elements)
uniform float uLookAway;      // 0..1 = smoothstep(cone, cone+20°, angle(gaze, +z)) for the scene's interestCone; Nave backdrop weight
```

### B.3 Navigation and head (producer: core flight controller / head tracker)
```glsl
uniform float uSpeed;         // current flight speed in world u/s (after scene speedScale and DE hover slowdown); 0 in HOVER / 2D
uniform float uTravel;        // distance flown since scene start (u); in the 2D room the gated dive distance (the dive itself is folded into uZoom)
uniform vec3  uGravity;       // real-world DOWN in scene coordinates (W·(0,−1,0)); unit length. Identity-frame (0,−1,0) when head tracking is off
uniform vec3  uHeadRate;      // head angular rate (yaw, pitch, roll) rad/s, smoothed; optional motion-adaptive detail
uniform float uEyeSep;        // signed half-interaxial s for this eye (− left, + right; 0 when mono) — eye-consistent noise/specular seeds
```

### B.4 Depth output and rotation-exact reprojection (producer: core; scenes MAY participate)
```glsl
float gDepth = -1.0;          // GLOBAL, not a uniform. Scenes MAY set it to the hit distance t along rayDir (−1 = sky/2D).
                              // footer.glsl writes it: layout(location = 1) out float outDepth; … outDepth = gDepth;  (R16F attachment)
uniform sampler2D uPrevDepth; // last frame's depth attachment (whole FBO)
uniform mat3  uPrevCamRotT;   // TRANSPOSE of last frame's uCamRot for this eye (CPU transposes once per frame)
uniform vec3  uPrevCamPos;    // last frame's per-eye camera position
uniform vec2  uPrevEyeOrigin, uPrevEyeRes;   // last frame's eye rect in the FBO (rung may have changed)

// uvGeom = eyeUV() - vec2(uUvShift, 0.0)   (geometric uv without the convergence shift)
vec2 reprojectPrev(vec2 uvGeom) {
    vec3 dw = uCamRot * vec3(uvGeom * uFov, 1.0);
    vec3 dp = uPrevCamRotT * dw;                       // into the previous camera frame
    if (dp.z <= 0.05) return vec2(-10.0);              // behind the previous camera → invalid
    return dp.xy / (dp.z * uFov) + vec2(uUvShift, 0.0);
}
float startT(vec2 uvGeom, vec3 ro, vec3 rd) {        // conservative ray start from last frame's depth; 0 when unusable
    vec2 uvp = reprojectPrev(uvGeom);
    if (abs(uvp.x) > 0.66 || abs(uvp.y) > 0.5) return 0.0;
    vec2 tc = (uvp * uPrevEyeRes.y + 0.5 * uPrevEyeRes + uPrevEyeOrigin) / vec2(textureSize(uPrevDepth, 0));
    float tp = texture(uPrevDepth, tc).r;
    if (tp <= 0.0) return 0.0;
    vec3 dirPrev = normalize(transpose(uPrevCamRotT) * vec3((uvp - vec2(uUvShift, 0.0)) * uFov, 1.0));
    vec3 p = uPrevCamPos + tp * dirPrev;               // world point hit last frame
    return max(dot(p - ro, rd) * 0.9, 0.0);            // 10 % margin for translation; scenes with flight use ×0.85 more
}
```
Rule for scenes using `startT`: after computing `t`, verify `map(ro + rd·t) > eps` (or your DE) and restart at
`t = 0` if not — the helper is a hint, not a guarantee. The same `reprojectPrev` is used by the post pass for
world-locked echo trails.

### B.5 Post-pass uniforms (core-owned `post.frag`; listed so the Scene → core data flow is explicit)
```glsl
uniform int   uSceneId;                 // 0..3 (per-scene branches)
uniform float uEchoMix, uEchoZoom, uEchoRot;   // from Scene.echo × Echo setting; frame-rate normalised by the core; mix ≤ 0.90 (0.92 in hit bump)
uniform vec2  uEchoCentre;              // per-eye centre of the echo zoom/rotate (default (0,0), plus (uUvShift,0) added in-shader)
uniform vec3  uEchoTint;                // hue-rotated scene palette sample, normalised to max 1
uniform vec3  uBloomTint; uniform float uBloomStrength;   // per scene (§8 table): default (1,1,1), 0.35
uniform vec3  uCoreTint;                // core tint for the hue-preserving tone-map (§7.1); default (1,1,1)
uniform float uAberr;                   // 0.002 + 0.01·uEnergy + 0.004·uBeat + 0.008·uHitPulse
uniform float uBreath;                  // 0.012·sin(2π·0.08·t) sub-perceptual scale
uniform float uExposure;                // APL servo output, 1.0 unless the servo is enabled (bounded 0.5..1.5)
uniform int   uPrism;                   // 1 when Echo = Wild: 3-direction (120°) chromatic aberration
```

### B.6 Kotlin `Scene` additions (members only; nothing renamed)
`speedScale`, `maxRung`, `interestConeDeg`, `chimeRootHz`, `palette` (a, b, c, d0, zshift, coreTint, bloomTint,
bloomStrength), `echo` (soft/wild τ, zoom, rot, centre), `startPose()`, `current(p, t)`, `autoFlight(t, pos)`,
`field(p)`, `safeVolume(cam)`, `grad(p, zoom)` (core-provided 4-tap of `de`), `onLevelChanged(level)`. The spec's
`cruiseSpeed` per level is realised as the global level table × `speedScale`.

---

## Part C — Clarifications (comments and core notes; no code change to Part A)

C1. **`uFov` semantics.** `eyeUV()` spans `uv.y ∈ [−0.5, +0.5]`, so the tangent at the top edge is `0.5·uFov`:
    **`uFov = 2·tan(halfVerticalFov)`**. The Part A comment "tan(half vertical fov)" is corrected in
    `common.glsl`'s comment only. Default `uFov = 0.35` (19.9° V × 26.3° H).

C2. **World frame.** x = right, y = up (gravity-true), z = straight ahead at the last recentre (left-handed);
    `uCamRot` columns (right, up, forward) satisfy `cross(right, up) = forward`, `det = +1`. Convergence plane
    (anchor) at 1.4 u; `uUvShift = ± s/(1.4·uFov)`.

C3. **2D scenes reinterpret `uCamPos`**: `xy` = plane pan, `z` = plane rotation (radians). `uCamRot` is still the
    head rotation (used by the core to project the toys; scenes need not read it). The plane is sampled at
    `eyeUV()` (which includes `uUvShift`), placing it behind the zero-parallax toys.

C4. **`uBeat`** is onset-gated: at most one pulse per 340 ms, decay τ 0.18 s.

C5. **Post tone-map** is luminance-Reinhard with core tint (`m = c/(1+lum)`, cores mixed toward `uCoreTint`),
    superseding the per-channel `c/(1+c)` wording in Part A. Both map 0 → 0. `post.frag` is core-owned; scenes
    are unaffected.

C6. **Echo** is frame-rate normalised (`mix = exp(−dt/τ)`), world-locked via `reprojectPrev`, speed-gated on head
    rate, clamped at 2.0 before mixing, and the echo buffer persists across scene crossfades.

C7. **`toysDE` cost**: begins with the `uToyBounds` early-out (B.1). Scenes keep calling it exactly as in Part A.
