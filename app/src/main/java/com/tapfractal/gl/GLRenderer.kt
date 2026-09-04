package com.tapfractal.gl

import android.content.res.AssetManager
import android.opengl.GLSurfaceView
import android.util.Log
import com.tapfractal.engine.Game
import com.tapfractal.head.HeadTracker
import com.tapfractal.ui.Hud
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Frame orchestration on the GL thread, once per VSYNC: head → [game at slice 0] → HUD → pipeline
 * (one scene slice + the timewarped present), the rung controller (fed by real GPU frame time from
 * [GpuTimer], falling back to the frame period), and the "frame ms / rung / fps" log line every 5 s
 * (always on).
 *
 * Pacing (CORE_API §10.1): the activity requests a render on EVERY vsync. A 3D room renders its scene
 * pair in `slices` = 2 vsyncs (30 fps scene rate) and the pipeline presents every vsync through the
 * rotation-only timewarp with the newest head basis; the game/physics run once per pair (slice 0) with
 * the accumulated dt, so `rs` is identical for both slices. The Tidepool, the menu and the notice run
 * 1 slice (60 fps). The slice count is latched at slice 0 and only changes at a pair boundary.
 *
 * Debug flags (`adb shell settings put global tapfractal_debug "..."`, polled every 6 frames):
 * pipeline flags (noscene, noecho, nobloom, nopost, nohud, notoys, mono, noapl, nowarp, rung=N) plus
 * `pulse=<epoch ms>` (fire a visual-only hit pulse/flash at that wall-clock time) and
 * `flick=<epoch ms>` (a real tap/flick at that time) for timed captures. Any non-empty value also
 * shows the "yaw/pitch/roll · rung · ms" HUD line and the 1 Hz `motion` log line.
 */
