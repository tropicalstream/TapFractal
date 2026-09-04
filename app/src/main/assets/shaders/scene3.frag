// =====================================================================================
// scene3.frag — The Throat (MENGER TUNNEL), DESIGN §9.3. Owned by the scene-3 author.
//
// An infinite kaleidoscopic-IFS Menger lattice (world scale 2: cells are 4 u, the central
// throat is 0.667 u half-wide at zoom 0), lit ONLY at its edges over near-black faces that fog
// to pure black. Fold count = 3 + floor(zoom) (cap 7), a per-fold twist grows 0.05 rad/level
// (cap 0.35) and the beat kicks it (+0.15·beat²), the beat also kicks the fold scale
// (3 + 0.5·beat²), bass widens the throat (xy ÷ 1.08), an octahedral crystal fold blends in
// over zoom 3→4 (offset plane x+y = 0.9 in the sorted frame at folds ≥ 1 — keeps the throat open).
// The twist ALSO starts at fold 1 (2026-09-03): twisting fold 0 compounded through 7 folds and shut
// the central throat by zoom 5–6 (open radius 0.05 → 0), which the travel dive now reaches in under
// a minute; with folds ≥ 1 twisted the throat stays ≥ 0.45 u open (before narrowing) at any zoom.
//
// DIVE (2026-09-03, "travel INTO the fractal"): uZoom is continuous and grows with forward travel
// (Throat.diveRate ≈ 1 level per 8 s at CRUISE) as well as with hits. Everything structural is a
// function of uZoom, so rushing in keeps adding folds, twist and the crystal fold; the throat also
// NARROWS with zoom (xy ÷ narrow, narrow = max(0.75, 0.96^zoom)) so the walls — and their finer
// lattice — close in on you as you dive. Throat.kt mirrors mengerDE() exactly (same tiling, spin,
// narrowing, twist, folds, octahedral offset, scale 3, WS) with the music terms at zero.
//
// PALETTE (2026-09-03, "colours washed out"): full-saturation only, on black —
//   coarse lattice edges (levels 0–2)  electric blue  (0, 0.55, 1)    ~1 px line + 3 px halo
//   fine lattice edges (levels ≥ 3)    lime           (0.55, 1, 0.10) ~1 px line + 2 px halo, ONE or
//                                      two levels at a time (boxes 20–56 px fade in; smaller ones are
//                                      not drawn — dense sub-pixel lines were the old teal wash)
//   lattice vertices (levels 0–2)      magenta        (1, 0.15, 0.85) beat-pumped nodes (local)
//   kick scan-ring                     magenta        travels along the throat with each beat
//   target hit rings                   lime           at each target's z
//   hit flash ring                     gold           (1, 0.92, 0.25) launched from you
//   faces                              deep blue      (0.002, 0.004, 0.016)·AO — near black, never teal
//   fog                                → black        exp(−0.36 t)
// No normals are evaluated on the walls (the hierarchical edge orbit-trap draws the wireframe).
// =====================================================================================
// DESIGN §8 cosine palette of this room (Throat.kt's `palette`, from which the core derives the echo tint);
// the walls no longer sample it — every lit element uses the fixed saturated set below.
const vec3 PA = vec3(0.30, 0.75, 0.60), PB = vec3(0.30, 0.25, 0.40), PC = vec3(1.0), PD = vec3(0.30, 0.00, 0.60);
const vec3 ZSH = vec3(0.0, 0.035, -0.025);
const float WS = 2.0;                 // world scale: lattice cell = 2·WS = 4 u, throat half-width WS/3
const float CELL = 4.0;
const float OCT_W = 0.9;              // octahedral fold acts where x + y < OCT_W (sorted frame), folds ≥ 1
const float FAR = 11.0;               // far clip (fog exp(−0.36·11) ≈ 0.02 hides it)
const float NARROW_MIN = 0.75;        // the throat never narrows below 0.75× (open half-width ≥ 0.34 u at any zoom)
const vec3 EBLUE = vec3(0.00, 0.55, 1.00);   // coarse edges
const vec3 LIME  = vec3(0.55, 1.00, 0.10);   // fine edges, veins, target hit rings
const vec3 MAG   = vec3(1.00, 0.15, 0.85);   // beat accents: vertex nodes + kick ring
const vec3 GOLD  = vec3(1.00, 0.92, 0.25);   // hit flash ring
const vec3 FACE  = vec3(0.002, 0.004, 0.016); // faces: deep saturated blue, near black (sRGB ≈ 0.14 after gamma)
const vec3 GLOWC = vec3(0.00, 0.45, 1.00);   // distance glow (blue, saturated)

