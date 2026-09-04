package com.tapfractal.engine

import com.tapfractal.M
import com.tapfractal.Mat3
import com.tapfractal.Vec2
import com.tapfractal.Vec3
import com.tapfractal.scenes.Scene
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Per-frame inputs to the toy simulation. */
class ToyCtx {
    val camCenter = Vec3()
    val camRot = Mat3()
    val gaze = Vec3(0f, 0f, 1f)
    val gravity = Vec3(0f, -1f, 0f)
    var scene: Scene? = null
    var time = 0f
    var onset = false          // gated beat onset this frame
    var bass = 0.3f
    var energy = 0.5f
    var is2D = false
    var headOn = true
    var attract = false
    var activity = 0f          // 0..1 recent head activity (scales the beat dance)
    var driftAmp = 0.03f       // anchor Lissajous amplitude (0.03 play · 0.18 head off · 0.35 attract)
    /** scene.ropeScale(zoom): the toy shrinks with the dive (CORE_API §11) — scales the sluurp radius and the rope rest length. */
    var toyScale = 1f
    var fov = 0.35f
    // 2D plane mapping (for the plane bounce)
    val planePan = Vec2(); var planeRot = 0f; var planeScale = 1.6f; var planeDepth = 0f
    var targets: Targets? = null
    var level = 0
}

/**
 * The sluurp on its rope (DESIGN §4): world-space spring–damper bungee (K 320, C 9, ζ ≈ 0.25) with
 * slack-then-elastic rope, 240 Hz substeps, Verlet rope for the picture, flick → free flight →
 * re-tether snap, bounce off the scene's DE mirror (restitution 0.6), head bubble 0.55 u and a
 * 6 u/s tangential speed cap.
 */
class Toy {
    companion object {
        const val R_B = 0.045f
        val A_LOCAL = Vec3(0f, 0.15f, 1.4f)
        const val K = 320f
        const val C = 9f
        const val SUBSTEP = 1f / 240f
        /**
         * Fractal-bounce cue gate (3D rooms only): minimum impact speed (u/s) and minimum spacing (s) of the
         * "bong". 0.6 s = one cue per real impact; the rope settles in ~2 bounces anyway (DESIGN §4.2), so a
         * chattering contact reads as one hit, not a drum roll. Sfx applies the same cooldown centrally.
         */
        const val BOUNCE_MIN_V = 0.6f
        const val BOUNCE_COOLDOWN = 1.0f
        /** Sluurp speed relative to the anchor (u/s) below which an overlap with an eye is a pass-through, not a hit. */
        const val HIT_MIN_REL = 0.6f
        /** Rest length before the dive scale (× ctx.toyScale each frame). */
        fun restLenFor(level: Int) = min(0.34f, 0.23f * (1f + 0.03f * level))
        /** The dive scale never takes the sluurp below this radius (u): it has to stay a visible, hittable ball. */
        const val R_B_MIN = 0.018f
    }

    val sluurp = Vec3(); val vel = Vec3()
    val anchor = Vec3(); val anchorVel = Vec3()
    /** Physics radius this frame = R_B × ctx.toyScale (≥ R_B_MIN): the bounce, the hit test, the bounds and the draw use it. */
    var rB = R_B; private set
    private val anchorPrev = Vec3(); private var anchorInit = false
    var restLen = 0.23f
    var reelLen = 0.23f
    private var reelSpeed = 3f
    var freeT = 0f; private set
    var tension = 0f; private set
    var ropeFree = 0f; private set
    var glow = 0f; private set
    var squash = 0f; private set
    val rope = Array(12) { Vec3() }
    private val ropePrev = Array(12) { Vec3() }
    private var ropeInit = false
    val eyeDir = Vec3(0f, 0f, 1f)
    private var lastFlickTime = -10f
    private var lastHitTime = -10f
    private var danceAngle = 0f
    private val whiffed = BooleanArray(6)
    /** Rendering radius (breath). */
    var drawRadius = R_B; private set

    var onHit: ((Int, Boolean) -> Unit)? = null      // slot, clean (during free flight)
    var onBounce: ((Float) -> Unit)? = null           // impact speed
    var onSnap: ((Float) -> Unit)? = null             // 0..1 strength
    var onWhiff: (() -> Unit)? = null
    var onFlick: ((Float) -> Unit)? = null            // tension at flick

