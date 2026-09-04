// =====================================================================================
// scene4.frag — Scene 4: THE HIVE (APOLLONIAN BLOOM). DESIGN §9.4; mirrored by scenes/Hive.kt.
//
// Pitch dark. You fly INTO an Apollonian sphere packing the size of a hangar and the only light is
// the sluurp: swing it and the hot-magenta silhouettes of kissing spheres appear and vanish (near-black
// bodies, saturated rims, single-pixel white-hot highlights); where spheres kiss, gold contact rings
// glow. Integration tune 2026-09-03 (on-device: the room read as black from the void finder's open spot, 1.6 u from
// every wall): lamp reach 1.3 → 1.6, gold-ring floor 0.12 → 0.30, hot-core floor 0.06 → 0.12, magenta Fresnel floor 0.30 → 0.42.
// Travel dives: uZoom rises continuously with forward travel (DIVE CONTRACT, diveRate in Hive.kt),
// the inversion radius grows (foam → bubbles → crystal) and the next generation of ever-smaller spheres
// grows in SMOOTHLY (fractional iteration count) so the detail resolves as you go in — no pops.
// World scale 4 → cell = 8 u.
//
// Contract: common.glsl is prepended, footer.glsl appended. This file defines render() + helpers.
// The Kotlin mirror (Hive.de) runs the SAME apollonianDE with gBloom = 0 — keep them in lockstep:
//   s      = 1.02 + 0.09·min(zoom, 7) + 0.02·clamp(zoom − 7, 0, 5)
//   itF    = clamp(5 + 0.5·zoom, 5, 9); n = int(itF); f = itF − n   (n full iterations + f of the next fold)
//   twist  = rotY(0.2·min(zoom, 6)) · rotX(0.11·min(zoom, 6))     (capped: a continuous dive must not sweep
//                                                                    the walls around the origin for ever)
//   no xz mirror (the design's L ≥ 4 |p.xz| was a whole-world pop; a continuous dive cannot afford one)
//   yfold  = |fract(p.y / s + 0.5) − 0.5| · s   (after the twist, before the iteration — see apollonianDE)
//   DE     = WS · 0.25 · mix(|p.y|, |fold(p).y|, f) / scale
// No outside (2026-09-03 fixer): the sphere layer sits on the planes y = 2k (cell) and its tallest bubbles reach
// |y| = s/2; everything between was a 4 u black band the flight cruised into ("black sky except the toys").
// Mirroring y with period s stacks the layers so the big bubbles kiss their mirror images: floor and ceiling are
// never more than 2 u away wherever you are. Near-field structure (same round): the orbit trap's level sets
// modulate the lamp as contact rings with gold glints, so a camera pinned against a bubble sees rings, not a skin.
// =====================================================================================
const vec3 PA = vec3(0.85, 0.25, 0.80), PB = vec3(0.15, 0.25, 0.20), PC = vec3(1.0), PD = vec3(0.00, 0.55, 0.35);
const vec3 ZSH = vec3(0.0, 0.035, -0.025);
const float WS  = 4.0;                       // world scale: p/WS is in cell units, cell = [-1,1)^3 → 8 u
const float FAR = 6.0;                       // far clip (u)
const float TRAPK_NONE = 4.0;                // trapK for points never inverted: exp(-30·4) ≈ 0 → no glint
const vec3 GOLD  = vec3(1.0, 0.85, 0.25);    // contact rings, hue 48° (gold band, clear of orange)
const vec3 HOT   = vec3(1.0, 0.92, 0.70);    // specular cores / ring cores — the only white-hot pixels
const vec3 MAG   = vec3(1.0, 0.12, 0.72);    // the room's accent (§8): saturated hot magenta rims
const vec3 PINK  = vec3(1.0, 0.35, 0.80);    // rim cores + target lamps at rest (sat 0.65)
const vec3 VIO   = vec3(0.45, 0.10, 1.00);   // violet: crevice / far-side rim tint
const vec3 SPECK = vec3(1.0, 0.95, 0.70);    // treble specks

// per-frame structure (set once at the top of render(); the Kotlin mirror derives the same from zoom)
float gS;        // inversion radius²: 1.02 → 1.65 (→ 1.75 very deep), foam → bubbles → crystal
float gBloom;    // music inflate (visual only; 0 on the CPU)
int   gIter;     // 5..9 full iterations
float gIterF;    // 0..1 weight of the next fold (fractional iteration; 0 at the 9-iteration cap)
mat3  gTwist;    // rotY(0.2·min(uZoom,6))·rotX(0.11·min(uZoom,6))

