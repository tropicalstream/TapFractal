// scene2.frag — The Nave (MANDELBULB CATHEDRAL), DESIGN §9.2.
// Raymarched y-up power-8 Mandelbulb. Mirrored on the CPU by scenes/Nave.kt: same map, same tables in
// the continuous zoom (base iters clamp(6 + 0.25·zoom, 6, 8) + 2 near the surface — see the dive pass
// below, twist 0.15·max(zoom − 4, 0) as a rotY per iteration, swell 1 + 0.035·min(zoom, 8)), same
// coordinate frame, IQ azimuth convention (the trig-free power-8
// polynomial IS the trig map at power 8: verified to 3e-11 relative). Music / hit terms are visual only.
//
// Room-tuner pass (2026-09-03), measured on the X3 Pro at a pinned front-on pose, rung 5 stereo:
// the trig DE (acos/atan/pow/sin/cos per iteration, power 8 + 0.25·L) cost 79 ms; the polynomial
// power-8 map costs 40 ms for the same picture, so the level growth moved from power to
// iterations + twist + a per-level swell (the cathedral grows a step on every hit) and the beat/hit
// power flex became a scale flex. Over-relaxed sphere tracing and 4-iteration far-field bounds were
// both measured SLOWER on this GPU (89–96 ms) and are not used. Other levers: the depth-reprojected
// ray start (startT), the bounding-sphere DE bound outside |p| > 1.55·swell, bulb-only normal taps,
// traps taken from the hit evaluation, a DE-based AO (stable under reprojection), a 1.2-px hit
// threshold and a 32–56 step cap whose leftovers (≤ 0.03 u) are shaded as dark crevices instead of holes.
//
// Waveguide colour: the stone palette is teal/cyan/blue only (R ≤ 0.08 at every phase, so no level or
// hue drift can reach yellow); gold is reserved for the seams, the dense recess haze and the hot
// cores and is always SATURATED and bright (dim gold = olive/mustard on the waveguide — the trap the
// previous palette fell into). The haze is integrated from the BULB DE only (the old term integrated
// map(), i.e. the toy bounding sphere, and painted olive rings around the sluurp in open space) and
// ramps teal → cyan → lime → gold with density, never through dim yellow. Two-tap DE AO darkens the
// crevices, the sluurp is an electric-blue lantern with a wrapped rim term, and the treble motes are
// round cell-jittered lime spots ≥ 3 FBO px (the old floor()-grid motes were square blocks up close).
//
// Dive pass (2026-09-03, owner: "more travelling INTO the fractal"): uZoom is now CONTINUOUS (hit levels
// + the core's travel dive, Nave.diveRate ≈ 1 level per 12 s at CRUISE), so every zoom term below is a
// smooth function of it. The DE gains a PROXIMITY REFINEMENT: the map runs gIterBase iterations
// (6 + 0.25·zoom, cap 8 — slower than the old 6 + 0.5·zoom table because the dive makes zoom 6 routine
// and the old table cost 1.7× there) and, only where that coarse estimate is within gRefineT of the
// surface, continues to gIterMax = base + 2 — the coarse set contains the fine set, so the coarse DE is
// a valid lower bound everywhere and the extra iterations are paid only by the last steps of a ray, the
// normal / AO taps and the flight when it is actually in a crevice. Crevices resolve as you enter them,
// the far field costs what it did. gRefineT shrinks with the dive (0.10·0.85^zoom, floor 0.025) and
// stays above the flight skin (Nave.skinAt = 0.20·0.60^zoom, floor 0.03), so the flight always collides
// with the surface you see. (Measured on the mirror: the bulb's canyons are only ~0.1 u wide — nothing
// inside |p| < 1.1 clears 0.12 u at any zoom — so the skin has to reach ~0.03 for the camera to get in;
// at that skin 19 % of the inner volume is open at zoom 4, 11 % at 6, 5 % at 8–10.) The DE also uses
// the CONSISTENT (r, dr) pair (r = |z| after the last step, IQ's form) instead of the old r = |z|
// before the last step, which under-estimated the distance by r^6/8 (3–8×) and burnt the step budget
// into "crevice" leftovers. Music/hit flex is scaled by 0.60^zoom so the surface never breathes
// further than the shrinking skin. Fog thickens with the dive (k = 0.16 + 0.03·zoom) so depth stays
// legible at small scales. Vibrancy: the fresnel rim and the thin haze are pure cyan, seams go pure
// gold sooner, the deep shadow carries a violet trace. Nave.kt mirrors all of this: iteration table,
// refinement rule, threshold, swell, consistent pair.