    fun reset(ctx: ToyCtx) {
        applyScale(ctx)
        updateAnchor(ctx, 1f / 60f, true)
        sluurp.set(anchor).addScaled(Vec3(0f, -1f, 0f), restLen)
        vel.set(0f, 0f, 0f); freeT = 0f; tension = 0f; ropeFree = 0f; glow = 0.5f; squash = 0f
        reelLen = restLen; ropeInit = false; anchorInit = false
        updateAnchor(ctx, 1f / 60f, true)
        initRope()
    }

    /** Move the whole toy by a world offset (scene switch keeps it in place relative to the head). */
    fun translate(off: Vec3) {
        sluurp.addScaled(off, 1f); anchor.addScaled(off, 1f); anchorPrev.addScaled(off, 1f)
        for (i in 0 until 12) { rope[i].addScaled(off, 1f); ropePrev[i].addScaled(off, 1f) }
    }

    private fun updateAnchor(ctx: ToyCtx, dt: Float, snap: Boolean) {
        val t = ctx.time; val k = ctx.driftAmp / 0.03f
        val drift = Vec3(0.03f * k * sin(M.TAU * t / 5.3f), 0.02f * k * sin(M.TAU * t / 7.9f), 0.015f * k * sin(M.TAU * t / 6.1f))
        val a = ctx.camCenter + ctx.camRot.mul(A_LOCAL) + drift
        if (!anchorInit || snap) { anchorPrev.set(a); anchorVel.set(0f, 0f, 0f); anchorInit = true }
        val fd = (a - anchorPrev) / dt.coerceAtLeast(1e-4f)
        anchorVel.x += (fd.x - anchorVel.x) * 0.5f; anchorVel.y += (fd.y - anchorVel.y) * 0.5f; anchorVel.z += (fd.z - anchorVel.z) * 0.5f
        anchorPrev.set(a)
        anchor.set(a)
    }

    /** The dive scale (CORE_API §11): the sluurp radius and the rope rest length follow scene.ropeScale(zoom); the reel eases a shrinking rest length in. */
    private fun applyScale(ctx: ToyCtx) {
        rB = max(R_B_MIN, R_B * ctx.toyScale)
        restLen = restLenFor(ctx.level) * ctx.toyScale
    }

    fun update(frameDt: Float, ctx: ToyCtx) {
        val dt = min(frameDt, 0.050f)
        updateAnchor(ctx, dt, false)
        applyScale(ctx)
        val n = max(1, ceil(dt / SUBSTEP).toInt())
        val h = dt / n
        repeat(n) { substep(h, ctx) }
        ropeStep(dt, ctx)
        glow = max(glow * exp(-dt / 0.45f), 0f)
        squash *= exp(-dt * 12f)
        if (freeT > 0f) ropeFree = 1f else ropeFree *= exp(-dt / 0.2f)
        drawRadius = rB * (1f + 0.04f * sin(M.TAU * 0.25f * ctx.time) + 0.06f * ctx.bass)
        // iris: nearest target within 4 u, else the camera
        var best = 4f; var look: Vec3? = null
        ctx.targets?.let { ts -> for (i in 0 until 6) { val t = ts.slots[i]; if (t.state == Target.State.ACTIVE) { val d = t.pos.distance(sluurp); if (d < best) { best = d; look = t.pos } } } }
        val target = ((look ?: ctx.camCenter) - sluurp).normalized()
        eyeDir.set(Vec3.slerpDir(eyeDir, target, M.ease(dt, 0.15f)))
        // beat dance (still head): the sluurp swings by itself on gated onsets
        if (ctx.onset && ctx.energy > 0.5f) {
            danceAngle += 2.399963f
            val axis = ctx.gaze
            val r = ctx.camRot.right; val u = ctx.camRot.up
            val dk = (r * cos(danceAngle) + u * sin(danceAngle)); val proj = dk - axis * dk.dot(axis)
            vel.addScaled(proj.normalized(), 0.25f * ctx.bass * (1f - 0.5f * ctx.activity))
            glow = max(glow, 0.15f * ctx.bass)
        }
    }