// ---- the packing -----------------------------------------------------------------------
// trapK = min(k − 1) over inverting iterations (0 = right on an inversion sphere → gold contact ring);
// trapR = min r² (palette phase). Return value is a conservative lower bound (0.25 factor, as IQ's).
// The last inversion never changes |p.y|/scale (k cancels), only the NEXT fold does — so the fractional
// iteration is a blend between |p.y| and |fold(p).y|: the next generation of spheres inflates smoothly.
float apollonianDE(vec3 p, out float trapK, out float trapR) {
    p = gTwist * (p / WS);
    p.y = abs(fract(p.y / gS + 0.5) - 0.5) * gS;   // stack the layers: mirror y with period s (1-Lipschitz, the bound holds)
    float scale = 1.0; trapK = TRAPK_NONE; trapR = 1e9;
    for (int i = 0; i < 9; i++) {
        if (i >= gIter) break;
        p = -1.0 + 2.0 * fract(0.5 * p + 0.5);
        float r2 = max(dot(p, p), 1e-7);
        float k = max(gS / r2, 1.0);
        p *= k; scale *= k;
        if (k > 1.0) trapK = min(trapK, k - 1.0);
        trapR = min(trapR, r2);
    }
    vec3 q = -1.0 + 2.0 * fract(0.5 * p + 0.5);
    float r2 = max(dot(q, q), 1e-7);
    float k = max(gS / r2, 1.0);
    if (k > 1.0) trapK = mix(trapK, min(trapK, k - 1.0), gIterF);
    trapR = mix(trapR, min(trapR, r2), gIterF);
    float dy = mix(abs(p.y), abs(q.y), gIterF);
    // never inverted (scale == 1): every bubble lives inside the inversion sphere, so the exact plane distance is a
    // valid bound up to that sphere — without this the 0.25× bound crept along a grazing floor at 7 %/step and a
    // floor 0.5 u below the eye was never hit within 45 steps (black floor on the device, fixer 2026-09-03)
    float d = (scale == 1.0) ? max(0.25 * dy, min(dy, length(p) - sqrt(gS))) : 0.25 * dy;
    return WS * d / scale - gBloom;
}
float map(vec3 p, out int id) {
    float a, b; float d = apollonianDE(p, a, b); id = 0;
    int tid; float dt = toysDE(p, tid); if (dt < d) { d = dt; id = tid; }
    return d;
}
vec3 boost(vec3 c) { float l = dot(c, vec3(0.2126, 0.7152, 0.0722)); return max(l + (c - l) * 1.35, 0.0); }
vec3 normalDE(vec3 p, float e) { const vec2 k = vec2(1.0, -1.0); int id;
    return normalize(k.xyy * map(p + k.xyy * e, id) + k.yyx * map(p + k.yyx * e, id)
                   + k.yxy * map(p + k.yxy * e, id) + k.xxx * map(p + k.xxx * e, id)); }

// point lamp: att = 1/(1 + 6d²/R²) with a hard reach at 1.6 R (walls appear and vanish; the falloff was 16d²/R²
// until 2026-09-03 — a wall 1.5 u away got 1/15 of the lamp and the room read as black from any open spot).
// The body is dark (diffuse × 0.30 × the ring structure in a squared palette); the light lives on the silhouette: a Fresnel rim
// in saturated hot magenta with a pink core, and Blinn spec power 80 → single-pixel white-hot highlights.
// `str` (0.3..1) is the surface structure (contact-ring bands from the orbit trap): it breaks the rim and the body
// into rings, so a bubble filling the frame is never a uniform skin.
vec3 lamp(vec3 p, vec3 n, vec3 rd, vec3 lp, float R, vec3 tint, vec3 base, float specW, float str) {
    vec3 L = lp - p; float d = max(length(L), 1e-4); L /= d;
    float att = smoothstep(1.6 * R, 0.4 * R, d) / (1.4 + 6.0 * d * d / (R * R));   // 1.4: a wall at point-blank range is not a flashbulb
    float diff = max(dot(n, L), 0.0);
    float fres = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
    // spec power 160 (was 80): on a flat floor or a big bubble the 80 lobe spread into a wide pale beige blob that
    // pulled the frame's saturation under the vibrant bar; the ring structure breaks the lobe up too
    float spec = pow(max(dot(n, normalize(L - rd)), 0.0), 160.0) * (0.35 + 0.65 * str);
    vec3 body = base * diff * 0.30 * str;
    vec3 rim  = mix(mix(VIO, MAG, 0.6 + 0.4 * diff), PINK, fres * fres) * (0.25 + 0.75 * diff) * fres * 3.0 * str;
    return (body + rim + HOT * spec * specW) * att * tint;
}
// far end of the ray's overlap with the sphere |p − c| < R (0 when the ray misses it)
float litSpan(vec3 ro, vec3 rd, vec3 c, float R) {
    vec3 oc = ro - c; float b = dot(oc, rd); float h = b * b - dot(oc, oc) + R * R;
    return (h < 0.0) ? 0.0 : -b + sqrt(h);
}

