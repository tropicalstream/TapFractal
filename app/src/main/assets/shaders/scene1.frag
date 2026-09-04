// =====================================================================================
// scene1.frag — The Tidepool (JULIA DRIFT, 2D). DESIGN.md §9.1 + the DIVE CONTRACT (CORE_API).
//
// A phosphorescent rock-pool you DESCEND INTO: a 2D Phoenix–Julia set (z' = z² + c + p·z_prev)
// drawn as a distance-estimated glowing filament on black, with orbit traps (point / line /
// breathing ring / sluurp thread) that re-emerge at every scale of the dive.
//
// DIVE CONTRACT: uZoom is the CONTINUOUS depth (hit levels eased + diveRate·travel). Everything
// that depends on depth — plane scale, iteration count, the sluurp coupling, the Phoenix wobble —
// is a function of uZoom ALONE. uTravel is NOT read (the core folds the travel dive into uZoom).
//   scale = 1.6·0.72^uZoom (plane half-height)     N = 40 + 8·uZoom, cap 128
// Deviations from the DESIGN §9.1 sketch (deliberate, for the deep-zoom feel):
//  - ONE seed (the dendrite). A seed melt driven by uZoom would morph the boundary continuously
//    during a dive and throw the zoom centre off the structure it was descending into; hits now
//    re-dress the same set instead (palette phase, ring-trap phase, the eased scale step).
//  - NO kaleidoscope fold: it centred a static mirror star on the screen, hid the descent, and
//    the CPU mirror could not undo it (the mirror tested the unfolded set — a real mismatch).
//  - the sluurp's bend of c and the Phoenix wobble are scaled by 0.72^uZoom, so their shift of
//    the boundary stays proportional to the VIEW size at every depth (a whole-set morph at L0,
//    a shimmer of the same screen magnitude at depth 12) instead of an earthquake.
//
// Waveguide: black = transparent; glowing structure on black; full-saturation cyan / electric
// blue / violet / magenta along the filament, white-hot cores kept to ~1 px seeds; the interior is
// near-black water with faint contour rings. Nothing here adds a field on a flick or a hit.
//
// Kotlin mirror: scenes/Tidepool.kt::escapeCount() iterates the SAME loop with `zoom` (== uZoom):
// same seed + sluurp coupling (rest-hang estimate) × 0.72^zoom, same Phoenix p, same N, bailout 64
// and smooth count n + 4 − log2(log2 r²). The derivative dz is colouring-only (distance glow) and
// is NOT part of the mirror. Music terms (uBass on p and scale, uQuality on N) are visual-only.
// =====================================================================================

// ---- palette (Kotlin Palette carries the IQ constants for the core's echo/post tints) -------------
// The filament itself uses four fully-saturated poles so no blend ever goes pastel.
const vec3 HUE_CY = vec3(0.00, 0.85, 1.00);   // cyan
const vec3 HUE_BL = vec3(0.10, 0.25, 1.00);   // electric blue
const vec3 HUE_VI = vec3(0.50, 0.08, 1.00);   // violet
const vec3 HUE_MG = vec3(1.00, 0.06, 0.80);   // hot magenta
const vec3 CORE_W = vec3(1.00, 0.97, 0.90);   // white-hot seed cores (tiny)
const vec3 RING_C = vec3(0.10, 0.95, 0.90);   // ring-trap halos: teal-cyan
const vec3 VEIN_C = vec3(0.65, 0.12, 1.00);   // line-trap veins: violet
const vec3 SLUURP_C = vec3(1.00, 0.30, 0.90);  // the sluurp's thread: magenta

const float BAILOUT = 64.0;                   // |z|² escape radius (r = 8)
const float MAX_N   = 128.0;                  // hard iteration cap (loop bound); reached at uZoom 11
const float MIN_N   = 24.0;                   // floor after the uQuality scale
const float DEPTH_CAP = 26.0;                 // scale floor 1.6·0.72^26 ≈ 3e-4 keeps highp float clean
const vec2  SEED    = vec2(-0.7269, 0.1889);  // the dendrite

// cyan → blue → violet → magenta → cyan, smooth, always saturated
vec3 hue4(float t) {
    float s = fract(t) * 4.0;
    float f = fract(s); f = f * f * (3.0 - 2.0 * f);
    if (s < 1.0) return mix(HUE_CY, HUE_BL, f);
    if (s < 2.0) return mix(HUE_BL, HUE_VI, f);
    if (s < 3.0) return mix(HUE_VI, HUE_MG, f);
    return mix(HUE_MG, HUE_CY, f);
}

// pull the minimum channel down so blends stay saturated (does not raise brightness)
vec3 sat(vec3 c, float k) {
    float m = min(c.r, min(c.g, c.b));
    return max(c - m * k, 0.0);
}

// complex multiply
vec2 cmul(vec2 a, vec2 b) { return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x); }

