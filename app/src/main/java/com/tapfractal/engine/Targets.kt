package com.tapfractal.engine

import com.tapfractal.M
import com.tapfractal.Mat3
import com.tapfractal.Vec2
import com.tapfractal.Vec3
import com.tapfractal.scenes.Pose
import com.tapfractal.scenes.Scene
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class Target {
    enum class State { FREE, ACTIVE, BURST }
    var state = State.FREE
    val pos = Vec3(); val spawnPos = Vec3()
    var radius = 0.08f
    var life = 25f; var lifeMax = 25f
    var immortal = false
    var pulse = 0f
    var golden = false
    var assist = false
    var age = 0f
    var phase = 0f
    var spawnT = 0f      // spawn scale-in
    var burstT = 0f
    var respawnAt = -1f   // absolute time when the slot may be refilled
    var silentRespawn = false   // freed by the silent fly-past recycle: its replacement spawns without the bloom cue
    val lifeUniform: Float get() = if (immortal) 1f else M.clamp(life / 4f, 0f, 1f)
}

/** Per-frame inputs for target logic. */
class TargetCtx {
    val cam = Vec3(); val camRot = Mat3(); val gaze = Vec3(0f, 0f, 1f)
    var scene: Scene? = null
    var zoom = 0f; var level = 0; var restLen = 0.23f
    /** scene.ropeScale(zoom): the toy shrinks with the dive (CORE_API §11); scales the target radii and spawn margins. 1 in 2D. */
    var toyScale = 1f
    var time = 0f; var bass = 0.3f
    var attract = false; var headOn = true
    var is2D = false; var fov = 0.35f
    val planePan = Vec2(); var planeRot = 0f; var planeScale = 1.6f; var planeDepth = 0f
    var onboardingActive = false
    /** The sluurp's position this frame (valid when [sluurpValid]): no eye spawns on the resting yo-yo (an unearned hit). */
    val sluurp = Vec3(); var sluurpValid = false
}

/**
 * "Fractal eyes" (DESIGN §5): spawned in the ±25° gaze cone 1–3 u ahead in open space (rejection
 * sampling on the scene's DE mirror), gaze-gated 25 s lifetimes, silent recycling of targets you
 * fly past, golden eye every 5th hit (slot 5), mercy/assist (§4.5), onboarding script.
 */
class Targets {
    companion object {
        /** A target the camera comes within this distance of (u) is recycled: it cannot be swung at from inside it. */
        const val NEAR_RECYCLE = 0.45f
        /** Fallback spawns (along the gaze) start this far out, so a CRUISE camera has a second to swing before reaching it. */
        const val FALLBACK_MIN = 1.1f
        /** No spawn within this distance (u) of the sluurp: with the head pitched down the gaze ray runs through the
         *  hanging yo-yo, and an eye spawned onto it is hit without a flick — a chime + whoomp the player did not earn
         *  (the owner's "too many sound effects" in idle cruise). */
        const val SLUURP_CLEAR = 0.35f
        /** Fallback spawns are bent this far off the gaze ray (alternating sides) so the flight passes beside them. */
        const val FALLBACK_OFF_DEG = 10f
        /** 3D rooms: no fallback spawn when the camera is further than this (DE units) from any structure. */
        const val VOID_DE = 3.0f
    }
    val slots = Array(6) { Target() }
    var hits = 0; private set
    var hitsSinceGolden = 0; private set
    var lastHitTime = -100f; private set
    private var lastTimeoutTime = -100f
    private var nextSpawnEasy = false
    private var fallbackSide = 1f
    var onboardStep = 0
    var onboardSpawnedThird = -1f; private set
    private val rng = java.util.Random()

    var onTimeout: ((Int) -> Unit)? = null
    var onSpawn: ((Int) -> Unit)? = null

    fun clear() { for (t in slots) { t.state = Target.State.FREE; t.radius = 0f; t.pulse = 0f; t.respawnAt = -1f } }

    /** Scene switch: clear and refill after `delay` seconds. */
    fun resetForScene(time: Float, delay: Float) { clear(); for (t in slots) t.respawnAt = time + delay }

    private fun radiusFor(level: Int) = max(0.055f, 0.08f * 0.97f.pow(level))
    /** Radius after the dive scale (never below 0.03 u — a fractal eye must stay visible). */
    private fun radiusFor(ctx: TargetCtx) = max(0.03f, radiusFor(ctx.level) * ctx.toyScale)

