package com.tapfractal.engine

import com.tapfractal.M
import com.tapfractal.Vec2
import com.tapfractal.Vec3
import com.tapfractal.scenes.Scene
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Gaze navigation (DESIGN §3 + CORE_API §11): the camera flies forward along the gaze at the level
 * speed × the scene's speedScale; the scene's CPU DE mirror slows it to a hover as a surface closes in
 * and keeps a skin (`scene.skinAt(zoom)`, 0.20 u at the surface, shrinking with the dive) between the
 * eye and the fractal — slide, never clip. Everything that moves the camera is SMOOTH:
 *  - the hover factor is a 150 ms EMA of the DE test (a DE that changes discontinuously between
 *    frames, e.g. a fold crossing, no longer steps the speed);
 *  - the skin is held by a critically damped push (ω = PUSH_OMEGA) along the DE gradient instead of a
 *    hard per-frame clamp (only a deep intrusion below ¼ skin is snapped, as a safety net);
 *  - the resulting camera velocity is acceleration-capped at ACCEL_MAX u/s² (per frame, all terms:
 *    flight, current, push, auto-flight heading changes).
 * Forward travel feeds the DIVE: `diveZoom = scene.diveRate · travel` (eased τ 0.25 s) is added to
 * uZoom by the game, so flying into the structure takes you deeper into the detail.
 * 2D rooms pan/dive instead (§3.3). Auto-flight follows `scene.autoFlight()` at ≤ 1 u/s when head
 * tracking is off or in attract mode.
 */
class Flight {
    companion object {
        /** HOVER · SLOW · CRUISE · FAST · WARP base speeds (u/s), × scene.speedScale (CORE_API §11). */
        val LEVEL = floatArrayOf(0f, 0.30f, 1.0f, 1.8f, 3.0f)
        val NAMES = arrayOf("HOVER", "SLOW", "CRUISE", "FAST", "WARP")
        val PAN_GAIN = floatArrayOf(0f, 0.45f, 0.90f, 1.50f, 2.40f)
        /** Skin at zoom 0 (the Scene.skinAt default); kept for callers that want the nominal value. */
        const val SKIN = 0.20f
        const val DEFAULT_LEVEL = 2
        /** Camera acceleration cap (u/s²) over every term of the motion. */
        const val ACCEL_MAX = 4f
        /** Hover-factor EMA time constant (s). */
        const val HOVER_TAU = 0.15f
        /** Skin push spring: critically damped, ω rad/s (≈ 110 ms response). */
        const val PUSH_OMEGA = 18f
        /** Current clamp (u/s): a room may pull the flight into a gap this hard (CORE_API §11). */
        const val CURRENT_MAX = 0.60f
        /** Travel-dive ease (s). */
        const val DIVE_TAU = 0.25f
        /** 3D dive proximity gate (DE units): forward travel counts fully toward the dive within DIVE_NEAR of the
         *  structure, not at all beyond DIVE_FAR (EMA τ DIVE_GATE_TAU) — flying off into the void is not
         *  "travelling INTO the fractal", so the world does not keep zooming out there. uTravel stays the raw distance. */
        const val DIVE_NEAR = 0.6f
        const val DIVE_FAR = 2.5f
        const val DIVE_GATE_TAU = 0.5f
        /** 2D structure nudge (plane units per second, × plane scale) and the hard pan bound (plane units; the set lives in |z| < 2). */
        const val PAN_NUDGE = 0.15f
        const val PAN_MAX = 2.2f
    }

    val pos = Vec3()
    val heading = Vec3(0f, 0f, 1f)
    /** Nominal flight speed along the heading (u/s) — uSpeed. */
    var speed = 0f
        private set
    /** Actual camera velocity of the last frame (u/s), after the current, push and acceleration cap. */
    val vel = Vec3()
    /** Distance flown FORWARD since scene start (3D) — uTravel; the net displacement along the heading, so a camera pinned against a wall does not travel. */
    var travel = 0f
        private set
    /** Eased travel dive in zoom levels (= scene.diveRate · travel, τ 0.25 s); the game adds it to uZoom. */
    var diveZoom = 0f
        private set
    /** Forward distance that counted toward the dive (3D: travel × the proximity gate; 2D: the gated dive distance). */
    var diveDist = 0f
        private set
    private var diveGate = 1f
    /** The skin in use this frame (u). */
    var skin = SKIN
        private set
    var level = DEFAULT_LEVEL
        set(v) { field = v.coerceIn(0, 4) }

    private var hoverF = 1f
    private var pushV = 0f
    private var haveVel = false

    // 2D room
    val pan = Vec2()
    var planeRot = 0f
        private set
    /** 2D: the eased travel dive in zoom levels (= diveZoom; kept for readers of the old name). */
    val dive: Float get() = diveZoom
    /** Recentre zeroes the 2D pan input (DESIGN §3.3). */
    fun zeroPan() { pan.set(0f, 0f) }