const vec3 PA = vec3(0.04, 0.42, 0.52), PB = vec3(0.04, 0.30, 0.38), PC = vec3(1.0), PD = vec3(0.00, 0.10, 0.25);
const vec3 ZSH  = vec3(0.00, 0.03, 0.02);         // level shift rotates teal ↔ blue only
const vec3 GOLD = vec3(1.0, 0.88, 0.12);          // hue 52° — inside the allowed gold band (46°–58°), B very low
const vec3 LIME = vec3(0.55, 1.0, 0.20);          // the green → gold crossing passes through lime, never orange
const vec3 MOTE = vec3(0.85, 1.0, 0.30);          // hue 73° lime: a 3-px twinkle has no red for the post CA to fringe orange
const vec3 CYAN = vec3(0.00, 0.90, 1.00);         // fresnel rim on the ridges: fully saturated cyan
const vec3 LANT = vec3(0.35, 0.75, 1.0);          // sluurp lantern (electric blue, like the sluurp's own rim)
const vec3 HAZE = vec3(0.00, 0.70, 1.00);         // thin haze: saturated cyan (brightness comes from density, not desaturation)
const vec3 VIOL = vec3(0.30, 0.05, 0.75);         // violet trace in the deep shadow (colour contrast against the teal stone)
const float REFINE_EXTRA = 2.0;                   // iterations added near the surface (mirror: Nave.REFINE_EXTRA)
const float ITER_CAP     = 10.0;                  // 8 + REFINE_EXTRA (mirror: Nave.ITER_CAP; the loop below is bounded by it)
const vec3 KEY  = vec3(0.4240, 0.8480, -0.3180);  // normalize(vec3(0.4, 0.8, -0.3)), fixed world key light
const float BULB_R    = 1.25;                     // bounding sphere of the bulb at swell 1 (max extent ≈ 1.2 incl. breath)
const float BULB_SKIP = 1.55;                     // outside this radius (× swell) the DE is the cheap sphere bound
const float FAR_T     = 12.0;

// ---- per-frame parameters (set once in render) ----
float gTwistC, gTwistS, gScale, gSlide, gRefineT; int gIterBase, gIterMax;
// ---- orbit traps written by the last bulbDE() call ----
float gTrapR, gTrapPlane, gTrapAxis;