    private fun substep(h: Float, ctx: ToyCtx) {
        val scene = ctx.scene
        val f = (scene?.field(sluurp) ?: Vec3()) + ctx.gravity * 1.0f
        if (ctx.is2D) { // soft leash toward the anchor beyond 2.5 u (Tidepool feel, DESIGN §4.8)
            val d = sluurp - anchor; val l = d.length()
            if (l > 2.5f) f.addScaled(d, -2.0f)
        }
        f.addScaled(vel, -(if (freeT > 0f) 0.35f else 0.6f))
        f.addScaled(vel, -0.02f * vel.length())
        if (freeT > 0f) {
            freeT -= h; tension = max(0f, tension - h / 0.15f)
            if (freeT <= 0f) onRetether(ctx)
        } else {
            if (reelLen > restLen) reelLen = max(restLen, reelLen - reelSpeed * h)
            val d = sluurp - anchor; val len = d.length()
            if (len > reelLen && len > 1e-6f) {
                val dir = d / len; val stretch = len - reelLen
                val vRel = (vel - anchorVel).dot(dir)
                f.addScaled(dir, -(K * stretch + C * vRel))
                tension = M.clamp(stretch / 0.15f, 0f, 1f)
            } else tension *= exp(-h / 0.10f)
        }
        vel.addScaled(f, h); sluurp.addScaled(vel, h)
        capTangential(ctx.camCenter, 6f)
        headBubble(ctx.camCenter, 0.55f)
        hardLeash(ctx.camCenter)
        if (scene != null) { if (ctx.is2D) planeBounce(ctx, scene) else fractalBounce(scene, 0.6f, h) }
        hitTest(ctx)
    }

    private fun capTangential(cam: Vec3, cap: Float) {
        val rel = sluurp - cam; val r = rel.length(); if (r < 1e-6f) return
        val dir = rel / r; val vr = vel.dot(dir)
        val vt = vel - dir * vr; val vtl = vt.length()
        if (vtl > cap) { vt.scale(cap / vtl); vel.set(dir * vr + vt) }
    }

    private fun headBubble(cam: Vec3, r0: Float) {
        val rel = sluurp - cam; val r = rel.length(); if (r >= r0 || r < 1e-6f) return
        val dir = rel / r
        sluurp.set(cam + dir * r0)
        val vr = vel.dot(dir); if (vr < 0f) vel.addScaled(dir, -vr)
    }

    private fun hardLeash(cam: Vec3) {
        val rel = sluurp - cam; val r = rel.length()
        if (r > 6f) { vel.set(rel * (-8f / r)); if (freeT > 0f) { freeT = 0f; reelLen = max(restLen, sluurp.distance(anchor)); reelSpeed = 6f } }
    }

    private var bounceCooldown = 0f

    /**
     * Push-out + reflection every substep (the physics), but the "bong" cue only for a real impact:
     * |vn| ≥ BOUNCE_MIN_V and at most one per BOUNCE_COOLDOWN. A sluurp pinned against the surface by
     * the rope, a scene field or gravity re-contacts it every substep at ≈ a·h ≈ 0.07 u/s, which would
     * otherwise queue ~200 bell voices per second (a clipped drone).
     */
    private fun fractalBounce(scene: Scene, rest: Float, h: Float) {
        bounceCooldown -= h
        val d = scene.de(sluurp)
        if (d < rB) {
            val n = scene.grad(sluurp)
            sluurp.addScaled(n, rB - d)
            val vn = vel.dot(n)
            if (vn < 0f) {
                vel.set(Vec3.reflect(vel, n) * rest)
                if (-vn >= BOUNCE_MIN_V && bounceCooldown <= 0f) { bounceCooldown = BOUNCE_COOLDOWN; onBounce?.invoke(-vn) }
            }
        }
    }

    private val lastExteriorUv = Vec2(); private var haveExterior = false
    private var planeBounceCooldown = 0f

    /**
     * 2D room (DESIGN §4.6): when the sluurp's plane point enters the set interior, reflect the in-plane
     * velocity about the boundary normal and push it back out. Deep inside the set the smooth escape count
     * has no gradient, so the normal is taken from the last exterior point (direction back out of the set);
     * a sluurp that is already interior when the set sweeps under it is left alone.
     *
     * No `onBounce` here: the plane boundary is swept under the sluurp by the pan/dive every frame, so the
     * contact is continuous rather than an impact and used to queue a "plip" every 50 ms — the Tidepool's
     * "too many sound effects over the music". The reflection stays; the cue is 3D-only (`fractalBounce`).
     */
    private fun planeBounce(ctx: ToyCtx, scene: Scene) {
        val d = ctx.camRot.mulT(sluurp - ctx.camCenter)
        if (d.z < 0.05f) return
        val uv = Vec2(d.x / (d.z * ctx.fov), d.y / (d.z * ctx.fov))
        val p = toPlane(uv, ctx)
        val nMax = scene.maxIter(ctx.planeDepth)
        val inside = scene.escapeCount(p, ctx.planeDepth) >= nMax
        planeBounceCooldown -= SUBSTEP
        if (!inside) { lastExteriorUv.set(uv.x, uv.y); haveExterior = true; return }
        if (!haveExterior || planeBounceCooldown > 0f) return
        val n2 = lastExteriorUv - uv
        val l2 = n2.length(); if (l2 < 1e-5f) return
        val nuv = n2 * (1f / l2)                       // toward the exterior, in uv space
        val nw = (ctx.camRot.right * nuv.x + ctx.camRot.up * nuv.y).normalized()
        val vn = vel.dot(nw)
        // push back to the last exterior uv (world offset = Δuv · z · fov along the screen axes)
        sluurp.addScaled(nw, l2 * d.z * ctx.fov)
        if (vn < 0f) vel.set(Vec3.reflect(vel, nw) * 0.6f)
        planeBounceCooldown = 0.05f
    }

