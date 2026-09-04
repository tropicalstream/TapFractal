package com.tapfractal.scenes

import com.tapfractal.Vec2
import com.tapfractal.Vec3
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Scene 1 — "The Tidepool" (JULIA DRIFT, 2D). DESIGN.md §9.1 + the DIVE CONTRACT (CORE_API).
 *
 * A phosphorescent rock-pool you descend INTO: a 2D Phoenix–Julia set (`z' = z² + c + p·z_prev`) drawn
 * as a distance-estimated filament with orbit traps that re-emerge at every scale. Gaze pans the plane,
 * forward travel at any speed level dives ([diveRate]), hits add an eased zoom level, and the sluurp's
 * projected offset from the anchor bends the Julia constant (scaled to the view size, see below).
 *
 * DIVE CONTRACT: the core writes the CONTINUOUS [zoom] (== uZoom: hit levels eased + diveRate·travel)
 * before every call. This mirror uses [zoom] for everything the shader derives from uZoom — the plane
 * scale, the iteration count, the sluurp coupling and the Phoenix wobble — and IGNORES the `depth`
 * argument of the 2D hooks (under the contract the core passes depth == zoom; if an older core still
 * passes zoom + dive the mirror stays consistent with the picture, which reads uZoom alone).
 *
 * CPU mirror. The core has no 3D surface to collide with here (`de` = far), so the "no clipping" mirror
 * is [escapeCount]: the same escape loop as `scene1.frag` — the same fixed seed, the same sluurp coupling
 * (rest-hang estimate × 0.72^zoom), the same Phoenix coupling by [time] (× 0.72^zoom), the same
 * `N = 40 + 8·zoom` (cap 128), bailout 64 and smooth count `n + 4 − log2(log2 r²)`. The core uses it for
 * the dive gate (plane point under the screen centre), the sluurp's plane bounce (every 240 Hz substep)
 * and target spawn rejection (`[4, N)` = the glowing boundary). It is allocation-free.
 *
 * Deliberate differences from the shader (all visual-only, per CORE_API §5):
 *  - no `uBass` on the Phoenix coupling or the plane scale, no `uQuality` on N (table value);
 *  - the sluurp coupling `b = 0.34·(uSluurp.xy − uAnchor.xy)` needs the *projected* toy offset, which the
 *    Scene API never receives; the mirror uses the rest-hang estimate (the sluurp hanging the (rope-scaled)
 *    rest length straight below the head-locked anchor) so it agrees with the GPU whenever the toy is at
 *    rest, i.e. while the player is navigating. See [restOffsetB].
 *
 * Deviations from DESIGN §9.1 (deliberate, for the deep-zoom feel; the shader header says the same):
 *  - ONE seed (the dendrite) instead of the eight-seed melt by level: a melt driven by the continuous
 *    zoom would morph the boundary during every dive and throw the zoom centre off the structure it was
 *    descending into. Hits re-dress the same set (palette / ring phase / the eased scale step).
 *  - NO kaleidoscope fold (it hid the descent behind a static mirror star, and this mirror could never
 *    undo it — the old mirror tested the unfolded set, a real mismatch at L ≥ 3).
 *  - the sluurp's bend of c and the Phoenix wobble are scaled by 0.72^zoom so their shift of the boundary
 *    stays proportional to the view size at any depth.
 */
class Tidepool : Scene(1, "Tidepool", "shaders/scene1.frag", "music/scene1.mp3") {
    override val is2D = true

    // DESIGN §8 palette table, Tidepool row, retuned toward the shader's saturated cyan/violet/magenta poles
    // (the core derives the echo tint from a/b/c/d0/zshift and uses coreTint/bloomTint in the post pass).
    override val palette = Palette(
        a = Vec3(0.45f, 0.30f, 0.80f), b = Vec3(0.55f, 0.45f, 0.20f), c = Vec3(1f, 1f, 2f),
        d0 = Vec3(0.00f, 0.50f, 0.50f), zshift = Vec3(0.02f, 0.02f, 0.04f),
        coreTint = Vec3(0.40f, 0.90f, 1.00f),      // cyan (was ice 0.85/0.95/1.0: hot toy cores bloomed white-blue and pulled the room below the 0.60 saturation bar)
        bloomTint = Vec3(0.35f, 0.75f, 1.0f), bloomStrength = 0.35f,
    )

    // DESIGN §7.2: Soft 0.10 s / 1.010 / +0.004 · Wild 0.30 s / 1.030 / +0.012 (the design's
    // sign(sin 0.05t) flip on the wild rotation is not expressible through EchoParams constants). Centre (0,0).
    override val echo = EchoParams(0.10f, 1.010f, 0.004f, 0.30f, 1.030f, 0.012f)

    override val speedScale = 1.0f            // the global table: CRUISE 1.0 u/s, FAST 1.8, WARP 3.0
    override val maxRung = 9                  // the 60 fps showcase room
    override val interestConeDeg = 45f
    override val chimeRootHz = 146.8f         // D3

    /**
     * DIVE CONTRACT: zoom levels per world unit of forward travel. CRUISE (1.0 u/s) descends one level
     * per 6 s, FAST (1.8 u/s) one per 3.3 s, WARP (3.0 u/s) one per 2 s. The plane scale follows
     * 0.72^zoom, so CRUISE magnifies ×1.056 per second — a steady, readable descent.
     */
    override val diveRate: Float = 1f / 6f

