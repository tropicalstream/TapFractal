package com.tapfractal.scenes

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
 * Scene 3 — "The Throat" (MENGER TUNNEL), DESIGN §9.3.
 *
 * An infinite kaleidoscopic-IFS Menger lattice at world scale 2 (cells 4 u, central throat
 * 0.667 u half-wide at zoom 0). [de] is the exact CPU mirror of `mengerDE()` in `scene3.frag`:
 * the same cell tiling (all three axes), cell spin, zoom narrowing, per-fold twist (folds ≥ 1),
 * fold count, octahedral crystal fold (offset plane, blends in over zoom 3→4) and fold scale 3 —
 * with the music-only terms (bass widening, beat kick of scale/twist) at zero, as CORE_API §5
 * requires. The flight controller, the sluurp bounce and target spawning all rely on this
 * agreeing with the GPU.
 *
 * DIVE CONTRACT: everything structural is a function of the CONTINUOUS [zoom] the core writes
 * (hit levels eased + the travel dive: [diveRate] = 1 level per 12.8 u of forward travel =
 * 1 level per 8 s at CRUISE with speedScale 1.6). Per-zoom mapping:
 *   folds  = clamp(3 + floor(zoom), 3, 7)            twist/fold = min(0.35, 0.05·zoom), folds ≥ 1 only
 *   spin   = 0.10·t + 0.10·min(zoom,7)·sin(0.5·t)    (peak rate 0.10 + 0.05·L rad/s = the §9.3 table)
 *   oct    = 0.9/√2 · clamp(zoom − 3, 0, 1)          (L4+: crystalline)
 *   narrow = max(0.75, 0.96^zoom)                    the throat narrows slightly as you dive
 * Twisting fold 0 shut the throat by zoom 5–6 (measured open radius 0.05 → 0 u), which the dive
 * reaches in under a minute; with the twist on folds ≥ 1 the open radius is 0.64 (z0) · 0.51 (z2) ·
 * 0.41 (z4) · 0.34 (z6) · 0.31 u (z8+), narrowing included — always above the shrinking skin.
 *
 * Lane assist ([current]): when the flight is travelling within ~30° of the throat axis (±z) it is
 * pulled toward the nearest z-throat axis at ≤ 0.35 u/s (fades out near the walls and at hover,
 * so a wall you fly up to stays reachable). The scene has no gaze input, so the travel direction is
 * estimated from successive positions — the heading eases to the gaze with τ 0.3 s, so it IS the
 * gaze to within a few degrees whenever the flight is moving.
 */
class Throat : Scene(3, "Throat", "shaders/scene3.frag", "music/scene3.mp3") {
    override val is2D = false
    /** Cosine palette (echo tint derivation only — the walls are lit by the fixed saturated set in the shader);
     *  core tint a saturated mint so hot cores never go pale; bloom tint cyan-lime, kind to the magenta accents. */
    override val palette = Palette(
        a = Vec3(0.30f, 0.75f, 0.60f), b = Vec3(0.30f, 0.25f, 0.40f), c = Vec3(1f, 1f, 1f),
        d0 = Vec3(0.30f, 0.00f, 0.60f), zshift = Vec3(0.00f, 0.035f, -0.025f),
        coreTint = Vec3(0.45f, 1.00f, 0.65f), bloomTint = Vec3(0.5f, 1.0f, 0.9f), bloomStrength = 0.40f,
    )
    /** Soft 0.12 s / 1.015 / 0 · Wild 0.35 s / 1.045 / 0 · centre = the vanishing point (0,0) — hyperspace streaks. */
    override val echo = EchoParams(0.12f, 1.015f, 0f, 0.35f, 1.045f, 0f)
    override val speedScale = 1.6f          // the rush room (cells are 4 u long)
    override val maxRung = 7
    override val interestConeDeg = 180f     // no look-away: the lattice is everywhere
    override val chimeRootHz = 164.8f       // E3
    /** Zoom levels per world unit of forward travel: 1 level per 12.8 u = 8 s at CRUISE (1.0 u/s × 1.6). */
    override val diveRate = 1f / 12.8f

    companion object {
        const val WS = 2f                   // world scale: lattice cell = 2·WS = 4 u
        const val CELL = 4f
        const val OCT_W = 0.9f              // octahedral fold acts where x + y < OCT_W (sorted frame, folds ≥ 1)
        const val R2 = 0.70710678f
        const val SCALE = 3f                // fold scale (the shader adds 0.5·beat² — GPU only)
        const val NARROW_MIN = 0.75f        // the throat never narrows below 0.75×
        const val PULL_MAX = 0.60f          // lane-assist pull, u/s (the core's current() clamp)

        fun foldsFor(zoom: Float): Int = (3 + floor(zoom).toInt()).coerceIn(3, 7)
        fun twistFor(zoom: Float): Float = min(0.35f, 0.05f * zoom)
        fun spinFor(zoom: Float, t: Float): Float = 0.10f * t + 0.10f * min(zoom, 7f) * sin(0.5f * t)
        fun octFor(zoom: Float): Float = OCT_W * R2 * (zoom - 3f).coerceIn(0f, 1f)
        /** Throat narrowing with zoom (= the shader's `narrow`; the GPU multiplies in the bass widening too). */
        fun narrowFor(zoom: Float): Float = max(NARROW_MIN, 0.96f.pow(max(zoom, 0f)))
    }