// per-frame structure parameters (set once at the top of render())
int   gFolds;                         // 3 + floor(zoom), clamp 3..7
float gScale, gOct, gBassK;           // fold scale (3 + 0.5·beat²), octahedral offset, bass widening × zoom narrowing
vec2  gSpinCS, gTwistCS;              // (cos, sin) of the cell spin and the per-fold twist

// ---- the distance estimator (Throat.kt mirrors this function) ----
float mengerDE(vec3 p, int folds) {
    p.xy = vec2(gSpinCS.x * p.x - gSpinCS.y * p.y, gSpinCS.y * p.x + gSpinCS.x * p.y);   // cell spin about the throat axis
    p.xy /= gBassK;                                                                    // bass widens / zoom narrows the throat
    p = mod(p + WS, 2.0 * WS) - WS;                                                    // infinite lattice, all three axes
    p /= WS;
    float s = 1.0;
    float sc = gScale, off = gScale - 1.0;
    for (int i = 0; i < 7; i++) {
        if (i >= folds) break;
        p = abs(p);
        if (p.x < p.y) p.xy = p.yx;                                                    // sort x ≥ y ≥ z
        if (p.x < p.z) p.xz = p.zx;
        if (p.y < p.z) p.yz = p.zy;
        if (i >= 1) {                                                                  // octahedral crystal fold (no-op while gOct = 0)
            float dd = (p.x + p.y) * 0.70710678 - gOct;
            if (dd < 0.0) p.xy -= 2.0 * dd * 0.70710678;
        }
        if (i >= 1) p.xy = vec2(gTwistCS.x * p.x - gTwistCS.y * p.y, gTwistCS.y * p.x + gTwistCS.x * p.y);   // per-fold twist (folds ≥ 1: keeps the throat open)
        p = p * sc - off;
        if (p.z < -1.0) p.z += 2.0;
        s *= sc;
    }
    vec3 q = abs(p) - 1.0;
    return (length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0)) / s * WS * gBassK;
}

// level of detail along the ray: folds whose solid boxes (4/3^k u) would be under ~6 px at distance t are
// skipped (each dropped fold only removes detail finer than that — the coarser sponge is a superset, so
// the bound stays safe). Resolution-aware, so low rungs march fewer folds and the deep levels stop
// resolving sub-pixel sponge that only ever rendered as noise. gPx = world size of one FBO pixel at t = 1.
float gPx;
int foldsAt(float t) {
    float k = log2(CELL / max(6.0 * gPx * t, 1e-5)) * 0.6309298;   // log3
    return clamp(int(floor(k)), 3, gFolds);
}

// Hierarchical edge trap at a HIT point (same transforms as mengerDE; the DE itself stays lean).
// After fold i the point sits in the frame of a level-(i+1) box (half-size WS/3^(i+1) u); the Menger
// sponge keeps every level's cube edges, so the world distance to the nearest edge of that unit box
// draws the lattice wireframe.
//   edgeB: coarse levels 0–2, ~1 px line + 3 px GAUSSIAN halo (an exp(−d) halo's tail summed over
//          the levels was a dim teal field on every wall), boxes ≥ 48 px full weight        → electric blue
//   edgeL: fine levels ≥ 3, ~1 px line + 2 px halo, ONLY boxes spanning 20–56+ px on screen → lime
//          (the finest visible level fades in as you approach; sub-20-px boxes are never drawn, so the
//          walls stay black instead of filling with a teal/olive field of sub-pixel lines)
//   node:  the box VERTICES of the coarse levels (≈ 6 px dots)                              → magenta
//   vein:  the level-0 cell mid-planes (untwisted, run along the tunnel)                    → lime
void mengerTrap(vec3 p, int folds, float t, out float edgeB, out float edgeL, out float node, out float vein) {
    p.xy = vec2(gSpinCS.x * p.x - gSpinCS.y * p.y, gSpinCS.y * p.x + gSpinCS.x * p.y);
    p.xy /= gBassK;
    p = mod(p + WS, 2.0 * WS) - WS;
    p /= WS;
    float s = 1.0;
    float sc = gScale, off = gScale - 1.0;
    float w = max(1.0 * t * gPx, 0.0005);                        // ~1 px line half-width in world units
    edgeB = 0.0; edgeL = 0.0; node = 0.0; vein = 0.0;
    for (int i = 0; i < 7; i++) {
        if (i >= folds) break;
        p = abs(p);
        if (p.x < p.y) p.xy = p.yx;
        if (p.x < p.z) p.xz = p.zx;
        if (p.y < p.z) p.yz = p.zy;
        if (i >= 1) {
            float dd = (p.x + p.y) * 0.70710678 - gOct;
            if (dd < 0.0) p.xy -= 2.0 * dd * 0.70710678;
        }
        if (i >= 1) p.xy = vec2(gTwistCS.x * p.x - gTwistCS.y * p.y, gTwistCS.y * p.x + gTwistCS.x * p.y);
        p = p * sc - off;
        if (p.z < -1.0) p.z += 2.0;
        s *= sc;
        vec3 aq = abs(abs(p) - 1.0);                             // distance to the 6 faces of this level's box
        float m1 = min(aq.x, min(aq.y, aq.z)), m3 = max(aq.x, max(aq.y, aq.z));
        float m2 = aq.x + aq.y + aq.z - m1 - m3;
        float k = WS / s;                                        // box frame → world
        float ed = length(vec2(m1, m2)) * k;                     // world distance to the nearest box edge
        float boxPx = 2.0 * k / max(t * gPx, 1e-5);              // box size on screen
        float line = exp(-ed * ed / (w * w));
        if (i < 3) {
            float g = smoothstep(12.0, 48.0, boxPx);
            edgeB += (line + 0.25 * exp(-ed * ed / (9.0 * w * w))) * g;   // coarse: line + 3 px Gaussian halo (no tail → no field)
            float dn = length(aq) * k;                           // world distance to the nearest box vertex
            node += exp(-dn * dn / (9.0 * w * w)) * g;           // ~3 px node radius
        } else {
            float g = smoothstep(20.0, 56.0, boxPx);
            edgeL += (line + 0.20 * exp(-ed * ed / (4.0 * w * w))) * g;   // fine: line + 2 px Gaussian halo, sparse levels only
        }
        if (i == 0) { float dv = min(abs(p.x), min(abs(p.y), abs(p.z))) * k / (1.2 * w); vein = exp(-dv * dv) * smoothstep(12.0, 48.0, boxPx); }
    }
}

