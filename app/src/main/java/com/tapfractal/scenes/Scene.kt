package com.tapfractal.scenes

import com.tapfractal.Vec2
import com.tapfractal.Vec3
import kotlin.math.pow

/** IQ cosine palette parameters + post tints (DESIGN §8 table). */
class Palette(
    val a: Vec3, val b: Vec3, val c: Vec3, val d0: Vec3, val zshift: Vec3,
    val coreTint: Vec3, val bloomTint: Vec3, val bloomStrength: Float,
)

/** Echo-trail parameters per Echo setting (DESIGN §7.2 table). zoom/rot are per-frame amounts at 60 fps. */
class EchoParams(
    val softTau: Float, val softZoom: Float, val softRot: Float,
    val wildTau: Float, val wildZoom: Float, val wildRot: Float,
    val centre: Vec2 = Vec2(0f, 0f),
)

/** A position + unit heading in world units. */
class Pose(val pos: Vec3, val heading: Vec3)

/** Spawn volume predicate (world space). The core intersects it with the ±25° gaze cone at 1–3 u. */
fun interface Volume {
    fun contains(p: Vec3): Boolean
    companion object {
        val ALL = Volume { true }
        fun sphere(centre: Vec3, r: Float) = Volume { p -> p.distance(centre) < r }
    }
}

/**
 * Base class of the four rooms. Core-owned; scene authors subclass it in their own file and
 * edit only that file plus their `sceneN.frag`.
 *
 * Coordinate frame (CONTRACT C2): x right, y up (gravity-true), z forward at the last recentre;
 * 1 world unit ≈ 1 m of feel. `de(p, zoom)` MUST mirror the shader distance estimator (same
 * formula, same zoom-level parameters, no beat/bass terms) — the core uses it for gaze flight
 * (hover/slide), sluurp bounce and target spawning. See docs/CORE_API.md.
 */
abstract class Scene(val slot: Int, val displayName: String, val fragAsset: String, val musicAsset: String) {
    /** Menu value string ("Tidepool"). */
    val name: String get() = displayName
    /** HUD label ("THE TIDEPOOL"). */
    val hudLabel: String get() = "THE " + displayName.uppercase()

    abstract val is2D: Boolean
    abstract val palette: Palette
    abstract val echo: EchoParams
    /** Multiplier on the global speed-level table (DESIGN §3.1). */
    abstract val speedScale: Float
    /** Adaptive-resolution rung cap 0..9 (DESIGN §7.3). */
    abstract val maxRung: Int
    /** Half-angle (degrees) of the interest cone for uLookAway (DESIGN §3.4). */
    abstract val interestConeDeg: Float
    /** Root frequency of the hit chime (DESIGN §4.8). */
    abstract val chimeRootHz: Float

    // ---- state written by the core every frame (read-only for scenes) ----
    /**
     * Continuous depth (= uZoom): the hit levels eased (1.2 s ease-in-out) PLUS the travel dive
     * (`diveRate` × distance flown, eased). Written by the core every frame BEFORE any de()/field()/
     * current() call, so a CPU DE that reads `zoom` sees exactly what the shader sees in uZoom
     * (CORE_API §11). Use this — not the integer `level` — for everything the shader derives from uZoom.
     */
    @JvmField var zoom: Float = 0f
    /** Seconds since scene start (= uTime). */
    @JvmField var time: Float = 0f
    /** Integer hit level (the zoom TARGET of the hits alone; the dive is not counted). Target count / rest length / chime degree. */
    @JvmField var level: Int = 0

    // ---- dive contract (CORE_API §11) ----
    /**
     * Zoom levels gained per world unit of forward travel, at any speed level (0 = hits only). The core
     * adds `diveRate · travel` to uZoom, eased so it never jumps; flying INTO the structure therefore
     * takes you deeper into the detail. Rooms code their DE against `zoom`, which already includes it.
     */
    open val diveRate: Float = 0f
    /**
     * Flight hover/slide skin (u) at continuous depth `zoom`: the minimum clearance the camera keeps
     * from the surface. Shrinks with the depth so the camera can enter the crevices that the detail
     * opens up. Default 0.20 · 0.85^zoom, never below 0.03. The hover slowdown scales with it too.
     */
    open fun skinAt(zoom: Float): Float = (0.20f * 0.85f.pow(zoom)).coerceAtLeast(0.03f)
    /**
     * Scale factor for the toy as you dive (targets' radii and spawn margins; the sluurp/rope follow
     * once Toy.kt applies it): the toy shrinks with the detail. Default 0.85^(zoom/2).
     */
    open fun ropeScale(zoom: Float): Float = 0.85f.pow(zoom * 0.5f)

    /** CPU mirror of the shader DE at continuous depth `zoom` (2D scenes: return a large positive number). */
    abstract fun de(p: Vec3, zoom: Float): Float
    /** Convenience: de at the current uZoom. */
    fun de(p: Vec3): Float = de(p, zoom)

    /** Tetrahedron 4-tap gradient of de() (unit length), e = 0.002. Override only if you have an analytic normal. */
    open fun grad(p: Vec3, zoom: Float): Vec3 {
        val e = 0.002f
        val d1 = de(Vec3(p.x + e, p.y - e, p.z - e), zoom)
        val d2 = de(Vec3(p.x - e, p.y - e, p.z + e), zoom)
        val d3 = de(Vec3(p.x - e, p.y + e, p.z - e), zoom)
        val d4 = de(Vec3(p.x + e, p.y + e, p.z + e), zoom)
        val g = Vec3(d1 - d2 - d3 + d4, -d1 - d2 + d3 + d4, -d1 + d2 - d3 + d4)
        val l = g.length()
        return if (l > 1e-9f) g / l else Vec3(0f, 1f, 0f)
    }
    fun grad(p: Vec3): Vec3 = grad(p, zoom)

    /** Camera position + heading on scene entry. */
    abstract fun startPose(): Pose
    /**
     * Current added to the flight; the core clamps it to 0.35 u/s. Strong enough to PULL the flight into
     * a canyon mouth / gap / vein when the gaze is within ~30° of it — beyond that the room must return
     * zero or a gentle drift (gaze always wins). Called once per frame with `zoom` already written.
     */
    open fun current(p: Vec3, t: Float): Vec3 = Vec3()
    /** Desired unit heading for Head tracking = Off / attract mode (flown at ≤ 1 u/s). */
    abstract fun autoFlight(t: Float, pos: Vec3): Vec3
    /** Extra force on the sluurp (u/s²), added to gravity (DESIGN §4.8). */
    open fun field(p: Vec3): Vec3 = Vec3()
    /** Spawn volume for targets; the core also requires de(p) > 2·r + 0.05 and the gaze cone. */
    abstract fun safeVolume(cam: Pose): Volume
    /** Called when the integer level changes (after a hit or a timeout). */
    open fun onLevelChanged(level: Int) {}
    /** Called when the scene becomes the incoming scene of a crossfade (before startPose()). */
    open fun onEnter() {}

    // ---- 2D rooms only (is2D = true). The plane point of eye-uv `uv` is rot2(rot)·uv·planeScale(depth) + pan. ----
    /** Smooth escape count at plane point p (≥ maxIter(depth) = interior). Default: everything exterior. */
    open fun escapeCount(p: Vec2, depth: Float): Float = 8f
    /** Iteration cap used by escapeCount at this depth (the N of the shader). */
    open fun maxIter(depth: Float): Float = 96f
    /** Plane half-height at depth = uZoom + uTravel (DESIGN §3.3). */
    open fun planeScale(depth: Float): Float = 1.6f * 0.72f.pow(depth)
}