    fun update(dt: Float, ctx: TargetCtx) {
        val desired = if (ctx.attract) 6 else (2 + ctx.level / 3).coerceIn(2, 4)
        val timeouts = !ctx.attract && ctx.headOn
        val cos60 = 0.5f
        for (i in 0 until 6) {
            val t = slots[i]
            when (t.state) {
                Target.State.ACTIVE -> {
                    t.age += dt; t.spawnT = minOf(1f, t.spawnT + dt / 0.5f)
                    // motion about the spawn point
                    if (t.golden) {
                        val a = ctx.time * 1.2f + t.phase
                        t.pos.set(t.spawnPos + ctx.camRot.right * (0.20f * sin(a)) + ctx.camRot.up * (0.20f * sin(2f * a) * 0.5f))
                    } else {
                        val a = ctx.time * 0.6f + t.phase
                        t.pos.set(t.spawnPos + ctx.camRot.right * (0.06f * cos(a)) + ctx.camRot.up * (0.06f * sin(a)))
                    }
                    val rel = t.pos - ctx.cam; val dist = rel.length()
                    val ahead = if (dist > 1e-4f) rel.dot(ctx.gaze) / dist else 1f
                    // recycle silently when behind, too far, or REACHED — flying past is navigation, not failure; at
                    // CRUISE (1 u/s) the camera reaches a 1 u target in a second, and a fractal eye at 0.4 u would fill
                    // the whole frame as a flat disc (a pale field) on the way through
                    if (((t.age > 1.5f && (rel.dot(ctx.gaze) < 0f || dist > 4f)) || (t.age > 0.2f && dist < NEAR_RECYCLE)) && !t.immortal) {
                        t.state = Target.State.FREE; t.radius = 0f; t.respawnAt = ctx.time + 0.5f; t.silentRespawn = true
                        continue
                    }
                    if (timeouts && !t.immortal && ahead > cos60) t.life -= dt
                    if (t.life <= 0f && timeouts && !t.immortal) {
                        if (ctx.time - lastTimeoutTime < 15f) { t.life += 10f }          // mercy: never two losses within 15 s
                        else {
                            lastTimeoutTime = ctx.time
                            t.state = Target.State.FREE; t.radius = 0f; t.respawnAt = ctx.time + 0.4f
                            nextSpawnEasy = true
                            onTimeout?.invoke(i)
                            continue
                        }
                    }
                    t.radius = radiusFor(ctx) * (if (t.golden) 0.875f else 1f) * (1f + 0.08f * (ctx.bass - 0.3f)) * spawnScale(t.spawnT)
                    t.pulse *= exp(-dt / 0.45f)
                }
                Target.State.BURST -> {
                    t.burstT += dt
                    t.pulse = exp(-t.burstT / 0.45f)
                    if (t.pulse < 0.05f) { t.state = Target.State.FREE; t.radius = 0f; t.pulse = 0f }
                }
                Target.State.FREE -> {}
            }
        }
        // refill: slots 0..3 (0..5 in attract); slot 5 is the golden slot outside attract
        var active = 0
        for (i in 0 until 6) if (slots[i].state != Target.State.FREE && (ctx.attract || i < 4)) active++
        if (active < desired) {
            for (i in 0 until (if (ctx.attract) 6 else 4)) {
                val t = slots[i]
                if (t.state == Target.State.FREE && ctx.time >= t.respawnAt) {
                    if (spawn(i, ctx, golden = false)) { active++; if (active >= desired) break }
                    else t.respawnAt = ctx.time + 0.3f
                }
            }
        }
        if (!ctx.attract && hitsSinceGolden >= 5 && slots[5].state == Target.State.FREE && ctx.time >= slots[5].respawnAt) {
            if (spawn(5, ctx, golden = true)) hitsSinceGolden = 0
        }
    }

    private fun spawnScale(s: Float): Float { // 0→1 over 0.5 s with a 1.15 overshoot at 0.3 s
        if (s >= 1f) return 1f
        return if (s < 0.6f) 1.15f * M.smoothstep(0f, 0.6f, s) else 1.15f - 0.15f * M.smoothstep(0.6f, 1f, s)
    }

    fun hit(i: Int, ctx: TargetCtx) {
        val t = slots[i]
        t.state = Target.State.BURST; t.burstT = 0f; t.pulse = 1f
        t.respawnAt = ctx.time + 0.8f
        hits++; lastHitTime = ctx.time
        if (!t.golden) hitsSinceGolden++
        if (ctx.onboardingActive && onboardStep < 3) onboardStep++
    }

    fun secondsSinceHit(time: Float) = time - lastHitTime