    private fun toPlane(uv: Vec2, ctx: ToyCtx): Vec2 {
        val c = cos(ctx.planeRot); val s = sin(ctx.planeRot)
        val x = (c * uv.x - s * uv.y) * ctx.planeScale + ctx.planePan.x
        val y = (s * uv.x + c * uv.y) * ctx.planeScale + ctx.planePan.y
        return Vec2(x, y)
    }

    private fun hitTest(ctx: ToyCtx) {
        val ts = ctx.targets ?: return
        // a HIT needs a swung or flicked sluurp: in free flight, or moving relative to the head-locked anchor faster
        // than HIT_MIN_REL. A yo-yo hanging still 1.4 u ahead of a cruising head plows through every eye the flight
        // passes (measured: 22 unearned hits and 30 whiffs per minute at CRUISE in the Nave, zoom 0 → 24 without a tap);
        // those eyes are now passed through and recycled by the flight, silently
        val swung = freeT > 0f || (vel - anchorVel).length() >= HIT_MIN_REL
        for (i in 0 until 6) {
            val t = ts.slots[i]; if (t.state != Target.State.ACTIVE) { whiffed[i] = false; continue }
            val dist = t.pos.distance(sluurp)
            val hitR = rB + t.radius + 0.02f
            if (!swung) { whiffed[i] = dist < 1.6f * (rB + t.radius); continue }
            if (dist < hitR && ctx.time - lastHitTime > 0.25f) {
                lastHitTime = ctx.time
                val n = (sluurp - t.pos).normalized()
                vel.set(Vec3.reflect(vel, n) * 0.55f)
                sluurp.set(t.pos + n * hitR)
                glow = 1f; squash = 1f
                val clean = freeT > 0f
                if (clean) freeT = max(freeT, 0.35f)
                onHit?.invoke(i, clean)
            } else if (dist < 1.6f * (rB + t.radius)) {
                if (!whiffed[i]) { whiffed[i] = true; t.pulse = max(t.pulse, 0.3f); onWhiff?.invoke() }
            } else whiffed[i] = false
        }
    }

    // ---- flick / recall / re-tether ----

    /** Tap. Returns false if ignored (250 ms lockout). */
    fun flick(ctx: ToyCtx): Boolean {
        if (ctx.time - lastFlickTime < 0.25f) return false
        if (freeT > 0.10f) { recall(); lastFlickTime = ctx.time; return true }
        lastFlickTime = ctx.time
        val sp = vel.length()
        val dir = if (sp > 0.6f) vel / sp else ctx.gaze.copy()
        val v = M.clamp(1.5f * sp + 3.0f, 4.0f, 8.0f)
        val aimed = aimAssist(dir, if (ctx.headOn && !ctx.attract) 8f else 30f, ctx)
        vel.set(aimed * v)
        freeT = 0.6f + 0.06f * (v - 4f)
        reelLen = restLen; glow = max(glow, 0.8f); squash = 0.35f
        onFlick?.invoke(tension)
        return true
    }

    /** Attract-mode auto-flick toward the nearest target (≈ 80 % accuracy). */
    fun autoFlick(ctx: ToyCtx) {
        val ts = ctx.targets ?: return
        var best: Target? = null; var bd = 1e9f
        for (t in ts.slots) if (t.state == Target.State.ACTIVE) { val d = t.pos.distance(sluurp); if (d < bd) { bd = d; best = t } }
        val b = best ?: return
        var dir = (b.pos - sluurp).normalized()
        val jit = M.rad(8f) * (Math.random().toFloat() * 2f - 1f)
        dir = rotateAbout(dir, ctx.camRot.up, jit)
        lastFlickTime = ctx.time
        val v = M.clamp(bd * 2.5f, 4f, 8f)
        vel.set(dir * v); freeT = 0.6f + 0.06f * (v - 4f); reelLen = restLen; glow = max(glow, 0.8f); squash = 0.35f
        onFlick?.invoke(tension)
    }