    /**
     * The toy shrinks with the detail as you dive (contract default 0.85^(zoom/2)) but never below half
     * size: the head-locked yo-yo has to stay a usable target on the waveguide at depth 20.
     */
    override fun ropeScale(zoom: Float): Float = max(0.5f, 0.85f.pow(zoom * 0.5f))

    companion object {
        /** The dendrite seed — the ONE Julia constant of the room (see the class doc). */
        const val SEED_X = -0.7269f
        const val SEED_Y = 0.1889f
        const val BAILOUT = 64f
        const val MAX_ITER = 128f
        /** Deeper than this the shader clamps the depth (plane scale floor ≈ 3e-4, highp float stays clean). */
        const val DEPTH_CAP = 26f
        private const val INV_LN2 = 1.4426950f
        /** Anchor A_local = (0, 0.15, 1.4), uFov 0.35: uv per world unit at the anchor depth. */
        private const val UV_PER_U = 1f / (1.4f * 0.35f)
        /** Julia-constant gain on the projected offset (DESIGN §9.1, tune 0.25–0.45). */
        const val B_GAIN = 0.34f
    }

    // ---- 3D API: nothing to collide with; camera position is frozen at the origin ----------------------

    /** 2D room: no 3D surface. Far positive so flight/spawn tests never reject on it. */
    override fun de(p: Vec3, zoom: Float): Float = 10f

    override fun startPose() = Pose(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f))

    /** Auto-flight in the 2D room is the core's Lissajous pan (Flight.step2D); heading is unused. */
    override fun autoFlight(t: Float, pos: Vec3): Vec3 = Vec3(0f, 0f, 1f)

    /** The ±25° cone at 1–3 u is applied by the core; the plane-point boundary test is [escapeCount]. */
    override fun safeVolume(cam: Pose): Volume = Volume.ALL

    /** The Tidepool's soft leash (−2·(p − anchor) beyond 2.5 u, DESIGN §4.8) is applied by the core for is2D. */
    override fun field(p: Vec3): Vec3 = Vec3()

    /** Everything is a pure function of `zoom`; nothing to precompute per level. */
    override fun onLevelChanged(level: Int) {}

    // ---- 2D mirror ---------------------------------------------------------------------------------

    /** Shader `zf = 0.72^clamp(uZoom, 0, 26)`: the view-size factor (plane scale / 1.6). */
    private fun viewFactor(): Float = 0.72f.pow(zoom.coerceIn(0f, DEPTH_CAP))

    /**
     * Plane half-height. Shader: `1.6·0.72^clamp(uZoom, 0, 26)` before the bass breath. The `depth`
     * argument is ignored — the contract's continuous [zoom] is the single source (class doc).
     */
    override fun planeScale(depth: Float): Float = 1.6f * viewFactor()

    /** Shader N without the uQuality scale: 40 at zoom 0 → 128 at zoom 11+. */
    override fun maxIter(depth: Float): Float = min(MAX_ITER, 40f + 8f * zoom.coerceIn(0f, DEPTH_CAP))

    /** Phoenix coupling p (real) at the current time and zoom, without the bass term (shader line for line). */
    fun phoenixP(): Float = 0.30f + 0.20f * sin(0.07f * time) * viewFactor()

    /**
     * Rest-hang estimate of the shader's `b.y` (b.x = 0): the sluurp hangs the rope-scaled rest length
     * below the head-locked anchor → projected Δuv.y = −restLen·ropeScale / (1.4·uFov), × B_GAIN,
     * clamped ±0.3, then × 0.72^zoom like the shader.
     */
    fun restOffsetB(): Float {
        val restLen = min(0.34f, 0.23f * (1f + 0.03f * level)) * ropeScale(zoom)
        val b = -B_GAIN * restLen * UV_PER_U
        return b.coerceIn(-0.3f, 0.3f) * viewFactor()
    }

    /** Julia constant at the current [zoom]: the dendrite plus the estimated sluurp bend; written into `out`. */
    fun juliaC(out: Vec2): Vec2 {
        out.x = SEED_X
        out.y = SEED_Y + restOffsetB()
        return out
    }

    /** Alias with the DESIGN §9.1 name: smooth escape count at plane point p. */
    fun boundary(p: Vec2, depth: Float): Float = escapeCount(p, depth)

    /**
     * Smooth escape count of the Phoenix–Julia iteration at plane point p (already `rot·uv·scale + pan`,
     * mapped by the core). Returns [maxIter] when the point does not escape (interior). Mirrors the
     * shader loop exactly (same iteration count `i < N` as a float compare, same bailout, same smooth
     * count). The `depth` argument is ignored in favour of the contract's [zoom]. Allocation-free.
     */
    override fun escapeCount(p: Vec2, depth: Float): Float {
        val n = maxIter(depth)
        val cx = SEED_X
        val cy = SEED_Y + restOffsetB()
        val ph = phoenixP()
        var zx = p.x; var zy = p.y
        var px = 0f; var py = 0f                  // z_prev (Phoenix), starts at 0 like the shader
        var i = 0
        while (i.toFloat() < n) {
            val nx = zx * zx - zy * zy + cx + ph * px
            val ny = 2f * zx * zy + cy + ph * py
            px = zx; py = zy
            zx = nx; zy = ny
            val r2 = zx * zx + zy * zy
            if (r2 > BAILOUT) {
                val log2r2 = ln(r2) * INV_LN2
                return i.toFloat() + 4f - ln(max(log2r2, 1f)) * INV_LN2
            }
            i++
        }
        return n
    }
}