    // ---- per-(zoom, time) parameter cache: de() is called ~10-50×/frame with the same pair ----
    private var cZoom = Float.NaN
    private var cTime = Float.NaN
    private var cFolds = 3
    private var cOct = 0f
    private var cNarrow = 1f
    private var cSpinC = 1f; private var cSpinS = 0f
    private var cTwC = 1f; private var cTwS = 0f

    private fun prepare(zoom: Float, t: Float) {
        if (zoom == cZoom && t == cTime) return
        cZoom = zoom; cTime = t
        cFolds = foldsFor(zoom)
        cOct = octFor(zoom)
        cNarrow = narrowFor(zoom)
        val sp = spinFor(zoom, t); cSpinC = cos(sp); cSpinS = sin(sp)
        val tw = twistFor(zoom); cTwC = cos(tw); cTwS = sin(tw)
    }

    /** CPU mirror of the shader DE at continuous depth [zoom] and the core-maintained [time]. */
    override fun de(p: Vec3, zoom: Float): Float = de(p, zoom, time)

    /** Same, at an explicit time (the shader's uTime). Lower bound of the true distance (verified offline). */
    fun de(p: Vec3, zoom: Float, t: Float): Float {
        prepare(zoom, t)
        // cell spin about the throat axis, narrow the throat, then tile the lattice on all three axes
        var x = cSpinC * p.x - cSpinS * p.y
        var y = cSpinS * p.x + cSpinC * p.y
        var z = p.z
        x /= cNarrow; y /= cNarrow
        x = m4(x) / WS; y = m4(y) / WS; z = m4(z) / WS
        var s = 1f
        for (i in 0 until cFolds) {
            x = abs(x); y = abs(y); z = abs(z)
            if (x < y) { val tmp = x; x = y; y = tmp }            // sort x ≥ y ≥ z
            if (x < z) { val tmp = x; x = z; z = tmp }
            if (y < z) { val tmp = y; y = z; z = tmp }
            if (i >= 1) {
                val dd = (x + y) * R2 - cOct                       // octahedral crystal fold (no-op while cOct = 0)
                if (dd < 0f) { x -= 2f * dd * R2; y -= 2f * dd * R2 }
                val rx = cTwC * x - cTwS * y                       // per-fold twist (folds ≥ 1: keeps the throat open)
                val ry = cTwS * x + cTwC * y
                x = rx; y = ry
            }
            x = x * SCALE - (SCALE - 1f)
            y = y * SCALE - (SCALE - 1f)
            z = z * SCALE - (SCALE - 1f)
            if (z < -1f) z += 2f
            s *= SCALE
        }
        val qx = abs(x) - 1f; val qy = abs(y) - 1f; val qz = abs(z) - 1f
        val mx = max(qx, 0f); val my = max(qy, 0f); val mz = max(qz, 0f)
        return (sqrt(mx * mx + my * my + mz * mz) + min(max(qx, max(qy, qz)), 0f)) / s * WS * cNarrow
    }

    /** mod(v + WS, 2·WS) − WS with GLSL floor semantics (∈ [−WS, WS)). */
    private fun m4(v: Float): Float { val t = v + WS; return t - CELL * floor(t / CELL) - WS }

    /** On the throat axis, half a cell in: de = 0.667 u (> 0.20 skin), heading down +z. */
    override fun startPose() = Pose(Vec3(0f, 0f, 0.5f), Vec3(0f, 0f, 1f))

    /** Head tracking Off / attract: down +z with a lateral wobble (DESIGN §3.5). */
    override fun autoFlight(t: Float, pos: Vec3): Vec3 =
        Vec3(0.05f * sin(0.7f * t), 0.05f * cos(0.53f * t), 1f).normalized()

    override fun onEnter() { prevT = Float.NaN }

    // ---- lane assist: pull the flight onto the throat axis while it is travelling along it ----
    private val prevP = Vec3()
    private var prevT = Float.NaN