vec3 render(vec2 uv) {
    // ---- per-level structure (uZoom = hit levels eased + travel dive; the mirror uses the same map) ----
    float itF = clamp(5.0 + 0.5 * uZoom, 5.0, 9.0);
    gIter   = int(itF); gIterF = itF - float(gIter);
    float zs = min(uZoom, 7.0), zt = min(uZoom, 6.0);
    gS      = 1.02 + 0.09 * zs + 0.02 * clamp(uZoom - 7.0, 0.0, 5.0);
    gBloom  = 0.004 * uEnergy + 0.012 * uBeat;                 // walls pulse toward you on the kick
    gTwist  = rotY(0.2 * zt) * rotX(0.11 * zt);

    // ---- hit flash: ≤ 0.15 s (uHitFlash decays τ 0.7 s: > 0.80 for the first 0.156 s), squared → gone fast.
    // It lights the gold contact rings and lifts the lamp reach a little; it is never a full-frame flashbulb.
    float hitW = clamp((uHitFlash - 0.80) / 0.20, 0.0, 1.0); hitW *= hitW;
    // transitions (§9.5): incoming = the lamp reach ramps up over the 1 s fade; outgoing ("drain") ×2 then 0
    float inRamp = (uTransition > 0.0) ? mix(0.6, 1.0, smoothstep(0.0, 1.0, uTransition)) : 1.0;
    float drain  = (uTransition < 0.0) ? 2.0 * smoothstep(0.0, 0.45, -uTransition) : 1.0;
    // R: bass lets you see farther, flick/beat/attract lift it
    float R = max((1.6 + 0.1 * zs + 0.6 * uBass + 0.5 * uSluurpGlow + 0.2 * uBeat
                   + 0.5 * uAttract + 0.6 * hitW) * drain * inRamp, 0.05);

    vec3 ro = uCamPos, rd = rayDir(uv);

    // lit span: beyond the last lamp sphere only fogged self-emission remains → shorten the far clip
    float tMax = litSpan(ro, rd, uSluurp.xyz, 1.6 * R);
    for (int i = 0; i < 6; i++) if (uTargets[i].w > 0.0)
        tMax = max(tMax, litSpan(ro, rd, uTargets[i].xyz, 1.6 * (0.7 + 1.0 * uTargetPulse[i])));
    tMax = clamp(tMax, 3.0, FAR);

    // reprojected start (B.4): a hint, verified — restart at 0 if it lands inside/at a surface
    float t = startT(uv - vec2(uUvShift, 0.0), ro, rd) * 0.85;   // flight → extra 15 % margin
    { int id0; if (t > 0.0 && map(ro + rd * t, id0) < 0.002) t = 0.0; }

    float maxSteps = floor(mix(24.0, 56.0, uQuality));           // 24 @ rung 0 … 56 @ rung ≥ 5
    float steps = 0.0; int id = 0; bool hit = false; float dLast = 1.0;
    for (int i = 0; i < 56; i++) {
        if (float(i) >= maxSteps) break;
        float d = map(ro + rd * t, id); dLast = d;
        if (d < 0.001 * t + 0.0005) { hit = true; break; }
        t += d * 0.9; steps = float(i);
        if (t > tMax) break;
    }
    // soft hit: the budget ran out a hair above a surface (a grazing floor under the conservative bound) — shade it
    // (the ao term darkens it) instead of leaving a hole into the black
    if (!hit && t <= tMax && dLast < 0.02 * t + 0.004) hit = true;

    vec3 col = vec3(0.0);
    gDepth = hit ? t : -1.0;
    if (hit) {
        vec3 p = ro + rd * t; vec3 n = normalDE(p, 0.0008);
        if (id != 0) col = toyColor(id, p, n);                    // sluurp / rope / targets are emissive
        else {
            float a, b; apollonianDE(p, a, b);                     // a = trapK (contacts), b = trapR (phase)
            vec3 d3 = PD + ZSH * uZoom + vec3(uHue);
            vec3 base = pal(3.0 * b + 0.5 * min(a, 1.0), PA, PB, PC, d3);   // magenta/violet/pink, no gold
            base *= base;                                              // squared: dark, saturated (no lilac mid-tones)
            float ao = pow(1.0 - steps / maxSteps, 1.3);
            float db = length(uSluurp.xyz - p);
            float near = smoothstep(2.6, 0.4, db);
            // near-field structure: the level sets of the orbit trap (min r²) are rings around every inversion
            // centre — latitude rings toward a bubble's contact points, circles around a cluster on the bare floor.
            // Bands modulate the lamp (no uniform skin when pinned against a bubble); the iso-lines carry gold glints.
            float bph = fract(b * 12.0);
            float iso = 1.0 - smoothstep(0.0, 0.10, min(bph, 1.0 - bph));
            float band = 0.5 + 0.5 * cos(b * 12.0 * 6.2832);
            float str = 0.30 + 0.40 * band + 0.55 * iso;
            // the sluurp is the light
            col = lamp(p, n, rd, uSluurp.xyz, R, vec3(1.0), base, 1.0, str) * ao;
            // target lamps: pink, reach 0.7; on a hit they swell to 1.7 and go gold (a local flash, not the cave)
            for (int i = 0; i < 6; i++) if (uTargets[i].w > 0.0) {
                float pulse = uTargetPulse[i];
                vec3 tint = mix(PINK, GOLD, pulse) * (0.6 + 1.0 * pulse);
                col += lamp(p, n, rd, uTargets[i].xyz, 0.7 + 1.0 * pulse, tint, base, 0.5, str) * ao;
            }
            // gold contact rings: faint self-emission everywhere (the cave is never entirely invisible),
            // bright with a white-hot core within lamp reach; the hit flash lights every ring for 0.15 s
            float ring = exp(-a * 40.0);
            // (ring bands carry the iso structure and cap at 1.2: pinned 0.1 u from a contact, the trapK≈0 band filled the
            // frame as one flat yellow field at luma 0.30)
            col += GOLD * ring * (0.30 + 0.9 * near) * (0.55 + 0.45 * band) * (1.0 + uAttract + 2.5 * hitW);
            col += HOT * exp(-a * 110.0) * (0.12 + 0.8 * near) * (1.0 + 2.0 * hitW);
            // gold glints on the trap iso-lines within lamp reach (structure on the near bubble, faint beyond)
            // (hot-magenta glints — the room's accent; pink-gold bloomed into salmon bands on a lamp-less floor, meanSat 0.50)
            col += mix(MAG, PINK, 0.3) * iso * (0.10 + 0.35 * near) * (0.5 + 0.5 * max(dot(n, normalize(uSluurp.xyz - p)), 0.0));
            // hot-magenta silhouettes everywhere (faint self-lit Fresnel: the kissing spheres read as magenta
            // outlines even where the lamp does not reach — the room's accent, never a field)
            float fres = pow(1.0 - max(dot(n, -rd), 0.0), 4.0);
            col += MAG * fres * (0.42 + 0.25 * uEnergy) * (1.0 - 0.5 * near) * (0.55 + 0.45 * band);
            // treble specks on the contact rings
            col += SPECK * exp(-a * 60.0) * uTreble
                 * step(0.97, hash21(floor(p.xy * 60.0) + floor(p.z * 60.0) + floor(uTime * 6.0)));
            col += base * (0.08 + 0.35 * iso) * ao;                  // faint self-lit ring structure everywhere (violet crevices are never entirely invisible)
        }
        col *= exp(-t * (0.35 - 0.06 * uEnergy));                    // fog; energy lifts it a little
    }
    // uLookAway is always 0 here (interest cone 180°): the packing surrounds you, nothing to look away from.
    return boost(col) + toysEdge(uv);
}