    // ---- spawning ----

    private fun spawn(i: Int, ctx: TargetCtx, golden: Boolean): Boolean {
        val scene = ctx.scene ?: return false
        val t = slots[i]
        val r = radiusFor(ctx) * (if (golden) 0.875f else 1f)
        val l0 = ctx.restLen
        var p: Vec3? = null
        t.assist = false; t.immortal = ctx.attract || !ctx.headOn
        val easy = nextSpawnEasy
        val assist = !ctx.attract && ctx.headOn && ctx.time - lastHitTime > 30f && hits > 0
        if (ctx.onboardingActive && onboardStep < 3 && !golden) {
            p = when (onboardStep) {
                0 -> ctx.cam + ctx.gaze * (1.4f + 0.9f * l0)
                1 -> { val d = rotY(ctx.gaze, ctx.camRot.up, M.rad(20f)); ctx.cam + d * (1.4f + 0.9f * l0) }
                else -> { onboardSpawnedThird = ctx.time; ctx.cam + ctx.gaze * 2.4f }
            }
            if (scene.de(p, ctx.zoom) < 2f * r + 0.05f || !ok2D(p, ctx, scene) || nearSluurp(p, ctx)) p = null
        }
        if (p == null && assist) { p = ctx.cam + ctx.gaze * (1.4f + 0.9f * l0); t.assist = true; if (scene.de(p, ctx.zoom) < 2f * r + 0.05f || nearSluurp(p, ctx)) p = null }
        if (p == null && easy) {
            val d = 1.2f + 0.4f * rng.nextFloat(); p = ctx.cam + ctx.gaze * d
            if (scene.de(p, ctx.zoom) < 2f * r + 0.05f || !ok2D(p, ctx, scene) || nearSluurp(p, ctx)) p = null
        }
        var mode = if (p != null) "scripted" else ""
        if (p == null) { p = sample(ctx, scene, r, l0); if (p != null) mode = "sample" }
        // no eyes in the void: a 3D camera more than VOID_DE from any structure has nothing to hunt (the room's safe
        // volume already rejected the cone), and fallback eyes spawned out there were flown past every second — bright
        // discs zipping along the frame edges at 190°/s. The look-away chevron leads the player back instead.
        if (p == null && !ctx.is2D && scene.de(ctx.cam, ctx.zoom) > VOID_DE) return false
        if (p == null) { p = fallback(ctx, scene, r); if (p != null) mode = "fallback" }
        if (p == null) return false
        android.util.Log.d("TapFractal", "spawn slot=$i mode=$mode p=$p de=%.3f |p-cam|=%.2f".format(scene.de(p, ctx.zoom), p.distance(ctx.cam)))
        t.spawnPos.set(p); t.pos.set(p)
        t.radius = 0.001f; t.spawnT = 0f
        t.golden = golden
        t.lifeMax = (if (golden) 12f else 25f) + (if (easy) 10f else 0f)
        t.life = t.lifeMax
        t.pulse = 0f; t.age = 0f; t.phase = rng.nextFloat() * M.TAU; t.burstT = 0f
        t.state = Target.State.ACTIVE
        if (easy) nextSpawnEasy = false
        // the bloom announces a NEW eye (entry, after a hit or a timeout, the golden eye) — the replacement of one the
        // player simply flew past is routine and silent (at CRUISE that was a bloom every second: SFX over the music)
        if (!t.silentRespawn) onSpawn?.invoke(i)
        t.silentRespawn = false
        return true
    }

    private fun rotY(v: Vec3, axis: Vec3, ang: Float): Vec3 {
        val c = cos(ang); val s = sin(ang)
        return (v * c + axis.cross(v) * s + axis * (axis.dot(v) * (1f - c))).normalized()
    }

    /** Clear candidates weighed against each other before one is chosen (see [sample]). */
    private val NOOK_CANDIDATES = 4