float map(vec3 p, int folds, out int id) {
    float d = mengerDE(p, folds); id = 0;
    int tid; float dt = toysDE(p, tid);
    if (dt < d) { d = dt; id = tid; }
    return d;
}

vec3 boost(vec3 c) { float l = dot(c, vec3(0.2126, 0.7152, 0.0722)); return max(l + (c - l) * 1.35, 0.0); }

vec3 normalToy(vec3 p, float e) {
    const vec2 k = vec2(1.0, -1.0); int id;
    return normalize(k.xyy * toysDE(p + k.xyy * e, id) + k.yyx * toysDE(p + k.yyx * e, id)
                   + k.yxy * toysDE(p + k.yxy * e, id) + k.xxx * toysDE(p + k.xxx * e, id));
}

// ray entry into the toy bounding spheres: the reprojected start must never skip a toy that moved
// into this pixel since last frame (the depth buffer only remembers where the static wall was)
float raySphereEntry(vec3 ro, vec3 rd, vec3 c, float r) {
    vec3 oc = ro - c; float b = dot(oc, rd); float cc = dot(oc, oc) - r * r;
    if (cc <= 0.0) return 0.0;                                   // inside the sphere
    float h = b * b - cc;
    if (h < 0.0 || b > 0.0) return 1e9;                          // miss, or entirely behind the ray
    return -b - sqrt(h);
}
float toyEntryT(vec3 ro, vec3 rd) {
    float e = 1e9;
    if (uToyBounds.w > 0.0) e = min(e, raySphereEntry(ro, rd, uToyBounds.xyz, uToyBounds.w + TOY_PAD + 0.02));
    for (int i = 0; i < 6; i++) {
        if (uTargets[i].w > 0.0) e = min(e, raySphereEntry(ro, rd, uTargets[i].xyz, uTargets[i].w * 4.2));
    }
    return e;
}