    /**
     * Pull toward the nearest z-throat axis, ≤ [PULL_MAX] u/s, only while the travel direction (estimated
     * from successive positions, i.e. the eased gaze) is within ~30° of ±z, fading in from hover
     * (no drag while parked at a wall) and out toward the wall (the pull acts on the inner ~2/3 of the
     * throat, never inside the side voids). Gaze always wins: the flight runs at 1.6 u/s at CRUISE.
     */
    override fun current(p: Vec3, t: Float): Vec3 {
        val dt = t - prevT
        val vx = (p.x - prevP.x) / dt; val vy = (p.y - prevP.y) / dt; val vz = (p.z - prevP.z) / dt
        prevP.set(p); prevT = t
        if (dt.isNaN() || dt <= 0f || dt > 0.5f) return Vec3()  // first frame, scene re-entry, paused
        val sp = sqrt(vx * vx + vy * vy + vz * vz)
        if (sp < 0.25f) return Vec3()
        val gAxis = M.smoothstep(0.866f, 0.95f, abs(vz) / sp)  // within 30° of the axis, full inside 18°
        val gSpeed = M.smoothstep(0.30f, 0.90f, sp)             // hover → no pull
        if (gAxis * gSpeed <= 0f) return Vec3()
        prepare(zoom, t)
        // nearest z-throat axis in the spun lattice (the axes sit at (4k, 4m) in the spun xy frame)
        val qx = cSpinC * p.x - cSpinS * p.y
        val qy = cSpinS * p.x + cSpinC * p.y
        val dx = qx - CELL * round(qx / CELL)
        val dy = qy - CELL * round(qy / CELL)
        val r = sqrt(dx * dx + dy * dy)
        if (r < 1e-4f) return Vec3()
        val rw = WS / 3f * cNarrow                                // throat half-width at this zoom
        val m = PULL_MAX * M.smoothstep(0.04f, 0.20f, r) * (1f - M.smoothstep(0.55f * rw, 0.85f * rw, r)) * gAxis * gSpeed
        if (m <= 0f) return Vec3()
        val ux = -dx / r; val uy = -dy / r                        // toward the axis, spun frame
        val wx = cSpinC * ux + cSpinS * uy                        // back to world (inverse spin)
        val wy = -cSpinS * ux + cSpinC * uy
        return Vec3(wx * m, wy * m, 0f)
    }

    /** Sluurp field: turbulence 0.25·noise3(p + t) (signed, ±0.25 u/s² per axis) — the kite tugs in the wind. */
    override fun field(p: Vec3): Vec3 {
        val t = time
        return Vec3(
            0.5f * (vnoise(p.x + t, p.y, p.z) - 0.5f),
            0.5f * (vnoise(p.x + 11.3f, p.y + t, p.z + 7.7f) - 0.5f),
            0.5f * (vnoise(p.x + 23.1f, p.y + 5.9f, p.z + t) - 0.5f),
        )
    }

    /** Targets sit anywhere in the voids (the core adds the ±25° cone, 1–3 u and de(p) > 2·r_t + 0.05). */
    override fun safeVolume(cam: Pose): Volume = Volume.ALL

    /** All per-level structure derives from the continuous zoom; nothing to precompute here. */
    override fun onLevelChanged(level: Int) {}

    // ---- value noise (a port of common.glsl's hash31/noise3; CPU-only, feeds the sluurp field) ----
    private fun fract(v: Float): Float = v - floor(v)
    private fun hash31(x: Float, y: Float, z: Float): Float {
        var px = fract(x * 0.1031f); var py = fract(y * 0.1031f); var pz = fract(z * 0.1031f)
        val d = px * (pz + 31.32f) + py * (py + 31.32f) + pz * (px + 31.32f)
        px += d; py += d; pz += d
        return fract((px + py) * pz)
    }
    private fun vnoise(x: Float, y: Float, z: Float): Float {
        val ix = floor(x); val iy = floor(y); val iz = floor(z)
        var fx = x - ix; var fy = y - iy; var fz = z - iz
        fx = fx * fx * (3f - 2f * fx); fy = fy * fy * (3f - 2f * fy); fz = fz * fz * (3f - 2f * fz)
        val c000 = hash31(ix, iy, iz);           val c100 = hash31(ix + 1f, iy, iz)
        val c010 = hash31(ix, iy + 1f, iz);      val c110 = hash31(ix + 1f, iy + 1f, iz)
        val c001 = hash31(ix, iy, iz + 1f);      val c101 = hash31(ix + 1f, iy, iz + 1f)
        val c011 = hash31(ix, iy + 1f, iz + 1f); val c111 = hash31(ix + 1f, iy + 1f, iz + 1f)
        val x00 = c000 + (c100 - c000) * fx; val x10 = c010 + (c110 - c010) * fx
        val x01 = c001 + (c101 - c001) * fx; val x11 = c011 + (c111 - c011) * fx
        val y0 = x00 + (x10 - x00) * fy; val y1 = x01 + (x11 - x01) * fy
        return y0 + (y1 - y0) * fz
    }
}