// Power-8 Mandelbulb DE = 0.5·ln r·r/dr on the trig-free polynomial map (IQ), polar axis y, azimuth
// atan(x, z), twist = rotY per iteration applied to the new iterate before + p (same matrix as
// common.glsl rotY: M·v = (c·x + s·z, y, −s·x + c·z)). gScale = per-level swell × music/hit flex.
// (r, dr) are the consistent pair after the last executed step. Proximity refinement: after gIterBase
// iterations the coarse estimate is returned unless it is within gRefineT of the surface, in which case
// the loop continues to gIterMax (Nave.kt applies the identical rule with the identical constants).
// Traps: radius (before the step), |z.y| plane (gold seams), |z.xz| axis (hue) after the step.
float bulbDE(vec3 p) {
    p /= gScale;
    vec3 z = p; float dr = 1.0, r = max(length(z), 1e-6);
    gTrapR = 1e9; gTrapPlane = 1e9; gTrapAxis = 1e9;
    for (int i = 0; i < 10; i++) {
        if (i >= gIterMax || r > 4.0) break;
        if (i == gIterBase && 0.5 * log(r) * r / dr * gScale > gRefineT) break;   // far from the coarse set: coarse is enough
        gTrapR = min(gTrapR, r);
        float x = z.x, x2 = x * x, x4 = x2 * x2;
        float y = z.y, y2 = y * y, y4 = y2 * y2;
        float w = z.z, w2 = w * w, w4 = w2 * w2;
        float k3 = max(x2 + w2, 1e-12);
        float k2 = inversesqrt(k3 * k3 * k3 * k3 * k3 * k3 * k3);
        float k1 = x4 + y4 + w4 - 6.0 * y2 * w2 - 6.0 * x2 * y2 + 2.0 * w2 * x2;
        float k4 = x2 - y2 + w2;
        float r2 = r * r, r4 = r2 * r2;
        dr = 8.0 * r4 * r2 * r * dr + 1.0;
        vec3 n = vec3(64.0 * x * y * w * (x2 - w2) * k4 * (x4 - 6.0 * x2 * w2 + w4) * k1 * k2,
                      -16.0 * y2 * k3 * k4 * k4 + k1 * k1,
                      -8.0 * y * k4 * (x4 * x4 - 28.0 * x4 * x2 * w2 + 70.0 * x4 * w4 - 28.0 * x2 * w2 * w4 + w4 * w4) * k1 * k2);
        z = vec3(gTwistC * n.x + gTwistS * n.z, n.y, -gTwistS * n.x + gTwistC * n.z) + p;
        r = max(length(z), 1e-6);
        gTrapPlane = min(gTrapPlane, abs(z.y));
        gTrapAxis  = min(gTrapAxis, length(z.xz));
    }
    return 0.5 * log(r) * r / dr * gScale;
}

// Scene DE: bounding-sphere bound outside the bulb (valid lower bound, ≥ 0.30·swell so it never registers
// as a hit and rays that miss the bulb never pay the iterations), bulb DE inside.
float sceneDE(vec3 p) {
    p.z -= gSlide;                                      // incoming crossfade: the bulb slides in from further back
    float l = length(p);
    if (l > BULB_SKIP * gScale) return l - BULB_R * gScale;
    return bulbDE(p);
}
float map(vec3 p, out int id) {
    float d = sceneDE(p); id = 0;
    int tid; float dt = toysDE(p, tid);
    if (dt < d) { d = dt; id = tid; }
    return d;
}
vec3 normalScene(vec3 p, float e) {
    const vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * sceneDE(p + k.xyy * e) + k.yyx * sceneDE(p + k.yyx * e)
                   + k.yxy * sceneDE(p + k.yxy * e) + k.xxx * sceneDE(p + k.xxx * e));
}
vec3 normalToy(vec3 p, float e) {
    const vec2 k = vec2(1.0, -1.0); int id;
    return normalize(k.xyy * toysDE(p + k.xyy * e, id) + k.yyx * toysDE(p + k.yyx * e, id)
                   + k.yxy * toysDE(p + k.yxy * e, id) + k.xxx * toysDE(p + k.xxx * e, id));
}
vec3 boost(vec3 c) { float l = dot(c, vec3(0.2126, 0.7152, 0.0722)); return max(l + (c - l) * 1.35, 0.0); }

// Haze colour by density: thin = saturated cyan, dense = lime → gold (never dim yellow; gold only when it is bright).
vec3 hazeColor(float a) {
    if (a < 0.45) return mix(HAZE, CYAN, a * 2.222);
    if (a < 0.75) return mix(CYAN, LIME, (a - 0.45) * 3.333);
    return mix(LIME, GOLD, clamp((a - 0.75) * 4.0, 0.0, 1.0));
}

