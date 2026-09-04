package com.tapfractal.scenes

import android.util.Log
import com.tapfractal.M
import com.tapfractal.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Scene 4 — THE HIVE (APOLLONIAN BLOOM), DESIGN §9.4, retooled for the DIVE CONTRACT (travel INTO it).
 *
 * Pitch dark; the sluurp is the only lamp. The world is an Apollonian sphere packing at world
 * scale 4 (one unit cell = 8 u). [de] is the CPU mirror of `apollonianDE` in scene4.frag —
 * same formula, same continuous-zoom parameters, same coordinate frame, music inflate dropped
 * (gBloom = 0). The flight controller (hover / slide), the sluurp bounce and the target spawner all
 * depend on it agreeing with the GPU.
 *
 * Continuous zoom (= uZoom = hit levels eased + diveRate·travel):
 *   s     = 1.02 + 0.09·min(zoom, 7) + 0.02·clamp(zoom − 7, 0, 5)     foam → bubbles → crystal
 *   itF   = clamp(5 + 0.5·zoom, 5, 9); n = int(itF); f = itF − n     n full iterations + f of the next fold
 *           (the next generation of spheres inflates smoothly — a continuous dive cannot afford pops)
 *   twist = rotY(0.2·min(zoom, 6)) · rotX(0.11·min(zoom, 6))           capped (no endless wall sweep)
 *   no xz mirror (the design's L ≥ 4 |p.xz| was a whole-world pop)
 *   yfold = |fract(y / s + 0.5) − 0.5| · s   after the twist: the sphere layers are STACKED with period s (cell)
 *           so the tallest bubbles (|y| = s/2) kiss their mirror images — there is no outside any more (fixer
 *           2026-09-03: the 4 u band between the layers was black sky the flight cruised into)
 *   DE    = WS · 0.25 · mix(|p.y|, |fold(p).y|, f) / scale              conservative lower bound; the SHADER tightens
 *           never-inverted points (scale = 1) to max(0.25·|y|, min(|y|, |p| − √s)) so a grazing floor does not starve
 *           its march — the CPU keeps the uniform 0.25× (≤ the shader's DE everywhere; see evalDE)
 *
 * Travelling into it: `diveRate` adds ~1 zoom level per 10 s of CRUISE; `skinAt` shrinks fast (the DE is a
 * 0.25× bound, so the default 0.20 skin is 0.8 u of true clearance — too far to ever enter a crevice);
 * [current] finds the most open direction within 20° of the gaze and pulls the flight into that gap
 * (≤ 0.35 u/s, and forward through narrow throats where the core's hover gate would stall); when the gaze is
 * buried in a bubble (hovering at the skin, nothing open within 0.8 u) the ring widens to 45° and the pull SLIDES
 * the camera along the surface until the gaze sees past it; from an open pocket it drifts toward the nearest
 * bubble cluster. Targets spawn in a DE band that puts them inside the gaps between spheres rather than out in
 * the open. A `hive foam:` log line every 5 s reports the camera's clearance (the acceptance evidence).
 */
class Hive : Scene(4, "Hive", "shaders/scene4.frag", "music/scene4.mp3") {
    override val is2D = false
    override val palette = Palette(
        a = Vec3(0.85f, 0.25f, 0.80f), b = Vec3(0.15f, 0.25f, 0.20f), c = Vec3(1f, 1f, 1f),
        d0 = Vec3(0.00f, 0.55f, 0.35f), zshift = Vec3(0.00f, 0.035f, -0.025f),
        coreTint = Vec3(1.00f, 0.90f, 0.75f), bloomTint = Vec3(1.0f, 0.35f, 0.9f), bloomStrength = 0.45f,   // bloom haze magenta, not pink-white (it desaturated the lit floor to meanSat 0.58)
    )
    /** §7.2: Soft 0.10 s / 1.000 / +0.003 · Wild 0.28 s / 0.985 (inward suction) / +0.010 · centre (0,0). */
    override val echo = EchoParams(0.10f, 1.000f, 0.003f, 0.28f, 0.985f, 0.010f)
    override val speedScale = 0.9f          // dark, close walls — but it should still feel like flying
    override val maxRung = 6
    override val interestConeDeg = 180f     // the packing surrounds you: never "look away"
    override val chimeRootHz = 110f         // A2

    // ---- DIVE CONTRACT ----
    /** Zoom levels per world unit of forward travel: CRUISE 1.0 u/s × speedScale 0.9 → 0.099 level/s ≈ 1 level per 10 s. */
    override val diveRate: Float = 0.11f
    /**
     * Hover/slide skin in DE units. The DE is a conservative bound (0.25× against the bare planes, ~0.7× against
     * the spheres — measured), so the default 0.20 keeps the eye 0.3–0.8 u from everything and the hover gate
     * (which scales with the skin) crawls along the foam: 0.12 (0.15–0.5 u true) at L0 → 0.06 at L2.8 → floor 0.025.
     */
    override fun skinAt(zoom: Float): Float = (0.12f * 0.78f.pow(zoom)).coerceAtLeast(0.025f)

    companion object {
        private const val TAG = "TapFractal"
        const val WS = 4f
        const val TRAPK_NONE = 4f
        private const val PROBE_N = 24       // void finder: 24³ probes over one 8 u cell
        private const val PROBE_CELL = 8f
        private const val VOID_DE = 0.30f    // wanted DE at the start / auto-flight centre (≈ 1.2 u true: tether room, packing well inside lamp reach; 0.40 read as black)
        private const val VOID_MIN = 0.16f   // below this the spot is opened up with gradient nudges
        private const val PULL_MAX = 0.60f   // the core's current clamp (DIVE CONTRACT)
        private const val RING_N = 6         // gap finder: gaze + a ring of 6 directions at RING_DEG
        private const val RING_DEG = 20f
        private const val RING_PINNED_DEG = 45f  // the ring while pinned against a wall: the bubble's flank, not the same wall six times
        private const val CLUSTER_PULL = 0.20f   // u/s toward the nearest bubble cluster from an open pocket
        private const val FOAM_LOG_S = 5f
        private const val REACH_MAX = 3f     // u
        private const val RESCAN_DZ = 1.5f   // re-run the void finder every 1.5 levels of dive
    }

    // ---- scratch written by evalDE (GL thread only; the core never calls scenes from elsewhere) ----
    private var lastTrapK = TRAPK_NONE
    private var lastTrapR = 0f

    // ---- cached navigation state (the Scene API gives field()/current() no camera or gaze, so we remember it) ----
    private val camPos = Vec3(0f, 2f, 0f)
    private val prevCamPos = Vec3(0f, 2f, 0f)
    private var prevT = 0f
    private var haveCam = false
    private val gazeEst = Vec3(0f, 0f, 1f)
    private var lastLevel = 0

    // ---- gap pull (eased current returned by [current]) ----
    private val pull = Vec3()
    private val bestDir = Vec3()
    private val gradTmp = Vec3()

    // ---- largest void near the player (start pose, auto-flight centre, post-level-change nudge) ----
    private val voidCentre = Vec3(0f, 2f, 0f)
    private var voidRadius = 0.5f
    private val lastAuto = Vec3(0f, 0f, 1f)

    // ------------------------------------------------------------------ DE mirror

    override fun de(p: Vec3, zoom: Float): Float = evalDE(p.x, p.y, p.z, zoom)

    /** Allocation-free mirror of `apollonianDE` (gBloom = 0). Also leaves trapK/trapR in lastTrapK/lastTrapR. */
    private fun evalDE(px: Float, py: Float, pz: Float, zoom: Float): Float {
        val zs = min(zoom, 7f); val zt = min(zoom, 6f)
        val s = 1.02f + 0.09f * zs + 0.02f * M.clamp(zoom - 7f, 0f, 5f)
        val itF = M.clamp(5f + 0.5f * zoom, 5f, 9f)
        val n = itF.toInt()                                          // GLSL int(): truncation, same for positives
        val f = itF - n
        var x = px / WS; var y = py / WS; var z = pz / WS
        // gTwist = rotY(0.2·zt) · rotX(0.11·zt) → apply rotX first, then rotY (GLSL column-major matrices)
        val ax = 0.11f * zt; val cx = cos(ax); val sx = sin(ax)
        val y1 = cx * y - sx * z; val z1 = sx * y + cx * z          // rotX: (x, c·y − s·z, s·y + c·z)
        y = y1; z = z1
        val ay = 0.2f * zt; val cy = cos(ay); val sy = sin(ay)
        val x2 = cy * x + sy * z; val z2 = -sy * x + cy * z         // rotY: (c·x + s·z, y, −s·x + c·z)
        x = x2; z = z2
        y = abs(fract(y / s + 0.5f) - 0.5f) * s                     // stack the layers: mirror y with period s (as the shader)
        var scale = 1f; var trapK = TRAPK_NONE; var trapR = Float.MAX_VALUE
        for (i in 0 until n) {
            x = -1f + 2f * fract(0.5f * x + 0.5f)
            y = -1f + 2f * fract(0.5f * y + 0.5f)
            z = -1f + 2f * fract(0.5f * z + 0.5f)
            val r2 = max(x * x + y * y + z * z, 1e-7f)
            val k = max(s / r2, 1f)
            x *= k; y *= k; z *= k; scale *= k
            if (k > 1f) trapK = min(trapK, k - 1f)
            trapR = min(trapR, r2)
        }
        // fractional iteration: blend toward the next fold (the last inversion never changes |p.y|/scale)
        val qx = -1f + 2f * fract(0.5f * x + 0.5f)
        val qy = -1f + 2f * fract(0.5f * y + 0.5f)
        val qz = -1f + 2f * fract(0.5f * z + 0.5f)
        val r2q = max(qx * qx + qy * qy + qz * qz, 1e-7f)
        val kq = max(s / r2q, 1f)
        if (kq > 1f) trapK += (min(trapK, kq - 1f) - trapK) * f
        trapR += (min(trapR, r2q) - trapR) * f
        lastTrapK = trapK; lastTrapR = trapR
        val ay0 = abs(y)
        val dy = ay0 + (abs(qy) - ay0) * f
        // DELIBERATELY the uniform 0.25× bound (the shader tightens it to the exact plane distance for never-inverted
        // points so its march does not starve on a grazing floor; CORE_API §9.7). The CPU bound is ≤ the shader's
        // everywhere, so nothing the flight/toy/spawner does can clip the visible surface — and the navigation
        // (skin, hover gate, gap finder) needs a bound that scales UNIFORMLY with the true distance: with the tight
        // bound the camera hovered 0.09 u over a bare floor and every reach probe ran into the 4× drop at the
        // inversion-sphere boundary, so the gap finder saw walls in all six directions and parked for 30 s.
        return WS * 0.25f * dy / scale
    }

    private fun fract(v: Float): Float = v - floor(v)

    // ------------------------------------------------------------------ flight

    /** Mouth of the sphere layer near the player (void finder at VOID_DE), heading toward the nearest contact glint. */
    override fun startPose(): Pose {
        val z = zoom
        // first entry: the origin cell; re-entry: the cell around where the player last was
        val around = if (haveCam) camPos else Vec3(0f, 0f, 0f)
        findVoid(around, z)
        val pos = voidCentre.copy()
        val heading = glintHeading(pos, z)
        lastAuto.set(heading)
        camPos.set(pos); prevCamPos.set(pos); haveCam = true
        gazeEst.set(heading)
        pull.set(0f, 0f, 0f)
        lastLevel = level
        return Pose(pos, heading)
    }

    /**
     * The gap pull (DIVE CONTRACT): the most open direction within the ring wins — the flight is steered
     * laterally into that gap, and pushed forward through narrow throats (where the core's hover gate would
     * otherwise stall it) as long as the gaze direction itself is open. |v| ≤ 0.35 u/s, eased (τ 0.25 s), and
     * the candidates are all inside the 30° cone, so the gaze always wins beyond it.
     *
     * PINNED (fixer 2026-09-03): with the gaze buried in a bubble the flight hovered at the skin, the 20° ring
     * saw the same wall six times and nothing moved — the picture was one bubble's skin for as long as the head
     * stayed put. Now the ring widens to 45° and the pull slides the camera ALONG the surface (the way the gaze
     * leans; square to the wall, toward the widest ring direction), so within ~3 s on a 1 u bubble the gaze sees
     * past it and the cruise resumes. From an open pocket (de ≥ 0.26 ≈ 1 u+ of clearance in every direction)
     * a gentle drift leads toward the nearest bubble cluster. Also caches the camera position for [field],
     * estimates the gaze from the flight's own motion, and logs the camera's clearance every 5 s.
     */
    override fun current(p: Vec3, t: Float): Vec3 {
        val dt = if (haveCam) M.clamp(t - prevT, 1e-3f, 0.1f) else 1f / 30f
        if (haveCam) {
            // motion-based gaze estimate: the flight heading follows the gaze (τ 0.30 s), so the direction of
            // travel is a good proxy whenever the FLIGHT is moving — our own pull of the last frame is removed,
            // and the threshold sits above whatever part of it the core may have clamped away
            val dx = p.x - prevCamPos.x - pull.x * dt
            val dy = p.y - prevCamPos.y - pull.y * dt
            val dz = p.z - prevCamPos.z - pull.z * dt
            val l2 = dx * dx + dy * dy + dz * dz
            val thr = max(0.15f, 0.6f * pull.length()) * dt
            if (l2 > thr * thr) {
                val inv = 1f / sqrt(l2)
                gazeEst.set(dx * inv, dy * inv, dz * inv)
            }
        }
        prevCamPos.set(p); camPos.set(p); haveCam = true; prevT = t
        val z = zoom
        scanVoidSlice()                                    // one slice per frame of a pending void scan
        if (scanSlice >= PROBE_N && abs(z - scanZoom) > RESCAN_DZ) beginVoidScan(p, z)   // the dive changed the packing

        // ---- gap finder: reach of the gaze and of a slowly rotating ring of 6 directions ----
        val gx = gazeEst.x; val gy = gazeEst.y; val gz = gazeEst.z
        // orthonormal basis (u, v) ⊥ gaze
        var ux: Float; var uy: Float; var uz: Float
        if (abs(gy) < 0.9f) { ux = gz; uy = 0f; uz = -gx } else { ux = 0f; uy = -gz; uz = gy }  // gaze × up  /  gaze × x
        val ul = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-6f); ux /= ul; uy /= ul; uz /= ul
        val vx = gy * uz - gz * uy; val vy = gz * ux - gx * uz; val vz = gx * uy - gy * ux
        val here = evalDE(p.x, p.y, p.z, z)
        val skin = skinAt(z)
        val reachG = reach(p, gx, gy, gz, z)
        // pinned = hovering at the skin with nothing open along the gaze
        val pinned = M.clamp((2.5f * skin - here) / skin, 0f, 1f) * M.clamp((0.8f - reachG) / 0.4f, 0f, 1f)
        val ringDeg = RING_DEG + (RING_PINNED_DEG - RING_DEG) * pinned
        val ct = cos(M.rad(ringDeg)); val st = sin(M.rad(ringDeg))
        var bestReach = reachG + 0.2f; var found = false
        val ph0 = t * 0.9f
        for (j in 0 until RING_N) {
            val ph = ph0 + j * (M.TAU / RING_N)
            val cp = cos(ph); val sp = sin(ph)
            val dx = gx * ct + (ux * cp + vx * sp) * st
            val dy = gy * ct + (uy * cp + vy * sp) * st
            val dz = gz * ct + (uz * cp + vz * sp) * st
            val r = reach(p, dx, dy, dz, z)
            if (r > bestReach) { bestReach = r; found = true; bestDir.set(dx, dy, dz) }
        }
        // lateral steer into the gap
        var tx = 0f; var ty = 0f; var tz = 0f
        if (found && bestReach > 0.4f) {
            val dd = bestDir.x * gx + bestDir.y * gy + bestDir.z * gz
            var lx = bestDir.x - gx * dd; var ly = bestDir.y - gy * dd; var lz = bestDir.z - gz * dd
            val ll = sqrt(lx * lx + ly * ly + lz * lz)
            if (ll > 1e-5f) {
                val w = M.clamp((bestReach - reachG - 0.2f) / 0.6f, 0f, 1f) * PULL_MAX / ll
                lx *= w; ly *= w; lz *= w
                tx += lx; ty += ly; tz += lz
            }
        }
        // forward through a narrow throat (de < 0.24 ≈ 1 u true) when the gaze direction is open: the core's
        // hover gate crawls at ~0.1 u/s whenever everything is within a few skins — this is what carries you in
        val narrow = 1f - M.clamp((here - 0.04f) / 0.20f, 0f, 1f)
        val open = M.clamp((reachG - 0.2f) / 0.5f, 0f, 1f)
        val fw = PULL_MAX * narrow * open
        tx += gx * fw; ty += gy * fw; tz += gz * fw
        // slide along the wall while pinned: the hover gate parks the flight near a wall, so this is what carries
        // the camera around the bubble; direction = the gaze's tangential lean, or the widest ring direction's.
        // Only AT the skin (not inside it) and only as far as the slide direction is open (a current bypasses the
        // core's hover gate, and the first cut rammed the camera into a crevice narrower than the skin: 15 s of
        // safety snaps at 4–18 u/s²)
        val atSkin = M.clamp((here - 0.6f * skin) / (0.4f * skin), 0f, 1f)
        if (pinned * atSkin > 0.02f) {
            // outward normal: 4 tetrahedron taps straight through evalDE into a scratch vector (Scene.grad allocates
            // six Vec3 per call, and this runs every frame while pinned)
            val g = gradTmp; val ge = 0.002f
            val d1 = evalDE(p.x + ge, p.y - ge, p.z - ge, z); val d2 = evalDE(p.x - ge, p.y - ge, p.z + ge, z)
            val d3 = evalDE(p.x - ge, p.y + ge, p.z - ge, z); val d4 = evalDE(p.x + ge, p.y + ge, p.z + ge, z)
            g.set(d1 - d2 - d3 + d4, -d1 - d2 + d3 + d4, -d1 + d2 - d3 + d4)
            val gl = sqrt(g.x * g.x + g.y * g.y + g.z * g.z)
            if (gl > 1e-9f) g.set(g.x / gl, g.y / gl, g.z / gl) else g.set(0f, 1f, 0f)
            val gn = g.x * gx + g.y * gy + g.z * gz
            var sx = gx - g.x * gn; var sy2 = gy - g.y * gn; var sz = gz - g.z * gn
            var sl = sqrt(sx * sx + sy2 * sy2 + sz * sz)
            if (sl < 0.35f && found) {
                val bn = g.x * bestDir.x + g.y * bestDir.y + g.z * bestDir.z
                sx = bestDir.x - g.x * bn; sy2 = bestDir.y - g.y * bn; sz = bestDir.z - g.z * bn
                sl = sqrt(sx * sx + sy2 * sy2 + sz * sz)
            }
            if (sl > 1e-4f) {
                sx /= sl; sy2 /= sl; sz /= sl
                val ahead = reach(p, sx, sy2, sz, z)                       // lookahead along the slide
                val w = PULL_MAX * pinned * atSkin * M.clamp((ahead - 2f * skin) / (3f * skin), 0f, 1f)
                tx += sx * w; ty += sy2 * w; tz += sz * w
            }
        }
        // parked in a pocket (pinned, and nothing above produced a pull for 2 s — a cusp between kissing bubbles
        // where every ring direction is short): leave along the most open of the ring and the 6 axis directions
        val tl0 = sqrt(tx * tx + ty * ty + tz * tz)
        stuckS = if (pinned > 0.5f && tl0 < 0.05f) stuckS + dt else 0f
        if (stuckS > 2f) {
            var ex = bestDir.x; var ey = bestDir.y; var ez = bestDir.z; var er = if (found) bestReach else 0f
            for (k in 0 until 6) {
                val dx = if (k == 0) 1f else if (k == 1) -1f else 0f
                val dy = if (k == 2) 1f else if (k == 3) -1f else 0f
                val dz = if (k == 4) 1f else if (k == 5) -1f else 0f
                val r = reach(p, dx, dy, dz, z)
                if (r > er) { er = r; ex = dx; ey = dy; ez = dz }
            }
            val w = PULL_MAX * M.clamp((er - 1.5f * skin) / (2f * skin), 0f, 1f)
            tx += ex * w; ty += ey * w; tz += ez * w
            escapeS += dt
        }
        // cluster pull: from an open pocket drift toward the nearest bubble cluster (the cell centre of the
        // twisted, y-stacked frame) — the foam is where the bubbles are, and the lamp only reaches 2.6 u
        val openW = M.clamp((here - 0.26f) / 0.12f, 0f, 1f)
        if (openW > 0f) {
            clusterCentre(p, z, tmpCluster)
            val cx = tmpCluster.x - p.x; val cy = tmpCluster.y - p.y; val cz = tmpCluster.z - p.z
            val cl = sqrt(cx * cx + cy * cy + cz * cz)
            if (cl > 0.3f) { val w = CLUSTER_PULL * openW / cl; tx += cx * w; ty += cy * w; tz += cz * w }
        }
        // clamp + ease
        val tl = sqrt(tx * tx + ty * ty + tz * tz)
        if (tl > PULL_MAX) { val k = PULL_MAX / tl; tx *= k; ty *= k; tz *= k }
        val e = M.ease(dt, 0.25f)
        pull.x += (tx - pull.x) * e; pull.y += (ty - pull.y) * e; pull.z += (tz - pull.z) * e

        // ---- foam log: the camera's clearance, every 5 s (max de bound / max 6-axis clearance in the window) ----
        if (here > foamMaxDe) foamMaxDe = here
        foamPinnedS += pinned * dt
        if (t - foamLogT >= FOAM_LOG_S) {
            var clear6 = REACH_MAX
            clear6 = min(clear6, reach(p, 1f, 0f, 0f, z)); clear6 = min(clear6, reach(p, -1f, 0f, 0f, z))
            clear6 = min(clear6, reach(p, 0f, 1f, 0f, z)); clear6 = min(clear6, reach(p, 0f, -1f, 0f, z))
            clear6 = min(clear6, reach(p, 0f, 0f, 1f, z)); clear6 = min(clear6, reach(p, 0f, 0f, -1f, z))
            Log.i(TAG, "hive foam: de=%.3f (max %.3f / 5 s) clear6=%.2f u reachG=%.2f layerY=%.2f u pinned %.1f s escape %.1f s pull=%.2f zoom=%.2f cam=%s".format(
                here, foamMaxDe, clear6, reachG, layerHeight(p, z), foamPinnedS, escapeS, pull.length(), z, p))
            foamLogT = t; foamMaxDe = 0f; foamPinnedS = 0f; escapeS = 0f
        }

        // inside half the skin (a level change grew the packing into the player, or a crevice narrower than the
        // skin closed around the camera — the skin spring's gradient alternates between the two walls there and
        // never gets out): ESCAPE along the most open of the 6 axis directions and the ring at the full pull, and
        // drop the room's own pull (it is what led in here)
        if (here < 0.5f * skin) {
            var ex = bestDir.x; var ey = bestDir.y; var ez = bestDir.z; var er = if (found) bestReach else 0f
            for (k in 0 until 6) {
                val dx = if (k == 0) 1f else if (k == 1) -1f else 0f
                val dy = if (k == 2) 1f else if (k == 3) -1f else 0f
                val dz = if (k == 4) 1f else if (k == 5) -1f else 0f
                val r = reach(p, dx, dy, dz, z)
                if (r > er) { er = r; ex = dx; ey = dy; ez = dz }
            }
            escapeS += dt
            if (er > 0.05f) { pull.set(0f, 0f, 0f); return Vec3(ex * PULL_MAX, ey * PULL_MAX, ez * PULL_MAX) }
            val toVoid = voidCentre - p
            if (toVoid.length() > 0.05f) { pull.set(0f, 0f, 0f); return toVoid.normalized() * PULL_MAX }
        }
        return pull.copy()
    }

    private val tmpCluster = Vec3()
    private var foamLogT = 0f; private var foamMaxDe = 0f; private var foamPinnedS = 0f; private var escapeS = 0f; private var stuckS = 0f

    /** The twisted cell frame of [evalDE] (rotX then rotY, no fold) — for the cluster / layer helpers. */
    private fun twistTo(px: Float, py: Float, pz: Float, zoom: Float, out: Vec3) {
        val zt = min(zoom, 6f)
        var x = px / WS; var y = py / WS; var z = pz / WS
        val ax = 0.11f * zt; val cx = cos(ax); val sx = sin(ax)
        val y1 = cx * y - sx * z; val z1 = sx * y + cx * z; y = y1; z = z1
        val ay = 0.2f * zt; val cy = cos(ay); val sy = sin(ay)
        val x2 = cy * x + sy * z; val z2 = -sy * x + cy * z; x = x2; z = z2
        out.set(x, y, z)
    }

    /** Height of [p] above the nearest stacked layer plane (u): 0 on a floor, s/2·WS on the mirror plane between two. */
    private fun layerHeight(p: Vec3, zoom: Float): Float {
        val s = 1.02f + 0.09f * min(zoom, 7f) + 0.02f * M.clamp(zoom - 7f, 0f, 5f)
        twistTo(p.x, p.y, p.z, zoom, tmpTwist)
        return abs(fract(tmpTwist.y / s + 0.5f) - 0.5f) * s * WS
    }
    private val tmpTwist = Vec3()

    /** World position of the bubble cluster nearest to [p]: the cell centre (xz on the 2-lattice, y on the layer lattice of period s) untwisted. */
    private fun clusterCentre(p: Vec3, zoom: Float, out: Vec3) {
        val zt = min(zoom, 6f)
        val s = 1.02f + 0.09f * min(zoom, 7f) + 0.02f * M.clamp(zoom - 7f, 0f, 5f)
        twistTo(p.x, p.y, p.z, zoom, tmpTwist)
        val cxq = 2f * round(tmpTwist.x / 2f); val czq = 2f * round(tmpTwist.z / 2f); val cyq = s * round(tmpTwist.y / s)
        // untwist: rotY(−ay) then rotX(−ax)
        val ay = 0.2f * zt; val cy = cos(ay); val sy = sin(ay)
        val ux = cy * cxq - sy * czq; val uz = sy * cxq + cy * czq
        val ax = 0.11f * zt; val cx = cos(ax); val sx = sin(ax)
        val uy2 = cx * cyq + sx * uz; val uz2 = -sx * cyq + cx * uz
        out.set(ux * WS, uy2 * WS, uz2 * WS)
    }

    /** Free path from [p] along a unit direction, in u (≤ REACH_MAX), marched on the CPU DE (≤ 8 steps). */
    private fun reach(p: Vec3, dx: Float, dy: Float, dz: Float, zoom: Float): Float {
        var tt = 0.02f
        for (i in 0 until 8) {
            val d = evalDE(p.x + dx * tt, p.y + dy * tt, p.z + dz * tt, zoom)
            if (d < 0.02f) return tt
            tt += max(d, 0.03f) * 0.95f
            if (tt >= REACH_MAX) return REACH_MAX
        }
        return min(tt, REACH_MAX)
    }

    /** Lissajous drift around the void (Head tracking = Off / attract), flown at ≤ 1 u/s by the core. */
    override fun autoFlight(t: Float, pos: Vec3): Vec3 {
        // voidRadius is the conservative DE (≈ ¼ of the visible clearance); the core's hover keeps us off the walls
        val a = M.clamp(1.6f * voidRadius, 0.3f, 1.6f)
        val tx = voidCentre.x + a * sin(0.23f * t)
        val ty = voidCentre.y + 0.5f * a * sin(0.17f * t + 1.3f)
        val tz = voidCentre.z + a * cos(0.19f * t)
        val d = Vec3(tx - pos.x, ty - pos.y, tz - pos.z)
        if (d.length() > 0.05f) lastAuto.set(d.normalized())
        return lastAuto.copy()
    }

    /** §4.8: zero extra gravity field; centre pull −0.4·(p − gazeLine(p)) toward the line the player looks along. */
    override fun field(p: Vec3): Vec3 {
        if (!haveCam) return Vec3()
        val rx = p.x - camPos.x; val ry = p.y - camPos.y; val rz = p.z - camPos.z
        val s = rx * gazeEst.x + ry * gazeEst.y + rz * gazeEst.z     // closest point on the gaze line
        val qx = camPos.x + gazeEst.x * s; val qy = camPos.y + gazeEst.y * s; val qz = camPos.z + gazeEst.z * s
        return Vec3((qx - p.x) * 0.4f, (qy - p.y) * 0.4f, (qz - p.z) * 0.4f)
    }

    /**
     * Targets live INSIDE the gaps: cone ∩ need < de(p) < need + 0.28 — clear of the walls by 2·r_t + 0.06
     * (a little more than the core's 0.05: the DE is a coarse bound) but never out in the empty mid-band
     * (de ≥ 0.5 ≈ 2 u from anything), so the hunt leads between the spheres.
     */
    override fun safeVolume(cam: Pose): Volume {
        camPos.set(cam.pos); haveCam = true
        val h = cam.heading
        if (h.lengthSq() > 1e-6f) gazeEst.set(h.normalized())          // the exact gaze, whenever the core hands it over
        val z = zoom
        val rT = max(0.055f, 0.08f * 0.97f.pow(level)) * ropeScale(z)
        val need = 2f * rT + 0.06f
        val most = need + 0.28f
        return Volume { p -> val d = de(p, z); d > need && d < most }
    }

    /**
     * Re-run the void finder around the player for the new packing. 24³ = 13,824 DE probes are 3–6 ms of
     * CPU — too much for the hit frame (which already carries the chime and the hit flash), so the scan is
     * spread over the next 24 frames, one x-slice (576 probes, ≈ 0.2 ms) per [current] call; the previous
     * void stays in use until the new one is adopted (it is only consumed by current()/autoFlight()/startPose()).
     * The zoom the packing will settle at = the current continuous zoom + the level step (the dive offset stays).
     */
    override fun onLevelChanged(level: Int) {
        val around = if (haveCam) camPos else voidCentre
        val target = zoom + (level - lastLevel)
        lastLevel = level
        beginVoidScan(around, max(target, 0f))
    }

    // ------------------------------------------------------------------ void finder

    // incremental scan state (GL thread only)
    private val scanAround = Vec3()
    private var scanZoom = 0f
    private var scanSlice = PROBE_N          // == PROBE_N → no scan in progress
    private var scanBestScore = -1e9f; private var scanBest = -1f
    private var scanBx = 0f; private var scanBy = 0f; private var scanBz = 0f

    /**
     * 24³ coarse de probes over one 8 u cell centred at [around] → the spot closest to VOID_DE, then 3
     * gradient-ascent nudges if it is tight. Result in voidCentre / voidRadius (voidRadius is the DE there).
     * Synchronous form (scene entry, where the frame stalls on the shader compile / music load anyway).
     */
    private fun findVoid(around: Vec3, zoom: Float) {
        beginVoidScan(around, zoom)
        while (scanSlice < PROBE_N) scanVoidSlice()
    }

    private fun beginVoidScan(around: Vec3, zoom: Float) {
        scanAround.set(around); scanZoom = zoom; scanSlice = 0
        scanBestScore = -1e9f; scanBest = -1f; scanBx = around.x; scanBy = around.y; scanBz = around.z
    }

    /** One x-slice (PROBE_N² probes) of the running scan; adopts the result after the last slice. */
    private fun scanVoidSlice() {
        if (scanSlice >= PROBE_N) return
        val around = scanAround; val zoom = scanZoom
        val step = PROBE_CELL / PROBE_N
        val x0 = around.x - 0.5f * PROBE_CELL + 0.5f * step
        val y0 = around.y - 0.5f * PROBE_CELL + 0.5f * step
        val z0 = around.z - 0.5f * PROBE_CELL + 0.5f * step
        // "Largest void" in the design's sense = room for the tether (anchor 1.4 u + rope) WITH the packing in
        // lamp reach — not the emptiest point (at L0 the DE maximum is the empty mid-band between the planes,
        // nothing within lamp reach and nothing to fly into). The DE is a 0.25× lower bound, so
        // de ≈ VOID_DE ≈ 1.6 u of true clearance. Score: closeness to VOID_DE, minus 0.04·distance so re-entry /
        // level changes keep you roughly where you were. Probes with de < VOID_MIN never win against one that clears it.
        val i = scanSlice
        val x = x0 + i * step; val ddx = x - around.x
        for (j in 0 until PROBE_N) {
            val y = y0 + j * step; val ddy = y - around.y
            for (k in 0 until PROBE_N) {
                val z = z0 + k * step; val ddz = z - around.z
                val d = evalDE(x, y, z, zoom)
                val dist = sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
                val score = (if (d >= VOID_MIN) -abs(d - VOID_DE) else -10f + d) - 0.04f * dist
                if (score > scanBestScore) { scanBestScore = score; scanBest = d; scanBx = x; scanBy = y; scanBz = z }
            }
        }
        scanSlice++
        if (scanSlice < PROBE_N) return
        var p = Vec3(scanBx, scanBy, scanBz); var dBest = scanBest
        if (dBest < VOID_MIN) {                                       // dense packing: open the spot up a little
            repeat(3) {
                val g = grad(p, zoom)                                 // points toward increasing de
                val q = p + g * (0.5f * max(dBest, 0.05f))
                val dq = evalDE(q.x, q.y, q.z, zoom)
                if (dq > dBest) { p = q; dBest = dq }
            }
        }
        voidCentre.set(p); voidRadius = max(dBest, 0.05f)
    }

    /**
     * Heading from [from] toward the nearest contact glint: march the 18 non-vertical lattice directions (≤ 4 u),
     * (pitch ≤ 35°), keep the hit with the smallest trapK that still leaves ≥ 0.8 u of open flight; fall back to +z.
     */
    private fun glintHeading(from: Vec3, zoom: Float): Vec3 {
        var bestScore = Float.MAX_VALUE; var bx = 0f; var by = 0f; var bz = 1f
        for (ix in -1..1) for (iy in -1..1) for (iz in -1..1) {
            if (ix == 0 && iy == 0 && iz == 0) continue
            val inv = 1f / sqrt((ix * ix + iy * iy + iz * iz).toFloat())
            val dx = ix * inv; val dy = iy * inv; val dz = iz * inv
            if (abs(dy) > 0.6f) continue                              // ≤ 35° pitch: cruise along the layer, never straight into it
            var t = 0f; var hitT = -1f; var trapK = TRAPK_NONE
            for (n in 0 until 40) {
                val d = evalDE(from.x + dx * t, from.y + dy * t, from.z + dz * t, zoom)
                if (d < 0.03f) { hitT = t; trapK = lastTrapK; break }
                t += d * 0.9f
                if (t > 4f) break
            }
            if (hitT < 0.8f) continue                                 // no hit, or a wall too close to fly toward
            val score = trapK + 0.05f * hitT                          // smallest glint first, nearer wins ties
            if (score < bestScore) { bestScore = score; bx = dx; by = dy; bz = dz }
        }
        return Vec3(bx, by, bz).normalized()
    }
}