    private fun sample(ctx: TargetCtx, scene: Scene, r: Float, l0: Float): Vec3? {
        val vol = scene.safeVolume(Pose(ctx.cam.copy(), ctx.gaze.copy()))
        val cone = M.rad(25f); val cosCone = cos(cone)
        val swingShare = if (ctx.level <= 1) 0.8f else 0.55f
        val right = ctx.camRot.right; val up = ctx.camRot.up
        val volScale = 1f + 0.08f * ctx.level
        // Among the first [NOOK_CANDIDATES] clear candidates, take the one with the SMALLEST clearance that
        // still fits the eye. A target hanging in open space is reached by drifting past the fractal; one
        // tucked into a crevice mouth makes the hunt itself fly the player inside the structure, which is
        // what the room is for. In 2D rooms de() is a constant large number, so this reduces to "first found".
        var best: Vec3? = null
        var bestDe = Float.MAX_VALUE
        var found = 0
        for (attempt in 0 until 40) {
            val u = rng.nextFloat(); val phi = rng.nextFloat() * M.TAU
            val ct = 1f - u * (1f - cosCone); val st = sqrt(max(0f, 1f - ct * ct))
            val dir = (ctx.gaze * ct + right * (st * cos(phi)) + up * (st * sin(phi))).normalized()
            val dist = if (rng.nextFloat() < swingShare) 1.0f + rng.nextFloat() * (0.4f + l0) else (1.4f + l0) + rng.nextFloat() * (3.0f - 1.4f - l0)
            val p = ctx.cam + dir * dist
            val de = scene.de(p, ctx.zoom)
            if (de < 2f * r + 0.05f) continue
            if (!vol.contains((p - ctx.cam) * (1f / volScale) + ctx.cam)) continue
            if (!ok2D(p, ctx, scene)) continue
            if (nearSluurp(p, ctx)) continue
            var tooClose = false
            for (o in slots) if (o.state != Target.State.FREE) {
                val od = (o.pos - ctx.cam).normalized()
                if (o.pos.distance(p) < 0.5f || acos(od.dot(dir).coerceIn(-1f, 1f)) < M.rad(15f)) { tooClose = true; break }
            }
            if (tooClose) continue
            if (de < bestDe) { bestDe = de; best = p }
            if (++found >= NOOK_CANDIDATES) break
        }
        return best
    }

    /**
     * Fallback: walk the gaze ray outward and return the first clear point (de > 2r + 0.05, ≥ 0.8 u out,
     * plane test in 2D) BEFORE the first wall — never a point inside the fractal (the previous
     * unconditional `cam + gaze·1.5` spawned targets inside the Nave's bulb whenever the player hovered
     * against it). Null → the caller retries 0.3 s later.
     */
    private fun fallback(ctx: TargetCtx, scene: Scene, r: Float): Vec3? {
        // the ray is bent FALLBACK_OFF_DEG off the gaze (alternating side, a little up): an eye placed ON the gaze ray
        // is flown straight through at CRUISE — it swelled to a half-frame disc in the face every 1.3 s in the void
        // (measured: the whole-frame luma pulsed 2.5× between screenshots 100 ms apart) — while one 10° off the line
        // is passed beside, recycled at the frame edge at ≤ ~80 px, never in the face
        fallbackSide = -fallbackSide
        val dir = rotY(rotY(ctx.gaze, ctx.camRot.up, M.rad(FALLBACK_OFF_DEG) * fallbackSide), ctx.camRot.right, M.rad(4f))
        var d = 0.3f
        while (d <= 3.0f) {
            val p = ctx.cam + dir * d
            val de = scene.de(p, ctx.zoom)
            if (de < 0.02f) return null                               // wall ahead: nothing beyond is visible
            if (d >= FALLBACK_MIN && de > 2f * r + 0.05f && ok2D(p, ctx, scene) && !nearSluurp(p, ctx)) return p
            d += max(0.1f, min(de * 0.5f, 0.3f))
        }
        return null
    }

    /** True when a candidate would spawn onto the yo-yo (within [SLUURP_CLEAR] of the sluurp). */
    private fun nearSluurp(p: Vec3, ctx: TargetCtx): Boolean = ctx.sluurpValid && p.distance(ctx.sluurp) < SLUURP_CLEAR

    /** 2D rooms: the target's plane point must be exterior with escape count in [4, N). */
    private fun ok2D(p: Vec3, ctx: TargetCtx, scene: Scene): Boolean {
        if (!ctx.is2D) return true
        val d = ctx.camRot.mulT(p - ctx.cam); if (d.z < 0.05f) return false
        val uv = Vec2(d.x / (d.z * ctx.fov), d.y / (d.z * ctx.fov))
        val c = cos(ctx.planeRot); val s = sin(ctx.planeRot)
        val pp = Vec2((c * uv.x - s * uv.y) * ctx.planeScale + ctx.planePan.x, (s * uv.x + c * uv.y) * ctx.planeScale + ctx.planePan.y)
        val n = scene.escapeCount(pp, ctx.planeDepth)
        return n >= 4f && n < scene.maxIter(ctx.planeDepth)
    }
}