// ---- the room -------------------------------------------------------------------------
vec3 render(vec2 uv) {
    // continuous depth (DIVE CONTRACT): hits + travel dive, eased by the core
    float depth = clamp(uZoom, 0.0, DEPTH_CAP);
    float zf    = pow(0.72, depth);                              // = scale / 1.6: view-size factor
    float N     = max(MIN_N, min(MAX_N, (40.0 + 8.0 * depth) * uQuality));
    float scale = 1.6 * zf * (1.0 + 0.04 * uBass);               // plane half-height; bass breath

    // transitions (§9.5): outgoing "surface" tilts the plane into perspective (uv.y stretched by
    // 1 + 2a²); incoming un-tilts and lets the rings expand from r = 0 ("drain"). Toys are unaffected.
    float tiltA = 0.0;
    if (uTransition < 0.0) tiltA = 1.0 + uTransition;            // −1 → 0 : tilt grows
    else if (uTransition > 0.0) tiltA = 1.0 - uTransition;       //  0 → 1 : tilt relaxes
    vec2 uvP = uv;
    uvP.y *= 1.0 + 2.0 * tiltA * tiltA;

    // plane point: rotate (slow drift + head roll), scale, gaze pan. Same mapping as the core's toys.
    vec2 p = rot2(uCamPos.z) * (uvP * scale) + uCamPos.xy;

    // Julia constant: the dendrite + the sluurp swing (projected uv offset from the anchor, ±0.3 clamp),
    // the swing's bend of c scaled to the view size so the morph reads the same at every depth
    vec2 b  = clamp(0.34 * (uSluurp.xy - uAnchor.xy), -0.3, 0.3) * zf;
    vec2 c  = SEED + b;
    // Phoenix coupling (real): slow wobble, amplitude scaled to the view size; bass = mass (visual-only)
    float ph = 0.30 + (0.20 * sin(uTime * 0.07) + 0.18 * uBass) * zf;

    // breathing ring trap radius: slow swell, phase advanced by the depth (rings sweep as you dive),
    // beat kick + hit flash; rings expand from 0 while incoming
    float ringR = 0.55 + 0.25 * sin(uTime * 0.21 + 1.7 * uZoom) + 0.15 * uBeat + 0.3 * uHitFlash;
    if (uTransition > 0.0) ringR *= smoothstep(0.0, 1.0, uTransition);
    vec2 bt = 2.2 * clamp(0.34 * (uSluurp.xy - uAnchor.xy), -0.3, 0.3);   // sluurp thread trap point (z-space)
    float ringR2 = ringR * ringR;

    // ---- Phoenix–Julia escape loop with four orbit traps + derivative (distance glow) ----
    // No transcendentals inside the loop (the Adreno EFU is the bottleneck): the ring trap runs on r²
    // (|r² − R²| ≈ 2R·|r − R| near the ring) and the sluurp trap on the squared distance; both are
    // converted once after the loop.
    float trapPt = 1e9, trapLine = 1e9, trapRing = 1e9, trapSluurp = 1e9;
    vec2  z = p, zp = vec2(0.0);
    vec2  dz = vec2(1.0, 0.0);                                    // ≈ d z_n / d z_0 (quadratic part only —
                                                                  // the p·dz_prev term is dropped: glow width
                                                                  // only, and it saves 4 ops per iteration)
    float n = 0.0;
    bool  inside = true;
    for (int i = 0; i < 128; i++) {
        if (float(i) >= N) break;
        vec2 zn = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c + ph * zp;    // z² + c + p·z_prev
        float dd = dot(dz, dz);
        if (dd < 1e28) dz = 2.0 * cmul(z, dz);                    // freeze before overflow: no inf/NaN,
                                                                  // the point is then ≤ 1e-13 from the boundary
        zp = z; z = zn;
        float r2 = dot(z, z);
        // orbit traps, only while |dz| < 8: the trap widths are ~5–9 px on screen at |dz| = 1 and shrink by
        // 1/|dz| for later iterations, so beyond |dz| 8 they are sub-pixel and only speckle / moiré as the
        // plane pans. Visual-only (the mirror has no traps) — same gate at every depth.
        if (dd < 64.0) {
            vec2 db = z - bt;
            trapPt    = min(trapPt, r2);                          // cores / seeds
            trapLine  = min(trapLine, min(abs(z.x), abs(z.y)));   // veins
            trapRing  = min(trapRing, abs(r2 - ringR2));          // breathing halos (on r²)
            trapSluurp = min(trapSluurp, dot(db, db));              // the sluurp's thread (squared)
        }
        if (r2 > BAILOUT) { n = float(i); inside = false; break; }
    }
    trapRing  /= 2.0 * max(ringR, 0.05);                         // ≈ min |r − R|
    trapSluurp  = sqrt(trapSluurp);
    float r2f = dot(z, z);
    float sn = inside ? N : (n + 4.0 - log2(max(log2(r2f), 1.0)));   // smooth escape count
    float u  = clamp(sn / N, 0.0, 1.0);

    // distance to the boundary in plane units (Milnor's estimate; 0 inside), then in FBO pixels
    float dist = inside ? 0.0 : 0.5 * sqrt(r2f) * log(r2f) / max(length(dz), 1e-12);
    float pxPerUnit = uEyeRes.y / max(scale, 1e-6);
    float dpx = dist * pxPerUnit;
    // orbit-trap widths in plane units, SCALED TO THE VIEW (zf): a ring halo, a vein, a seed core and the
    // sluurp thread keep the same screen size at every depth, so the early-iteration bands (smooth, huge in
    // plane units) stay thin lines and the late-iteration structure re-emerges at every scale of the dive
    float wRing  = 0.030 * zf, wLine = 0.025 * zf, wSluurp = 0.030 * zf;
    float seedR2 = 0.0016 * zf * zf;                              // seed core radius 0.04·zf plane units (~9 px)

    // ---- colouring: structure on black ----
    // hue cycles ALONG the filament: plane position (shallow), screen position (deep, where p is nearly
    // constant across the frame), the orbit (point trap: scale-free), time and the depth
    float phaseF = 0.30 * length(p) + 0.16 * length(uvP) + 0.11 * uvP.x
                 + 0.14 * sqrt(trapPt) - 0.04 * uTime + 0.09 * uZoom + uHue;
    float beatK = 0.8 + 0.4 * uBeat * (1.0 - 0.5 * uAttract);    // beat kick (calmer in attract)
    vec3 col = vec3(0.0);

    if (!inside) {
        // the boundary filament: a ~2 px saturated core + a soft halo of the same hue; the hue is pushed
        // to full saturation so the ridge never turns white — only the point-trap seeds may
        vec3 fc = sat(hue4(phaseF), 0.85);
        float core = exp(-dpx / 1.25);
        float halo = exp(-dpx / 5.0) * 0.12;
        col += fc * (core * 0.80 + halo) * (0.85 + 0.3 * uBeat);

        // reach: how long the orbit lingered — the traps fade to black away from the boundary
        float reach = smoothstep(0.06, 0.40, u);

        // veins: line trap, violet, only where the orbit lingered
        float veins = exp(-trapLine / wLine) * beatK * reach;
        col += VEIN_C * veins * 0.70;
        float glit = veins * uTreble * 0.35;                      // plane-locked treble glitter on the veins;
        if (glit > 0.004)                                         // noise3 is 8 hashes — only pay it on a vein
            col += glit * noise3(vec3(uvP * 14.4 + uCamPos.xy * 3.0, uTime * 2.5)) * fc;

        // rings: thin teal halos, brightness rides the energy
        col += RING_C * exp(-trapRing / wRing) * 0.45 * (0.5 + 0.5 * uEnergy) * reach;

        // seeds: white-hot cores where an orbit passes the origin — tiny, and they re-emerge at every depth
        float seeds = exp(-trapPt / seedR2) * reach;
        col += mix(fc, CORE_W, 0.7) * seeds * 0.9;

        // the sluurp's thread through the set: a magenta line, blazes on a flick, ripples on a hit
        col += SLUURP_C * exp(-trapSluurp / wSluurp) * 0.7 * (0.3 + 0.7 * uSluurpGlow) * (1.0 + 0.6 * uHitPulse) * reach;
    } else {
        // interior: black water with thin contour rings of the point trap and dim seeds where an
        // orbit passes the origin; drifts slowly so the pool looks alive
        // (contour spacing stays in plane units and the rings fade out below depth 6: scaled to the view
        // they turned into dense stripes / moiré from the later iterates — deep down the water is just black)
        float q = sqrt(trapPt);
        float phase = fract(q * 5.0 - uTime * 0.12);
        float tri = abs(phase - 0.5) * 2.0;                       // 1 at the contour, 0 between
        float ringsI = exp(-(1.0 - tri) * 20.0) * smoothstep(6.0, 2.0, depth);
        float seeds = exp(-trapPt / seedR2);
        vec3 ic = sat(hue4(phaseF + 0.25), 0.85);
        col += ic * (ringsI * 0.08 + seeds * 0.25) * (0.85 + 0.3 * uBeat);
        col += SLUURP_C * exp(-trapSluurp / wSluurp) * 0.4 * (0.3 + 0.7 * uSluurpGlow);
    }

    // the pool "cups" toward the periphery (deeper when looking away from the pool)
    col *= 1.0 - (0.25 + 0.20 * uLookAway) * smoothstep(0.6, 1.3, length(uv));

    gDepth = -1.0;                                               // 2D: no reprojection depth
    return toys2D(uv, col) + toysEdge(uv);
}