class GLRenderer(am: AssetManager, private val game: Game, private val head: HeadTracker,
                 private val resolver: android.content.ContentResolver? = null,
                 calibFile: java.io.File? = null) : GLSurfaceView.Renderer {
    companion object {
        const val TAG = "TapFractal"; const val VSYNC_MS = 16.667f
        /** Head rate (rad/s) below which the head counts as still for the deferred shader compiles. */
        const val STILL_RAD_S = 0.15f
        /** GPU-timer plausibility: a raw time above this on a frame that presented on time is not the frame's own cost. */
        const val GPU_IMPLAUSIBLE_MS = 2f * VSYNC_MS
        const val GPU_IMPLAUSIBLE_N = 20
    }
    private var headStillS = 0f
    private var gpuImplausible = 0

    val pipeline = Pipeline(am)
    private val hudTex = HudTexture()
    val hud = Hud(game, hudTex, am)
    private val gpuTimer = GpuTimer().also { it.calibFile = calibFile }
    private var lastNs = 0L
    private var frames = 0
    private var accumMs = 0f; private var maxMs = 0f
    private var logAccumS = 0f
    private var gpuAccum = 0f; private var gpuMax = 0f; private var gpuN = 0
    private var frameIdx = 0L
    private var ready = false
    private var cpuUpdate = 0f; private var cpuHud = 0f; private var cpuRender = 0f
    private var misses = 0
    private var lastDebugFlags = ""
    // pair cadence
    private var slice = 0
    private var slices = 1
    private var gameDt = 0f
    private var lastPresents = 0L; private var lastPairs = 0L
    // motion diagnostics
    private var diagAccumS = 0f; private var diagSpikes = 0
    private var accelMax5s = 0f; private var over4In5s = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.i(TAG, "surface created")
        pipeline.init(game.scenes)
        hudTex.createGL()
        gpuTimer.init(pipeline.vertSource)
        lastNs = 0L
        slice = 0; slices = 1; gameDt = 0f
        ready = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        pipeline.screenW = width; pipeline.screenH = height
        Log.i(TAG, "surface ${width}x$height")
    }

    private fun applyDebugFlags(f: String) {
        pipeline.applyDebug(f)
        val dbg = f.isNotEmpty()
        if (dbg != hud.debugEnabled) game.hudDirty = true   // redraw so a deleted flag takes its debug line with it (it stayed baked in the HUD texture)
        hud.debugEnabled = dbg
        for (tok in f.split(',')) {
            val t = tok.trim()
            if (t.startsWith("pulse=")) game.debugPulseAt = t.substringAfter('=').toLongOrNull() ?: 0L
            if (t.startsWith("flick=")) game.debugFlickAt = t.substringAfter('=').toLongOrNull() ?: 0L
        }
        Log.i(TAG, "debug flags: '$f'")
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!ready) return
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 1f / 60f else ((now - lastNs) * 1e-9f).coerceIn(1e-4f, 0.1f)
        val periodMs = if (lastNs == 0L) VSYNC_MS else (now - lastNs) * 1e-6f
        lastNs = now
        frameIdx++

        // debug flags (polled every 6 frames ≈ 100 ms so timed pulse/flick flags land within a tenth of a second)
        if (resolver != null && frameIdx % 6L == 3L) {
            val f = try { android.provider.Settings.Global.getString(resolver, "tapfractal_debug") ?: "" } catch (_: Exception) { "" }
            if (f != lastDebugFlags) { lastDebugFlags = f; applyDebugFlags(f) }
        }
        // scheduled debug pulses (wall clock; stale by > 5 s = ignored)
        if (game.debugPulseAt != 0L || game.debugFlickAt != 0L) {
            val wall = System.currentTimeMillis()
            if (game.debugPulseAt != 0L && wall >= game.debugPulseAt) { if (wall - game.debugPulseAt < 5000L) { game.debugPulse(); Log.i(TAG, "debug pulse fired (+${wall - game.debugPulseAt} ms)") }; game.debugPulseAt = 0L }
            if (game.debugFlickAt != 0L && wall >= game.debugFlickAt) { if (wall - game.debugFlickAt < 5000L) { game.debugFlick(); Log.i(TAG, "debug flick fired (+${wall - game.debugFlickAt} ms)") }; game.debugFlickAt = 0L }
        }
        // lazy shader compiles: one per frame after the first frame is on screen (a compile takes ~1.3 s and
        // freezes the picture), so the three non-current rooms are compiled only while a stall is invisible:
        // the notice/menu is open, or the head has been still for > 1 s. A room visited before its compile
        // stalls once on the switch instead (Pipeline.render compiles on first draw).
        headStillS = if (head.omegaMag < STILL_RAD_S) headStillS + dt else 0f
        val stallOk = game.menuOpen || game.state == Game.State.NOTICE || headStillS > 1f
        val compiled = frameIdx > 2 && stallOk && pipeline.compilePending(game.scene)

        val t0 = System.nanoTime()
        // the head is sampled every vsync (the timewarp presents with it); prediction horizon = one vsync's worth
        head.update(dt, VSYNC_MS / 1000f)
        gameDt += dt
        if (slice == 0) {
            // a new pair: run the game with the time since the last pair, then latch this pair's slice count
            game.update(gameDt); gameDt = 0f
            if (game.rungSwitchRequest) { game.rungSwitchRequest = false; pipeline.rungs.onSceneSwitch(game.scene.slot, game.rs.rungCap, game.scene.is2D, now) }
            val want = if (game.menuOpen || game.state == Game.State.NOTICE || game.scene.is2D) 1 else pipeline.rungs.slices
            if (want != slices) Log.i(TAG, "pacing: ${want}-slice pairs (${if (want == 2) "scene 30 fps + timewarp, present 60 fps" else "60 fps"})")
            slices = want
        }
        val t1 = System.nanoTime()
        val gpuMs = gpuTimer.lastMs
        if (hud.debugEnabled)   // four String.format calls per frame otherwise, for a line nobody reads
            hud.debugLine = "${head.debugString()} · rung ${pipeline.currentRung(game.rs)}${if (pipeline.rungs.mono) "m" else ""}@${pipeline.rungs.pacingName}${if (pipeline.timewarpActive) "tw" else ""} · ${if (gpuMs.isNaN()) "–" else "%.1f".format(gpuMs)} ms · apl ${if (pipeline.aplMean.isNaN()) "–" else "%.2f".format(pipeline.aplMean)} e${"%.2f".format(pipeline.aplExposure)}"
        hud.draw(dt)
        val tex = if (hud.hasContent) hudTex.upload() else 0   // a fully transparent HUD skips the composite pass
        val t2 = System.nanoTime()
        gpuTimer.begin()
        pipeline.compiledInRender = false
        pipeline.render(game.rs, tex, game.presentRot(), slice, slices, dt)
        // a frame that compiled a program or followed a long CPU stall measures the GPU's idle wait, not its cost
        if (compiled || pipeline.compiledInRender || periodMs > 100f) gpuTimer.taint()
        gpuTimer.end()
        gpuTimer.calibrate(frameIdx)
        val t3 = System.nanoTime()
        cpuUpdate += (t1 - t0) * 1e-6f; cpuHud += (t2 - t1) * 1e-6f; cpuRender += (t3 - t2) * 1e-6f
        val gameRan = slice == 0
        slice = (slice + 1) % slices

        // rung controller, fed per vsync (a compile frame is a CPU stall, not GPU load; the menu and a crossfade are not representative)
        val missed = periodMs > 1.5f * VSYNC_MS
        if (missed) misses++
        // a stall (compile, resume) is not running time for the controller's settle window / gaps (CORE_API §10.3)
        if (periodMs > 100f && frameIdx > 5) pipeline.rungs.stall(((periodMs - VSYNC_MS) * 1e6f).toLong())
        if (!compiled && !pipeline.compiledInRender && periodMs <= 100f && !game.menuOpen && !game.rs.crossfading && frameIdx > 5) {
            // plausibility guard (2026-09-03 integration): one session read a raw GPU time of ~55 ms per vsync at EVERY
            // rung while presenting a steady 60 fps with no misses — physically impossible for the frame's own work —
            // and the controller sank the Tidepool to rung 0 (192×144) for good. A sustained raw time above twice the
            // vsync on frames that presented on time is an invalid query stream: feed the period-based estimate instead.
            var haveGpu = gpuTimer.available && !gpuMs.isNaN()
            if (haveGpu) {
                if (gpuMs > GPU_IMPLAUSIBLE_MS && !missed) gpuImplausible++ else gpuImplausible = 0
                if (gpuImplausible >= GPU_IMPLAUSIBLE_N) {
                    if (gpuImplausible == GPU_IMPLAUSIBLE_N) {
                        Log.w(TAG, "GPU timer implausible: raw %.1f ms/vsync while presenting on time — using the frame period until it recovers".format(gpuMs))
                        gpuTimer.resync("raw %.1f ms/vsync at 60 Hz presentation".format(gpuMs))   // a stuck query stream must not outlive the trip (CORE_API §7.18)
                    }
                    haveGpu = false
                }
            }
            val raw = if (haveGpu) gpuMs else if (missed) periodMs else 0.6f * VSYNC_MS
            val trueMs = if (haveGpu) gpuTimer.trueMs else raw
            pipeline.rungs.onFrame(raw, trueMs, missed, now, game.rs.rungCap, game.rs.qualityMode, game.rs.eyeSep > 0f && !pipeline.dbgMono, game.rs.hitAge)
        }

        // ---- motion diagnostics (camera acceleration from the flight's position trace) ----
        val fl = game.flight
        if (gameRan) {   // once per game step
            if (fl.dAccelLast > accelMax5s) accelMax5s = fl.dAccelLast
            if (fl.dAccelLast > Flight_ACCEL_LIMIT && !game.scene.is2D) {
                over4In5s++
                if (hud.debugEnabled && diagSpikes < 4) { diagSpikes++
                    Log.i(TAG, "motion SPIKE a=%.1f u/s² spd=%.2f de=%.3f skin=%.3f zoom=%.2f rung %d%s".format(fl.dAccelLast, fl.speed, game.scene.de(fl.pos), fl.skin, game.zoom, pipeline.currentRung(game.rs), if (pipeline.rungs.mono) "m" else "")) }
            }
        }
        diagAccumS += dt
        if (diagAccumS >= 1f) {
            if (hud.debugEnabled) Log.i(TAG, "motion 1s: steps %d spd %.2f aMax %.2f u/s² over4 %d snapMax %.3f slowJump %.2f curMax %.2f de %.3f skin %.3f zoom %.2f rung %d%s %s-slice tw=%s warpMax %.2f°".format(
                fl.dFrames, fl.speed, fl.dAccelMax, fl.dOver4, fl.dSnapMax, fl.dSlowJumpMax, fl.dCurMax, game.scene.de(fl.pos), fl.skin, game.zoom,
                pipeline.currentRung(game.rs), if (pipeline.rungs.mono) " mono" else "", slices, pipeline.timewarpActive, pipeline.warpMaxDeg))
            fl.diagReset(); diagAccumS = 0f; diagSpikes = 0
        }

        frames++; accumMs += periodMs; if (periodMs > maxMs) maxMs = periodMs; logAccumS += dt
        if (!gpuMs.isNaN()) { gpuAccum += gpuMs; gpuN++; if (gpuMs > gpuMax) gpuMax = gpuMs }
        if (logAccumS >= 5f) {
            val n = frames.toFloat(); val avg = accumMs / n
            val presents = pipeline.presents - lastPresents; val pairs = pipeline.pairs - lastPairs
            lastPresents = pipeline.presents; lastPairs = pipeline.pairs
            Log.i(TAG, "frame %.1f ms / rung %d%s / %.1f fps / pacing %s (%d-slice, present %.1f Hz, scene %.1f Hz, timewarp %s, warp max %.2f°) / gpu %.1f ms (max %.1f, clock %.0f%%, true ema %.1f peak %.1f ms/vsync, calib %.2f/%.2f) (period max %.1f ms, misses %d, cpu update %.1f hud %.1f submit %.1f ms, apl %.3f exp %.2f, eye %dx%d, accel max %.2f u/s² over-cap %d, rung changes after settle %d, %s)".format(
                avg, pipeline.currentRung(game.rs), if (pipeline.rungs.mono) " mono" else "", 1000f / avg, pipeline.rungs.pacingName, slices, presents / logAccumS, pairs / logAccumS,
                if (pipeline.timewarpActive) "on" else "off", pipeline.warpMaxDeg, if (gpuN > 0) gpuAccum / gpuN else Float.NaN, gpuMax,
                gpuTimer.clockScale * 100f, pipeline.rungs.ema, pipeline.rungs.peak, gpuTimer.calibMs, gpuTimer.calibMin, maxMs, misses,
                cpuUpdate / n, cpuHud / n, cpuRender / n, pipeline.aplMean, pipeline.aplExposure,
                pipeline.lastEyeW, pipeline.lastEyeH, accelMax5s, over4In5s, pipeline.rungs.changesAfterSettle, game.scene.name))
            Log.i(TAG, game.debugState())
            frames = 0; accumMs = 0f; maxMs = 0f; logAccumS = 0f; cpuUpdate = 0f; cpuHud = 0f; cpuRender = 0f; misses = 0
            gpuAccum = 0f; gpuMax = 0f; gpuN = 0; accelMax5s = 0f; over4In5s = 0; pipeline.warpMaxDeg = 0f
        }
    }

    private val Flight_ACCEL_LIMIT = com.tapfractal.engine.Flight.ACCEL_MAX + 0.05f
}