vec3 render(vec2 uv) {
    // ---- zoom parameters (CONTINUOUS in uZoom — hit levels + travel dive; mirrored by Nave.kt) ----
    float zc = min(uZoom, 12.0);
    float twist = 0.15 * max(zc - 4.0, 0.0);
    gIterBase = int(clamp(6.0 + 0.25 * zc, 6.0, 8.0));   // far field: 6 (zoom < 4), 7 (4–7), 8 (8+) — the dive makes zoom 6 routine
    gIterMax  = int(min(float(gIterBase) + REFINE_EXTRA, ITER_CAP));
    gRefineT  = max(0.10 * pow(0.85, zc), 0.025);
    float swell = 1.0 + 0.035 * min(zc, 8.0);
    // flex (visual only, the mirror uses the swell alone): bass breath 2.5 %, beat kick 1.2 %, hit ring 3 %,
    // slow 0.5 % sway, all × 0.60^zoom — the flight skin (Nave.skinAt = 0.20·0.60^zoom, floor 0.03) shrinks
    // at the same rate: at zoom 0 the beat moves the surface ≤ 0.018 u and everything together ≤ 0.09 u under
    // the 0.20 u skin; at zoom 2 ≤ 0.039 u under 0.072; at zoom 4 ≤ 0.013 u under 0.03.
    float flexK = pow(0.60, zc);
    gScale  = swell * (1.0 + flexK * (0.025 * uBass + 0.012 * uBeat + 0.03 * uHitFlash + 0.005 * sin(uTime * 0.11)));
    gTwistC = cos(twist); gTwistS = sin(twist);
    // ---- crossfade behaviour (§9.5): incoming = the bulb fades in from 0.4 u further back;
    //      outgoing = fog k ×2 by the end of the fade ("the bulb closes in") ----
    float incoming = clamp(uTransition, 0.0, 1.0);
    float outgoing = (uTransition < 0.0) ? clamp(1.0 + uTransition, 0.0, 1.0) : 0.0;
    gSlide = 0.4 * (1.0 - incoming) * (1.0 - incoming) * step(0.001, incoming);

    vec3 ro = uCamPos, rd = rayDir(uv);
    vec2 uvGeom = uv - vec2(uUvShift, 0.0);
    float pxAng = uFov / max(uEyeRes.y, 1.0);           // tangent subtended by one FBO pixel

    // ---- ray start: last frame's depth (rotation-exact), ×0.85 for flight, then verify or restart ----
    float t = startT(uvGeom, ro, rd) * 0.85;
    {
        int id0; float d0 = map(ro + rd * t, id0);
        if (d0 < 0.002) {
            if (t > 0.0) d0 = map(ro, id0);
            // d0 < 0.002 at the eye itself = the music/hit flex swallowed the 0.20 u flight skin
            // for a moment: skip the thin shell instead of painting a full-frame flat field.
            t = (d0 < 0.002) ? 0.3 : 0.0;
        }
    }

    // ---- march (the haze integrates the BULB distance only — the toys must not cast it) ----
    float maxSteps = floor(mix(32.0, 56.0, uQuality));
    float glowK = (0.7 + 0.6 * uEnergy) * (1.0 + 0.3 * uBeat) * (1.0 + 0.3 * uAttract);
    float glow = 0.0; int id = 0; bool hit = false, crevice = false; float lastD = 1e9;
    for (int i = 0; i < 56; i++) {
        if (float(i) >= maxSteps) break;
        vec3 q = ro + rd * t;
        float ds = sceneDE(q);
        int tid; float dt = toysDE(q, tid);
        float d = ds; id = 0;
        if (dt < ds) { d = dt; id = tid; }
        if (d < 0.0006 + 1.2 * pxAng * t) { hit = true; break; }
        glow += exp(-ds * 40.0) * min(ds, 0.05) * glowK;     // recess haze: density integrated along the ray
        lastD = d;
        t += d * 0.85;
        if (t > FAR_T) break;
    }
    if (!hit && t < FAR_T && lastD < 0.03) { hit = true; crevice = true; id = 0; }   // out of steps in a canyon

    vec3 d3 = PD + ZSH * zc + vec3(uHue);
    float fogK = (0.16 + 0.03 * zc) * (1.0 + outgoing);                                // thickens with the dive: depth stays legible at small scales
    vec3 col = vec3(0.0);
    if (hit) {
        gDepth = t;
        vec3 p = ro + rd * t;
        if (id != 0) {
            vec3 n = normalToy(p, 0.0008);
            col = toyColor(id, p, n);
        } else {
            // traps from the hit evaluation (the normal taps below overwrite the globals)
            float trapR = gTrapR, trapPlane = gTrapPlane, trapAxis = gTrapAxis;
            vec3 n = normalScene(p, 0.0008);
            // crevice AO: two DE taps along the normal (near + wide) — sculpts the lobes, stable under reprojection
            float ao1 = clamp(sceneDE(p + n * 0.03) / 0.03, 0.0, 1.0);
            float ao2 = clamp(sceneDE(p + n * 0.09) / 0.09, 0.0, 1.0);
            float ao = 0.10 + 0.90 * sqrt(ao1) * (0.35 + 0.65 * ao2);
            if (crevice) ao *= 0.35;                                                     // ran out of steps in a canyon: deep shadow
            vec3 PAz = PA + vec3(0.0, 0.0, min(0.02 * zc, 0.15));                        // a.b lifts with level (blue)
            vec3 base = pal(2.2 * trapR + 0.3 * trapAxis, PAz, PB, PC, d3);              // teal / cyan / blue stone
            float diff = max(dot(n, KEY), 0.0);
            float hemi = 0.5 + 0.5 * n.y;
            float fres = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
            float fog = exp(-t * fogK), fogE = sqrt(fog);                                // emissives fog at half strength (they glow)
            col  = base * (0.05 + 0.34 * diff + 0.10 * hemi) * ao * fog;                 // dark teal stone
            float sh = 1.0 - ao;
            col += VIOL * sh * sh * 0.12 * fog;                                          // violet trace in the DEEP shadow only (squared: the lit stone stays teal)
            // gold seams (plane orbit trap): squared profile so the tails vanish instead of going dim-yellow.
            // The seam colour follows its FINAL intensity — bright = saturated gold, dim = lime (dim gold is
            // olive on the waveguide). A hit rings them, bass widens them.
            float ribK = 20.0 / (1.0 + 0.4 * uBass);
            float s = exp(-trapPlane * ribK);
            float seamI = s * s * (1.5 + 0.7 * uEnergy) * (1.0 + 1.2 * uHitFlash) * (0.3 + 0.7 * ao) * fogE;
            col += mix(LIME, GOLD, smoothstep(0.04, 0.22, seamI)) * seamI;
            col += CYAN * fres * 0.45 * (0.5 + 0.5 * ao) * fogE;                         // saturated cyan fresnel on the ridges
            // sluurp lantern: electric-blue point light with a wrapped rim term (the sluurp rim-lights the lobes)
            vec3 lb = uSluurp.xyz - p; float lr = max(length(lb), 1e-4); vec3 L = lb / lr;
            float lant = (0.35 + 1.0 * uSluurpGlow) / (1.0 + 2.0 * lr * lr);
            float wrap = max(dot(n, L) * 0.7 + 0.3, 0.0);
            col += LANT * (0.55 * wrap * ao + 0.9 * fres * wrap) * lant * fogE;
            // treble motes: round, cell-jittered, never smaller than ~3 FBO px, each twinkling at its own rate
            float ms = 30.0;
            vec3 cell = floor(p * ms);
            float h = hash21(cell.xy + 7.13 * cell.z);
            vec3 jit = vec3(hash21(cell.xy + 3.7 + cell.z), hash21(cell.yz + 11.1 + cell.x), hash21(cell.zx + 5.3 + cell.y)) * 0.6 + 0.2;
            float dm = length(fract(p * ms) - jit) / ms;                                 // world distance to the mote
            float rm = max(0.006, 3.0 * pxAng * t);
            float twinkle = step(0.88, fract(h * 13.0 + uTime * (0.4 + 0.6 * h)));
            col += MOTE * exp(-dm * dm / (rm * rm)) * step(0.85, h) * twinkle * uTreble * 1.8 * fogE;
        }
    } else {
        gDepth = -1.0;
        // look-away nebula backdrop (≤ 0.08 luminance, 2 octaves) so the frame is never dead
        if (uLookAway > 0.001)
            col += uLookAway * 0.08 * pal(0.5 * noise3(rd * 2.0 + uTime * 0.02) + 0.3 * noise3(rd * 5.0), PA, PB, PC, d3);
    }
    float haze = glow * 0.3;                                                             // recess haze: teal/cyan when thin, gold only when dense
    col += hazeColor(haze) * haze;
    return boost(col) + toysEdge(uv);
}