    private fun rotateAbout(v: Vec3, axis: Vec3, ang: Float): Vec3 {
        val c = cos(ang); val s = sin(ang)
        return (v * c + axis.cross(v) * s + axis * (axis.dot(v) * (1f - c))).normalized()
    }

    private fun aimAssist(dir: Vec3, maxDeg: Float, ctx: ToyCtx): Vec3 {
        val ts = ctx.targets ?: return dir
        var best = M.rad(maxDeg); var out = dir
        for (t in ts.slots) if (t.state == Target.State.ACTIVE) {
            val td = (t.pos - sluurp).normalized()
            val ang = acos(dir.dot(td).coerceIn(-1f, 1f))
            if (ang < best) { best = ang; out = td }
        }
        return out
    }

    fun recall() { freeT = 0f; reelLen = max(restLen, sluurp.distance(anchor)); reelSpeed = 6f; ropeFree = 0.999f }

    private fun onRetether(ctx: ToyCtx) {
        freeT = 0f
        val len = sluurp.distance(anchor)
        reelLen = max(restLen, len); reelSpeed = 3f
        if (len - restLen > 0.05f) {
            tension = 1f; glow = max(glow, 0.5f)
            vel.addScaled((anchor - sluurp).normalized(), 0.5f)
            onSnap?.invoke(M.clamp((len - restLen) / 2f, 0f, 1f))
        }
    }

    // ---- rope (Verlet, cosmetic) ----

    private fun initRope() {
        for (i in 0 until 12) { val t = i / 11f; rope[i].set(Vec3.lerp(anchor, sluurp, t)); ropePrev[i].set(rope[i]) }
        ropeInit = true
    }

    private fun ropeStep(dt: Float, ctx: ToyCtx) {
        if (!ropeInit) initRope()
        val segRest = max(restLen, sluurp.distance(anchor)) / 11f
        val damping = if (freeT > 0f) 0.985f else 0.995f
        val t = ctx.time
        for (i in 1 until 11) {
            val p = rope[i]
            val bx = 0.15f * (sin(p.y * 1.5f + t * 0.4f) * cos(p.z * 1.7f - t * 0.3f))
            val by = 0.15f * (sin(p.z * 1.5f + t * 0.5f) * cos(p.x * 1.3f + t * 0.4f))
            val bz = 0.15f * (sin(p.x * 1.6f - t * 0.35f) * cos(p.y * 1.4f + t * 0.45f))
            val ax = ctx.gravity.x + bx; val ay = ctx.gravity.y + by; val az = ctx.gravity.z + bz
            var nx = p.x + (p.x - ropePrev[i].x) * damping + ax * dt * dt
            var ny = p.y + (p.y - ropePrev[i].y) * damping + ay * dt * dt
            var nz = p.z + (p.z - ropePrev[i].z) * damping + az * dt * dt
            if (i == 1 && ctx.onset) { val pl = 0.6f * ctx.bass * dt; nx += ctx.camRot.right.x * pl; ny += ctx.camRot.right.y * pl; nz += ctx.camRot.right.z * pl }
            ropePrev[i].set(p); p.set(nx, ny, nz)
        }
        rope[0].set(anchor); rope[11].set(sluurp)
        repeat(8) {
            for (i in 0 until 11) {
                val a = rope[i]; val b = rope[i + 1]
                val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
                val l = sqrt(dx * dx + dy * dy + dz * dz); if (l < 1e-6f) continue
                val corr = (l - segRest) / l
                val wa = if (i == 0) 0f else 0.5f; val wb = if (i == 10) 0f else 0.5f
                if (i == 0) { b.x -= dx * corr; b.y -= dy * corr; b.z -= dz * corr }
                else if (i == 10) { a.x += dx * corr; a.y += dy * corr; a.z += dz * corr }
                else { a.x += dx * corr * wa; a.y += dy * corr * wa; a.z += dz * corr * wa; b.x -= dx * corr * wb; b.y -= dy * corr * wb; b.z -= dz * corr * wb }
            }
            rope[0].set(anchor); rope[11].set(sluurp)
        }
    }

    /** Bounding sphere of sluurp + rope (targets are added by the caller). */
    fun bounds(out: Vec3): Float {
        var cx = sluurp.x; var cy = sluurp.y; var cz = sluurp.z
        for (p in rope) { cx += p.x; cy += p.y; cz += p.z }
        cx /= 13f; cy /= 13f; cz /= 13f
        out.set(cx, cy, cz)
        var r = out.distance(sluurp) + rB * 1.5f
        for (p in rope) r = max(r, out.distance(p) + 0.02f)
        return r
    }
}
