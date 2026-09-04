package com.tapfractal.scenes

import com.tapfractal.M
import com.tapfractal.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Scene 2 — The Nave (MANDELBULB CATHEDRAL), DESIGN §9.2.
 *
 * An object the size of a cathedral hangs at the origin; you fly around it and INTO its canyons by
 * gaze. [de] is the CPU mirror of `assets/shaders/scene2.frag`'s `bulbDE`: same y-up POWER-8
 * Mandelbulb (the shader uses IQ's trig-free polynomial, which is the trig map at power 8 to 3e-11
 * relative — verified; the trig form is kept here for readability), same azimuth convention
 * (`atan2(x, z)`), same tables in the CONTINUOUS `zoom` (= uZoom: hit levels eased + the travel dive)
 * — base iterations `clamp(6 + 0.25·zoom, 6, 8)` (truncated like the shader's `int()`; slower than the
 * old 6 + 0.5·zoom table because the dive makes zoom 6 routine and that table cost 1.7× there), PROXIMITY
 * REFINEMENT to base + [REFINE_EXTRA] (cap [ITER_CAP] = 10) only where the coarse estimate is within
 * `refineT = max(0.10·0.85^zoom, 0.025)` of the surface (identical rule and constants in the shader),
 * twist `0.15·max(zoom − 4, 0)` as a rotY per iteration applied to the new iterate before `+ p`, swell
 * `1 + 0.035·min(zoom, 8)` (p /= swell, DE × swell), bailout r > 4, `DE = 0.5·ln r·r/dr` on the
 * CONSISTENT (r, dr) pair (r = |z| after the last executed step, IQ's form — the old r-before / dr-after
 * pair under-estimated the distance by r^6/8). Room-tuner change (2026-09-03): the power no longer grows
 * with the level (8 + 0.25·L cost 2× on the GPU — CORE_API §6 budget); growth is iterations + twist +
 * swell. The shader's music/hit terms (bass breath, beat/hit scale flex, transition slide) are visual
 * only and are NOT mirrored (scaled by 0.60^zoom in the shader so they stay under [skinAt] at every
 * zoom). The shader's cheap bounding-sphere bound outside |p| > 1.55·swell is not mirrored either (the
 * true DE is already 1–2 iterations there and a tighter bound, so the flight never slows for an
 * invisible sphere).
 *
 * DIVE CONTRACT (CORE_API): [diveRate] ≈ 1 level per 12 s at CRUISE (1.0 u/s), [skinAt] shrinks a bit
 * faster than the default so canyons ≥ 2×skin open up as you dive, [current] PULLS the flight (≤ 0.35
 * u/s) toward the nearest canyon mouth within ~22° of the flight direction, and [safeVolume] spawns the
 * eyes DEEPER IN than the player (or inside the bulb's canyons) so navigation leads into the object.
 */
class Nave : Scene(2, "Nave", "shaders/scene2.frag", "music/scene2.mp3") {
    override val is2D = false
    /**
     * Waveguide retune (2026-09-03): the stone is teal/cyan/blue only (R ≤ 0.08 at every phase — the old
     * (0.5, 0.85, 0.4) palette put yellow-green into the base and the dim result read as olive/mustard);
     * gold lives in the shader's seams/motes/haze. Same constants as scene2.frag. coreTint B lowered so a
     * hot core mixed toward it never goes pale (min(r,g,b) > 0.45); bloomTint keeps cyan bloom cyan-green
     * and gold bloom gold.
     */
    override val palette = Palette(
        a = Vec3(0.04f, 0.42f, 0.52f), b = Vec3(0.04f, 0.30f, 0.38f), c = Vec3(1f, 1f, 1f),
        d0 = Vec3(0.00f, 0.10f, 0.25f), zshift = Vec3(0.00f, 0.03f, 0.02f),
        coreTint = Vec3(1.00f, 0.90f, 0.40f), bloomTint = Vec3(1.0f, 0.95f, 0.7f), bloomStrength = 0.45f,
    )
    /** Soft 0.08 s / 1.006 / 0 · Wild 0.22 s / 1.020 / +0.006, centre (0,0) — "gold haze lingers" (§7.2). */
    override val echo = EchoParams(0.08f, 1.006f, 0f, 0.22f, 1.020f, 0.006f)
    override val speedScale = 1.0f
    /** 30 fps room: rung cap 5 (§7.3). */
    override val maxRung = 5
    override val interestConeDeg = 40f
    /** G2. */
    override val chimeRootHz = 98f

    // ---------------------------------------------------------------- DE mirror ----

    override fun de(p: Vec3, zoom: Float): Float = bulbDE(p.x, p.y, p.z, zoom)

    /** Swell of the bulb at continuous depth `zoom` (shader: `1 + 0.035·min(zc, 8)`). */
    private fun swellAt(zoom: Float): Float = 1f + 0.035f * min(min(zoom, 12f), 8f)

    /**
     * Allocation-free power-8 Mandelbulb DE; ≈ 1 µs (6–8 trig iterations, far points bail after 1–2;
     * up to 10 within `refineT` of the surface). Same loop as the shader's `bulbDE`, line for line
     * (verified on a 459k-point grid at five zooms: mean |Δ| 5e-7, no exterior sign flips).
     */
    private fun bulbDE(px0: Float, py0: Float, pz0: Float, zoom: Float): Float {
        val zc = min(zoom, 12f)
        val power = 8f
        val twist = 0.15f * max(zc - 4f, 0f)
        val nBase = M.clamp(6f + 0.25f * zc, 6f, 8f).toInt()
        val nMax = min(nBase + REFINE_EXTRA, ITER_CAP)
        val refineT = max(0.10f * 0.85f.pow(zc), 0.025f)
        val swell = 1f + 0.035f * min(zc, 8f)
        val px = px0 / swell; val py = py0 / swell; val pz = pz0 / swell
        val tc = cos(twist); val ts = sin(twist)
        var zx = px; var zy = py; var zz = pz
        var dr = 1f; var r = max(sqrt(zx * zx + zy * zy + zz * zz), 1e-6f)
        for (i in 0 until nMax) {
            if (r > 4f) break
            // coarse set done: refine only within refineT of it (the coarse DE is a lower bound either way)
            if (i == nBase && 0.5f * ln(r) * r / dr * swell > refineT) break
            val th = acos(M.clamp(zy / r, -1f, 1f)) * power
            val ph = atan2(zx, zz) * power
            val zr = r.pow(power)
            dr = zr / r * power * dr + 1f                   // = r^(P−1)·P·dr + 1
            val st = sin(th)
            var nx = zr * st * sin(ph); val ny = zr * cos(th); var nz = zr * st * cos(ph)
            // twist: rotY(twist)·z, same matrix as common.glsl rotY (c·x + s·z, y, −s·x + c·z)
            if (twist > 0f) { val rx = tc * nx + ts * nz; nz = -ts * nx + tc * nz; nx = rx }
            zx = nx + px; zy = ny + py; zz = nz + pz
            r = max(sqrt(zx * zx + zy * zy + zz * zz), 1e-6f)
        }
        return 0.5f * ln(r) * r / dr * swell
    }

    // ---------------------------------------------------------------- flight ----

    /**
     * (−1.5, 0.3, −3) looking +z: the bulb (radius ≤ 1.2) hangs to the right, so the default gaze glides
     * along its flank with ≥ 0.3 u of clearance instead of flying straight into the wall (the design's
     * (0, 0.3, −3) put the player against the surface within 2 s at CRUISE, with the anchor and sluurp
     * pinned inside the bulb and every target rejected). Turn your head to fly into the canyons.
     */
    // 2.2 u out (de ≈ 1.0, well clear) instead of 3.4: the first canyon mouth is reached in ~2 s at CRUISE
    override fun startPose() = Pose(Vec3(-0.9f, 0.25f, -2.0f), Vec3(0f, 0f, 1f))

    override fun onEnter() {
        haveLast = false; probeFrame = 0; bestScore = 0f; pull.set(0f, 0f, 0f); velEst.set(0f, 0f, 0f)
        haveHeading = false
    }

    // ---------------------------------------------------------------- dive contract ----

    /** ≈ 0.085 levels per u: one level per ~12 s at CRUISE (1.0 u/s), per ~6.5 s at FAST, per 4 s at WARP. */
    /** Slide speed along the wall while stalled against it, u/s (the core clamps currents to 0.60). */
    private val PINNED_SLIDE = 0.45f

    override val diveRate: Float = 0.085f

    /**
     * Flight skin: 0.20 u at zoom 0 (the start pose and the flank glide were tuned for it), then FAST
     * down to the 0.03 floor — zoom 1: 0.12 · 2: 0.072 · 3: 0.043 · 4+: 0.03. Measured on this mirror,
     * the bulb's canyons are only ~0.1 u wide (nothing inside |p| < 1.1 clears 0.12 u at any zoom), so
     * the default 0.85^zoom skin would keep the camera outside for good; at 0.03 the open share of the
     * inner volume is 19 % at zoom 4, 11 % at 6, 5 % at 8–10 — enough canyons to fly through. Reached
     * after ~36 s of CRUISE (or three hits). The shader's music flex is scaled by 0.60^zoom so it always
     * stays under this skin.
     */
    override fun skinAt(zoom: Float): Float = max(0.20f * 0.60f.pow(zoom), 0.03f)

    /**
     * Current (the core clamps it to 0.35 u/s): beyond 5 u a 0.15 u/s pull toward the origin (you cannot
     * lose the cathedral), and the CANYON PULL — the flight is drawn toward the nearest canyon mouth
     * within ~22° of the flight direction, so looking roughly at the object leads you into its canyons
     * and under its arches instead of gliding past. The core hands `current()` no gaze, so the flight
     * direction is inferred from successive positions (minus the current returned last frame, so the pull
     * cannot feed back on itself); it is the eased gaze, which is what the 30° rule is about. Every third
     * frame [probeCanyons] sphere-traces 7 directions (the heading + a 22° ring) ≤ 1.8 u and scores each by
     * how much CLOSER TO THE BULB'S CENTRE a clear flight along it ends — a canyon mouth scores high, a wall
     * scores 0, open space scores low. The pull is `(bestDir − heading)·0.9·gate` (≤ 0.35 u/s at 22°,
     * zero when the flight already aims at the mouth) eased with τ 0.5 s so it never steps; it fades out
     * when the flight stalls (< 0.08 u/s: no direction to reason about, and a stalled player should not be
     * moved). Gaze always wins: at CRUISE the pull bends the path ≤ 19°, at WARP ≤ 7°.
     */
    override fun current(p: Vec3, t: Float): Vec3 {
        val out = Vec3()
        val l = p.length()
        // The leash grows with distance: 0.30 u/s at 4 u up to the core's full 0.60 by 8 u. A flat 0.30 loses
        // to CRUISE (1.0 u/s) — the flight that slips out of a canyon on a fixed heading simply left the
        // cathedral behind and the room became empty space with a yo-yo in it. At 0.60 the path curves back
        // within a few seconds while a player who deliberately looks away still wins.
        if (l > 4f) out.addScaled(p, -(0.30f + 0.30f * M.clamp((l - 4f) / 4f, 0f, 1f)) / l)

        // flight direction from the position delta (this call comes once per frame, before the move)
        var dt = if (haveLast) t - lastT else 0f
        if (haveLast && dt > 1e-4f && dt < 0.5f) {
            velEst.set((p.x - lastPos.x) / dt - pull.x, (p.y - lastPos.y) / dt - pull.y, (p.z - lastPos.z) / dt - pull.z)
        } else if (haveLast) { velEst.set(0f, 0f, 0f) }
        if (!haveLast || dt < 0f) { dt = 1f / 30f; velEst.set(0f, 0f, 0f); bestScore = 0f }
        lastPos.set(p); lastT = t; haveLast = true

        val sp = velEst.length()
        val here = de(p, zoom)
        val skin = skinAt(zoom)
        var tx: Float; var ty: Float; var tz: Float
        if (sp > 0.08f) {
            val hx = velEst.x / sp; val hy = velEst.y / sp; val hz = velEst.z / sp
            lastHeading.set(hx, hy, hz); haveHeading = true
            probeFrame++
            if (probeFrame % 3 == 1) probeCanyons(p, hx, hy, hz)
            val gate = M.smoothstep(0.08f, 0.30f, bestScore)
            tx = (bestDir.x - hx) * 0.9f * gate; ty = (bestDir.y - hy) * 0.9f * gate; tz = (bestDir.z - hz) * 0.9f * gate
        } else { tx = 0f; ty = 0f; tz = 0f; bestScore = 0f }

        // PINNED SLIDE. The hover gate parks the flight the moment the gaze runs into the bulb, and the pull
        // above switches off below 0.08 u/s — so without this the player who touches the cathedral simply stops
        // OUTSIDE it, which is the opposite of the point. When stalled within 1.6 skins of the surface, probe
        // around the last heading (or, with none, around the tangent of the outward normal) and slide toward the
        // most open direction at full strength: along the surface, into the first canyon mouth that opens. The
        // Hive solves the same problem the same way. Gaze still wins — the moment the player looks elsewhere the
        // flight speed returns and the ordinary, gentler pull takes over.
        if (sp <= 0.08f && here < 1.6f * skin) {
            val n = grad(p, zoom)                                   // outward normal
            var hx: Float; var hy: Float; var hz: Float
            if (haveHeading) { hx = lastHeading.x; hy = lastHeading.y; hz = lastHeading.z }
            else { hx = -p.x; hy = -p.y; hz = -p.z }                // nothing to go on: face the bulb's centre
            // project the heading onto the surface so the probe ring sweeps ALONG the wall, not into it
            val dn = hx * n.x + hy * n.y + hz * n.z
            hx -= n.x * dn; hy -= n.y * dn; hz -= n.z * dn
            val hl = sqrt(hx * hx + hy * hy + hz * hz)
            if (hl > 1e-4f) {
                hx /= hl; hy /= hl; hz /= hl
                probeFrame++
                if (probeFrame % 3 == 1) probeCanyons(p, hx, hy, hz)
                val w = M.smoothstep(0f, 0.12f, bestScore)          // any opening at all is better than standing still
                tx = bestDir.x * PINNED_SLIDE * w; ty = bestDir.y * PINNED_SLIDE * w; tz = bestDir.z * PINNED_SLIDE * w
            }
        }
        val k = M.ease(max(dt, 1e-3f), 0.5f)
        pull.x += (tx - pull.x) * k; pull.y += (ty - pull.y) * k; pull.z += (tz - pull.z) * k
        return out + pull
    }

    private val lastPos = Vec3(); private var lastT = 0f; private var haveLast = false
    private val velEst = Vec3()
    private val pull = Vec3()
    private var probeFrame = 0
    private val bestDir = Vec3(0f, 0f, 1f); private var bestScore = 0f
    private val lastHeading = Vec3(0f, 0f, 1f); private var haveHeading = false

    /** 7 probe directions (heading + a 22° ring) sphere-traced ≤ 1.8 u on the mirror; ≤ 42 DE evaluations. */
    private fun probeCanyons(p: Vec3, hx: Float, hy: Float, hz: Float) {
        val skin = skinAt(zoom)
        val clear = max(1.25f * skin, 0.035f)                  // a path counts only where the camera fits with margin
        // orthonormal basis around the heading
        var ax = 0f; var ay = 1f; var az = 0f
        if (abs(hy) > 0.9f) { ax = 1f; ay = 0f }
        var ux = hy * az - hz * ay; var uy = hz * ax - hx * az; var uz = hx * ay - hy * ax
        val ul = sqrt(ux * ux + uy * uy + uz * uz); ux /= ul; uy /= ul; uz /= ul
        val vx = hy * uz - hz * uy; val vy = hz * ux - hx * uz; val vz = hx * uy - hy * ux
        val rp = p.length()
        val cosR = 0.927f; val sinR = 0.375f                    // 22° ring
        val phase = (probeFrame / 3) * 0.5236f                 // the ring turns 30° per probe: 12 azimuths over 4 probes
        var best = 0f; var bx = hx; var by = hy; var bz = hz
        for (k in 0 until 7) {
            val dx: Float; val dy: Float; val dz: Float
            if (k == 0) { dx = hx; dy = hy; dz = hz } else {
                val a = phase + (k - 1) * 1.0472f
                val ca = cos(a) * sinR; val sa = sin(a) * sinR
                dx = hx * cosR + ux * ca + vx * sa; dy = hy * cosR + uy * ca + vy * sa; dz = hz * cosR + uz * ca + vz * sa
            }
            var tt = skin
            for (s in 0 until 6) {
                val d = bulbDE(p.x + dx * tt, p.y + dy * tt, p.z + dz * tt, zoom)
                if (d < clear) break
                tt += d * 0.9f
                if (tt > 1.8f) { tt = 1.8f; break }
            }
            val ex = p.x + dx * tt; val ey = p.y + dy * tt; val ez = p.z + dz * tt
            val pen = rp - sqrt(ex * ex + ey * ey + ez * ez)      // radial penetration toward the centre
            val score = pen * M.smoothstep(0.25f, 0.60f, tt)       // needs ≥ ~0.3 u of clear flight to count
            if (score > best) { best = score; bx = dx; by = dy; bz = dz }
        }
        bestScore = best; bestDir.set(bx, by, bz)
    }

    /**
     * Auto-flight (Head tracking = Off / attract): a closed loop IN FRONT of the bulb. The camera basis
     * is identity when tracking is off (always looking +z, ±13° × ±10° window), so the path must keep
     * the cathedral ahead: a horizontal ring of radius 1.1 centred at (0, 0.35, −3.1) — z ∈ [−4.2,
     * −2.0] — with a slow vertical bob. At its near point the bulb (radius ≈ 1.2 at 2 u) overfills the
     * window (the close-up view, ≈ 0.8 u clearance so the room keeps its rung), at the far side the
     * whole object sits in it, at the flanks one edge shows against open space. The heading pursues
     * the ring point 0.55 rad AHEAD of the camera's own angle (pure pursuit), so the loop is flown
     * smoothly at whatever speed the core allows and never stalls or bang-bangs around a slow
     * waypoint (the previous arc pursuit ran ahead of its waypoint and left the bulb 60° out of frame
     * most of the time). One lap ≈ 10 s at CRUISE.
     */
    override fun autoFlight(t: Float, pos: Vec3): Vec3 {
        val cx = 0f; val cz = -3.1f; val r = 1.1f
        val ang = atan2(pos.x - cx, pos.z - cz)               // camera's angle on the ring (0 = near point, +z side)
        val a = ang + 0.55f                                    // pursue a point ahead on the ring
        val wp = Vec3(cx + r * sin(a), 0.35f + 0.25f * sin(0.031f * t), cz + r * cos(a))
        val h = wp - pos
        return if (h.lengthSq() > 1e-6f) h.normalized() else Vec3(0f, 0f, 1f)
    }

    // ---------------------------------------------------------------- toy ----

    /** The sluurp hangs toward the cathedral: 0.6·normalize(−p) (§4.8). */
    override fun field(p: Vec3): Vec3 {
        val l = p.length()
        return if (l > 1e-4f) p * (-0.6f / l) else Vec3()
    }

    // ---------------------------------------------------------------- targets ----

    /**
     * Spawn volume: { |p| < 4.5 } (the core intersects the ±25° gaze cone at 1–3 u, `de > 2r + 0.05`
     * and applies the 1 + 0.08·level scale itself) ∩ DEEPER IN — either inside the bulb's canyon zone
     * (|p| < 1.30·swell, i.e. among the lobes) or at least 0.25 u closer to the centre than the camera,
     * so the eyes always lead INTO the object — ∩ line-of-sight from the camera (a fractal eye hidden
     * behind a lobe cannot be seen, and "see it, fly to it" is the whole loop). Looking away from the
     * bulb rejects everything and the core's gaze-ray fallback takes over (gaze always wins). The sight
     * test is a short sphere trace on the mirror (≤ 20 evaluations per attempt).
     */
    override fun safeVolume(cam: Pose): Volume {
        val rc = cam.pos.length()
        val inner = 1.30f * swellAt(zoom)
        return Volume { p ->
            val r = p.length()
            r < SAFE_R && (r < inner || r < rc - 0.25f) && visible(cam.pos, p)
        }
    }

    private fun visible(from: Vec3, to: Vec3): Boolean {
        val dx = to.x - from.x; val dy = to.y - from.y; val dz = to.z - from.z
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-3f) return true
        val ux = dx / len; val uy = dy / len; val uz = dz / len
        val end = len - 0.15f                                  // stop short of the target's own sphere
        var t = skinAt(zoom)                                   // start outside the eye's (zoom-dependent) flight skin
        var n = 0
        while (t < end && n < 20) {
            val d = bulbDE(from.x + ux * t, from.y + uy * t, from.z + uz * t, zoom)
            if (d < 0.02f) return false
            t += max(d * 0.9f, 0.05f)
            n++
        }
        return true
    }

    /** The Nave's growth lives entirely in de(): power, twist and iterations follow the continuous zoom. */
    override fun onLevelChanged(level: Int) {}

    private companion object {
        const val SAFE_R = 4.5f
        /** Iterations added within refineT of the surface (shader: REFINE_EXTRA). */
        const val REFINE_EXTRA = 2
        /** 8 + REFINE_EXTRA (shader: ITER_CAP). */
        const val ITER_CAP = 10
    }
}