    fun enter(scene: Scene) {
        val p = scene.startPose()
        pos.set(p.pos); heading.set(p.heading.normalized())
        speed = 0f; travel = 0f; diveZoom = 0f; diveDist = 0f; diveGate = 1f
        vel.set(0f, 0f, 0f); haveVel = false; hoverF = 1f; pushV = 0f
        skin = scene.skinAt(scene.zoom)
        pan.set(0f, 0f); planeRot = 0f; autoC.set(0f, 0f)
        diagReset(); dAccelLast = 0f; dHave = false
    }

    /** 3D rooms. `auto` = head tracking off / attract (heading from scene.autoFlight, ≤ 1 u/s). `zoom` = the continuous uZoom (hits + dive). */
    fun step3D(dt: Float, gaze: Vec3, scene: Scene, zoom: Float, time: Float, auto: Boolean, forcedLevel: Int = -1) {
        val want0 = if (auto) scene.autoFlight(time, pos).normalized() else gaze
        heading.set(Vec3.slerpDir(heading, want0, M.ease(dt, 0.30f)))
        val lv = if (forcedLevel >= 0) forcedLevel else level
        var want = LEVEL[lv] * scene.speedScale
        if (auto) want = min(want, 1.0f)
        // hover: the DE test scales with the skin (0.35 / 0.60 / 0.12 / 0.20 at the 0.20 u skin, as designed)
        skin = scene.skinAt(zoom)
        val ahead = scene.de(pos + heading * (1.75f * skin), zoom)
        val here = scene.de(pos, zoom)
        val slowRaw = M.clamp(ahead / (3f * skin), 0f, 1f) * M.clamp((here - 0.6f * skin) / skin, 0f, 1f)
        hoverF += (slowRaw - hoverF) * M.ease(dt, HOVER_TAU)
        speed += (want * hoverF - speed) * M.ease(dt, 0.40f)
        var cur = scene.current(pos, time)
        val cl = cur.length(); if (cl > CURRENT_MAX) cur = cur * (CURRENT_MAX / cl)
        // skin spring: x = intrusion into the skin (> 0 inside), pushV = outward speed along the gradient
        val x = skin - here
        val pushA = (if (x > 0f) PUSH_OMEGA * PUSH_OMEGA * x else 0f) - 2f * PUSH_OMEGA * pushV
        pushV = max(0f, pushV + pushA * dt)
        val grad = if (pushV > 1e-4f || x > 0f) scene.grad(pos, zoom) else null
        // desired velocity, then the acceleration cap over the whole of it
        val vx = heading.x * speed + cur.x + (grad?.x ?: 0f) * pushV
        val vy = heading.y * speed + cur.y + (grad?.y ?: 0f) * pushV
        val vz = heading.z * speed + cur.z + (grad?.z ?: 0f) * pushV
        if (!haveVel) { vel.set(0f, 0f, 0f); haveVel = true }
        var dvx = vx - vel.x; var dvy = vy - vel.y; var dvz = vz - vel.z
        val dvl = kotlin.math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz)
        val dvMax = ACCEL_MAX * dt
        if (dvl > dvMax) { val k = dvMax / dvl; dvx *= k; dvy *= k; dvz *= k }
        vel.x += dvx; vel.y += dvy; vel.z += dvz
        pos.addScaled(vel, dt)
        val fwd = max(0f, vel.dot(heading)) * dt
        travel += fwd
        val near = M.clamp((DIVE_FAR - here) / (DIVE_FAR - DIVE_NEAR), 0f, 1f)
        diveGate += (near - diveGate) * M.ease(dt, DIVE_GATE_TAU)
        diveDist += fwd * diveGate
        // safety net only: a deep intrusion (below ¼ skin — a level ease that grew the surface through the
        // camera faster than the spring follows) is snapped out so the eye is never inside the surface
        val d2 = scene.de(pos, zoom)
        var snap = 0f
        if (d2 < 0.25f * skin) { snap = 0.25f * skin - d2; pos.addScaled(grad ?: scene.grad(pos, zoom), snap) }
        diveZoom += (scene.diveRate * diveDist - diveZoom) * M.ease(dt, DIVE_TAU)
        diagFrame(dt, slowRaw, snap, cl)
    }

    /**
     * 2D room: yaw/pitch steer a pan velocity; the speed level is the DIVE (CORE_API §11 for the 2D room —
     * "forward travel" is the level speed × speedScale, gated by the boundary under the screen centre: 0.3 inside
     * the set, clamp((escapeCount − 3)/6) outside, EMA τ HOVER_TAU so a filament crossing never steps the rate).
     * `travel` (= uTravel) accumulates that gated distance and `diveZoom = scene.diveRate · travel` (eased τ
     * DIVE_TAU) is added to uZoom by the game — the Tidepool's 1/6 level per u = one level per 6 s at CRUISE.
     * Auto (tracking off) is capped at 1 u/s like the 3D auto-flight; attract forces SLOW via `forcedLevel`.
     */
    fun step2D(dt: Float, yaw: Float, pitch: Float, roll: Float, scene: Scene, zoom: Float, time: Float, energy: Float, auto: Boolean, forcedLevel: Int = -1) {
        speed = 0f
        val scale = scene.planeScale(zoom)
        // dive gate: cannot dive into flat exterior colour or the interior; steered onto the glowing boundary
        val n = scene.escapeCount(pan, zoom); val nMax = scene.maxIter(zoom)
        val gate = if (n >= nMax) 0.3f else M.clamp((n - 3f) / 6f, 0f, 1f)
        // structure nudge: over flat exterior (gate < 1) the pan centre drifts back toward the set along the
        // escape-count gradient at ≤ PAN_NUDGE·scale per second — weaker than a deliberate gaze (0.24·scale/s
        // at 20°), so the player always wins, but a small pitch bias can no longer walk the view off the
        // structure and stall the dive for good (the interior has no gradient: the gate's 0.3 applies there)
        var nx = 0f; var ny = 0f
        if (gate < 1f && n < nMax) {
            val e = 0.04f * scale
            val gx = scene.escapeCount(Vec2(pan.x + e, pan.y), zoom) - scene.escapeCount(Vec2(pan.x - e, pan.y), zoom)
            val gy = scene.escapeCount(Vec2(pan.x, pan.y + e), zoom) - scene.escapeCount(Vec2(pan.x, pan.y - e), zoom)
            val gl = kotlin.math.sqrt(gx * gx + gy * gy)
            if (gl > 1e-6f) { val k = PAN_NUDGE * scale * (1f - gate) / gl; nx = gx * k; ny = gy * k }
        }
        if (auto) {
            autoC.x += nx * dt; autoC.y += ny * dt
            val tx = autoC.x + 0.35f * sin(0.05f * time) * scale; val ty = autoC.y + 0.35f * cos(0.037f * time) * scale
            pan.x += (tx - pan.x) * M.ease(dt, 1.5f); pan.y += (ty - pan.y) * M.ease(dt, 1.5f)
        } else {
            val g = PAN_GAIN[level] * scale
            pan.x += (g * soft(yaw) + nx) * dt
            pan.y += (g * soft(pitch) + ny) * dt
        }
        val pl = pan.length(); if (pl > PAN_MAX) { pan.x *= PAN_MAX / pl; pan.y *= PAN_MAX / pl }
        hoverF += (gate - hoverF) * M.ease(dt, HOVER_TAU)
        val lv = if (forcedLevel >= 0) forcedLevel else level
        var want = LEVEL[lv] * scene.speedScale
        if (auto) want = min(want, 1.0f)
        travel += want * hoverF * dt
        diveDist = travel
        diveZoom += (scene.diveRate * diveDist - diveZoom) * M.ease(dt, DIVE_TAU)
        planeRot += (0.02f + 0.04f * energy) * dt
        rollTerm = roll
        diagFrame(dt, hoverF, 0f, 0f)
    }
    private val autoC = Vec2()
    private var rollTerm = 0f
    /** uCamPos.z for 2D rooms = slow drift + head roll. */
    val planeRotation: Float get() = planeRot + rollTerm

    private fun soft(a: Float): Float {
        val p = M.rad(20f); val dz = M.rad(1.5f)
        if (abs(a) < dz) return 0f
        val s = a - dz * kotlin.math.sign(a)
        return p * tanh(s / p)
    }

    // ---- motion diagnostics (per-frame camera acceleration; read by the renderer's log lines) ----
    private val dPrevPos = Vec3(); private val dPrevVel = Vec3(); private var dHave = false; private var dPrevSlow = 1f
    /** Window maxima since the last [diagReset]: acceleration (u/s²), safety snap (u), hover-factor jump, current (u/s); frames over ACCEL_MAX. */
    var dAccelMax = 0f; var dSnapMax = 0f; var dSlowJumpMax = 0f; var dCurMax = 0f; var dOver4 = 0; var dFrames = 0
    /** Acceleration of the last frame (u/s²), measured from the position trace. */
    var dAccelLast = 0f
    private fun diagFrame(dt: Float, slow: Float, snap: Float, cur: Float) {
        if (dHave) {
            val v = (pos - dPrevPos) * (1f / dt)
            val a = (v - dPrevVel).length() / dt
            dAccelLast = a
            if (a > dAccelMax) dAccelMax = a
            if (a > ACCEL_MAX + 0.05f) dOver4++
            dPrevVel.set(v)
        } else { dPrevVel.set(0f, 0f, 0f); dHave = true }
        dPrevPos.set(pos)
        val sj = abs(slow - dPrevSlow); dPrevSlow = slow
        if (sj > dSlowJumpMax) dSlowJumpMax = sj
        if (snap > dSnapMax) dSnapMax = snap
        if (cur > dCurMax) dCurMax = cur
        dFrames++
    }
    /** Clears the window maxima only — the velocity history stays, or the next frame would read v/dt as a spike. */
    fun diagReset() { dAccelMax = 0f; dSnapMax = 0f; dSlowJumpMax = 0f; dCurMax = 0f; dOver4 = 0; dFrames = 0 }
}