vec3 render(vec2 uv) {
    // ---- structure parameters for this frame (continuous in uZoom: hits AND the travel dive) ----
    float zc = min(uZoom, 7.0);
    float kick = uBeat * uBeat;
    gFolds  = int(clamp(3.0 + floor(uZoom), 3.0, 7.0));
    gScale  = 3.0 + 0.5 * kick;
    // spin: base 0.10 rad/s plus a rocking term whose PEAK rate is the table's 0.05·L (continuous in uZoom,
    // so a level-up never whirls the lattice — a rate·time product would jump by 0.05·t on every hit)
    float spin = 0.10 * uTime + 0.10 * zc * sin(0.5 * uTime);
    gSpinCS = vec2(cos(spin), sin(spin));
    float twist = min(0.35, 0.05 * uZoom) + 0.15 * kick;
    gTwistCS = vec2(cos(twist), sin(twist));
    gOct   = OCT_W * 0.70710678 * clamp(uZoom - 3.0, 0.0, 1.0);   // blends in over the 3→4 ease
    float narrow = max(NARROW_MIN, pow(0.96, max(uZoom, 0.0)));   // the throat narrows as you dive (Throat.narrowFor)
    gBassK = (1.0 + 0.08 * uBass) * narrow;

    vec3 ro = uCamPos, rd = rayDir(uv);
    vec2 uvGeom = uv - vec2(uUvShift, 0.0);

    // ---- conservative ray start from last frame's depth (B.4), ×0.85 for flight, clamped to the toys ----
    float t = startT(uvGeom, ro, rd) * 0.85;
    t = min(t, max(toyEntryT(ro, rd) * 0.9, 0.0));
    if (t > 0.0) { int id0; if (map(ro + rd * t, gFolds, id0) < 0.002) t = 0.0; }   // verify, else restart at 0

    // ---- march (rung-6 budget: ≤ 56 steps, ~155 ops/DE) ----
    float maxSteps = floor(mix(28.0, 56.0, uQuality));
    gPx = uFov / max(uEyeRes.y, 1.0);                             // world size of one FBO pixel per unit distance
    float pixAng = 0.6 * gPx;                                     // ~0.6 px of tangent per unit distance
    float glow = 0.0, steps = 0.0; int id = 0; bool hit = false; int folds = gFolds;
    for (int i = 0; i < 56; i++) {
        if (float(i) >= maxSteps) break;
        folds = foldsAt(t);
        float d = map(ro + rd * t, folds, id);
        glow += exp(-d * 80.0) * 0.0005;
        if (d < 0.0004 + t * pixAng) { hit = true; break; }
        t += d * 0.9; steps = float(i) + 1.0;
        if (t > FAR) break;
    }

    vec3 col = vec3(0.0);
    gDepth = hit ? t : -1.0;

    // rings travel along the throat axis in the direction you are facing; the beat ring is also fired by
    // the §9.5 transitions (outgoing: a ring on the last beat; incoming: a ring that races in with the fade)
    float dz = clamp(2.0 * uCamRot[2].z, -1.0, 1.0);
    float beatRing = max(uBeat, max(-uTransition, (uTransition > 0.0) ? 1.0 - uTransition : 0.0));

    vec3 p = ro + rd * t;
    if (hit) {
        if (id != 0) {
            vec3 n = normalToy(p, 0.0008);
            col = toyColor(id, p, n);
        } else {
            float edgeB, edgeL, node, vein;
            mengerTrap(p, folds, t, edgeB, edgeL, node, vein);
            edgeB = min(edgeB, 1.0); edgeL = min(edgeL + 0.5 * vein, 1.0); node = min(node, 1.0);
            float ao = pow(1.0 - steps / maxSteps, 1.4);
            float flick = 0.85 + 0.3 * uTreble * hash21(floor(p.xy * 6.0) + floor(uTime * 16.0)) * (1.0 - uAttract);
            float streak = 1.0 + 0.4 * min(uSpeed / 3.5, 1.0);    // the faster you fly, the hotter the lattice
            streak *= mix(1.6, 1.0, smoothstep(0.0, 1.5, uTime)); // §9.5 dive-in: ×1.6 decaying over 1.5 s
            col  = FACE * ao;                                                               // faces: near-black deep blue
            col += EBLUE * edgeB * flick * 2.8 * streak * (1.0 + 0.35 * uHitFlash) * ao;   // coarse edges: electric blue
            col += LIME  * edgeL * 1.6 * streak * ao;                                       // fine edges / veins: lime
            col += MAG   * node * (1.6 + 2.4 * kick) * ao;                                  // vertices: magenta, pumped by the beat
            // rings light the edges they pass (a whisper on the faces so the slice still reads)
            float lit = 0.02 + 1.6 * clamp(edgeB + edgeL, 0.0, 1.0);
            // (explicit squares: pow() with a negative base is undefined in GLSL ES and NaNs on Adreno)
            float ringZ = uCamPos.z + dz * 6.0 * beatRing;                                  // magenta scan-ring (kick)
            float rb = (p.z - ringZ) * 7.0;
            col += MAG * exp(-rb * rb) * beatRing * 2.4 * (1.0 - 0.25 * uAttract) * lit;
            for (int i = 0; i < 6; i++) {                                                  // lime hit rings at each target's z
                if (uTargets[i].w > 0.0 && uTargetPulse[i] > 0.01) {
                    float rt = (p.z - uTargets[i].z) * 9.0;
                    col += LIME * exp(-rt * rt) * uTargetPulse[i] * 2.5 * lit;
                }
            }
            float hz = uCamPos.z + dz * 10.0 * (1.0 - uHitFlash);                          // gold hit ring, launched from you
            float rh = (p.z - hz) * 8.0;
            col += GOLD * exp(-rh * rh) * uHitFlash * 2.6 * lit;
        }
        col *= exp(-t * (0.36 - 0.06 * uEnergy));                                            // fog → black
    }
    col += glow * GLOWC * (0.6 + 0.4 * uEnergy);                                             // faint blue distance glow
    return boost(col) + toysEdge(uv);
}
