package com.tapfractal.gl

import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log

/**
 * Real GPU frame time via GL_EXT_disjoint_timer_query. The Java GLES30 binding has no EXT entry
 * points, but glBeginQuery/glEndQuery/glGetQueryObjectuiv pass the target enum straight through to
 * the driver, so GL_TIME_ELAPSED_EXT (0x88BF) works wherever the extension is advertised (Adreno
 * 6xx does). A ring of queries is polled without stalling; `lastMs` is the newest finished result
 * (only the low 32 bits of the nanosecond count are read — fine below 4 s). Falls back to
 * `available = false` (the renderer then uses the frame period) when the extension is missing or
 * a query raises a GL error.
 *
 * DVFS normalisation: the GPU governor lowers the clock whenever the frame fits its period with room
 * to spare (measured: at 30 fps pacing a Nave frame reads ~21 ms at every rung, gpubusy ≈ 70 %), so
 * a raw time says little about headroom. Every third frame a fixed ALU workload (96×96 px, 300
 * iterations, its own timer query) is drawn; its time scales with 1/clock, and the running minimum
 * over the session is the max-clock reference. `clockScale = calibMin / calibNow` (≤ 1) turns
 * `lastMs` into `trueMs`, the cost the same frame would have at the full clock — what the rung
 * controller needs to predict whether the next rung fits. Costs ≈ 0.5 ms every third frame.
 *
 * Robustness (fixer 2026-09-03 — one session read ~55 ms/vsync at every rung after an `install -r` and sank
 * the Tidepool to rung 0 until a force-stop; CORE_API §7.18):
 *  - every [init] is a new EGL context: both rings are regenerated (a query name is never reused across
 *    contexts), every in-flight slot is dropped, and a context generation counter tags the state;
 *  - GL_GPU_DISJOINT_EXT is read ONCE per frame after the results of that frame were collected (reading it
 *    clears it, so per-result reads raced the two rings): a disjoint event discards the results collected in
 *    that frame and taints every query still in flight, and the calibration reading is restarted;
 *  - the full-clock reference is the SECOND-smallest calibration reading of the session, so one garbage-low
 *    result cannot poison the session (the old running minimum did, and was persisted);
 *  - the persisted reference carries the boot time it was measured in — a reference from an earlier boot is
 *    ignored and the calibration re-runs (the file is rewritten for this boot);
 *  - [resync] (called by the renderer when its plausibility guard trips) throws both rings away and starts
 *    over inside the same context, so a stuck query stream cannot outlive the trip.
 */
class GpuTimer {
    companion object {
        const val TAG = "TapFractal"
        const val GL_TIME_ELAPSED_EXT = 0x88BF
        const val GL_GPU_DISJOINT_EXT = 0x8FBB
        const val RING = 4
        const val CALIB_PX = 96
        const val CALIB_EVERY = 3
        /** Any elapsed time above this (ns) is not a frame of ours (a vsync-paced frame is bounded by the swap chain). */
        const val MAX_PLAUSIBLE_NS = 500_000_000L
        /** Persisted-reference boot match tolerance (ms): the boot time estimate jitters by the scheduling of the two clock reads. */
        const val BOOT_TOLERANCE_MS = 5_000L
        private const val CALIB_FRAG = """#version 300 es
precision highp float;
uniform float uSeed;
out vec4 fragColor;
void main() {
    vec2 p = gl_FragCoord.xy * 0.013 + uSeed;
    float a = 0.0;
    for (int i = 0; i < 300; i++) {
        p = vec2(p.x * p.x - p.y * p.y, 2.0 * p.x * p.y) * 0.5 + vec2(0.31, 0.11) * fract(a);
        a += 0.37 * sin(p.x) + 0.11 * p.y;
        p = fract(p * 1.3) - 0.5;
    }
    fragColor = vec4(fract(a), p, 1.0);
}
"""
    }

    var available = false; private set
    /** Newest finished GPU frame time in ms (NaN until the first result). */
    var lastMs = Float.NaN; private set
    /** Newest calibration draw time (ms) and the session reference (second-smallest reading = the max-clock cost). */
    var calibMs = Float.NaN; private set
    var calibMin = Float.NaN; private set
    /** calibMin / calibMs ≤ 1: the fraction of the max clock the GPU is currently running at. */
    val clockScale: Float get() = if (calibMs.isNaN() || calibMin.isNaN() || calibMs <= 0f) 1f else (calibMin / calibMs).coerceIn(0.2f, 1f)
    /** Frame time normalised to the max GPU clock (NaN until measured). */
    val trueMs: Float get() = if (lastMs.isNaN()) Float.NaN else lastMs * clockScale
    /** Diagnostics: EGL contexts seen, disjoint events, resyncs, results dropped (disjoint / taint / implausible). */
    var contextGen = 0; private set
    var disjointEvents = 0; private set
    var resyncs = 0; private set
    var dropped = 0; private set

    private val ids = IntArray(RING)
    private val issued = BooleanArray(RING)
    /** A slot whose result must be dropped: the frame stalled on a shader compile / a long CPU period (or a
     *  disjoint event fired while it was in flight), so the elapsed time says nothing about the frame's cost. */
    private val tainted = BooleanArray(RING)
    private var head = 0
    private var active = false
    private var taintActive = false
    /** Optional file that persists the full-clock reference across launches WITHIN A BOOT — a light session never
     *  pushes the clock to the top, and without the reference every `trueMs` reads 1.4× too high. */
    var calibFile: java.io.File? = null
    private var calibSaved = Float.NaN
    private val tmp = IntArray(1)
    private val tmpDisjoint = IntArray(1)

    private val cids = IntArray(RING)
    private val cissued = BooleanArray(RING)
    private var chead = 0
    private var calibProg: ShaderProgram? = null
    private val calibFbo = IntArray(1); private val calibTex = IntArray(1)
    private var seed = 0f
    private var vertSrc = ""
    // the two smallest calibration readings of the session (m1 ≤ m2); the reference is m2 (one outlier cannot poison it)
    private var calibM1 = Float.NaN; private var calibM2 = Float.NaN
    /** calibMin came from a single in-session reading (not persisted; replaced by the next confirmed one). */
    private var calibProvisional = false
    private var lastDisjointLogNs = 0L
    // results collected this frame, published only if no disjoint fired (see poll/beginFrame)
    private var pendFrame = Float.NaN; private var pendCalib = Float.NaN

    /** The boot this process runs in, as the wall-clock time of boot (ms). Two reads a few ms apart agree within BOOT_TOLERANCE_MS. */
    private fun bootTimeMs(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** (Re)creates the query rings and the calibration draw; also reached after an EGL context loss, so every ring slot is reset. */
    fun init(vertSrc: String) {
        this.vertSrc = vertSrc
        contextGen++
        // stale ring state from a lost context would poll never-issued query names forever (GL_INVALID_OPERATION
        // each frame, `issued[head]` stuck true → no more timing, lastMs frozen for the rung controller)
        resetRings()
        lastMs = Float.NaN; calibMs = Float.NaN; calibProg = null
        if (calibMin.isNaN()) restoreCalib()
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        available = ext.contains("GL_EXT_disjoint_timer_query")
        if (!available) { Log.w(TAG, "no GL_EXT_disjoint_timer_query — rung controller falls back to the frame period"); return }
        GLES30.glGenQueries(RING, ids, 0)
        GLES30.glGenQueries(RING, cids, 0)
        calibProg = ShaderProgram.build(vertSrc, CALIB_FRAG, "gpu-calib")
        GLES30.glGenFramebuffers(1, calibFbo, 0)
        GLES30.glGenTextures(1, calibTex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, calibTex[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, CALIB_PX, CALIB_PX)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, calibFbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, calibTex[0], 0)
        val ok = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!ok || calibProg == null) { Log.w(TAG, "GPU calibration draw unavailable — no DVFS normalisation"); calibProg = null }
        // a fresh context starts with a clean disjoint flag (reading it clears it)
        GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, tmpDisjoint, 0)
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* drain */ }
        Log.i(TAG, "GPU timer queries enabled (context #$contextGen, calibration ${if (calibProg != null) "on" else "off"}, reference ${if (calibMin.isNaN()) "none" else "%.3f ms".format(calibMin)})")
    }

    private fun resetRings() {
        issued.fill(false); cissued.fill(false); tainted.fill(false); head = 0; chead = 0; active = false; taintActive = false
        pendFrame = Float.NaN; pendCalib = Float.NaN
    }

    /**
     * Throw both query rings away and start over inside the current context (the renderer's plausibility guard
     * tripped: a sustained raw time no frame of ours can have). The reference and the calibration reading restart too.
     */
    fun resync(reason: String) {
        if (!available) return
        resyncs++
        GLES30.glDeleteQueries(RING, ids, 0); GLES30.glDeleteQueries(RING, cids, 0)
        GLES30.glGenQueries(RING, ids, 0); GLES30.glGenQueries(RING, cids, 0)
        resetRings()
        lastMs = Float.NaN; calibMs = Float.NaN
        calibM1 = Float.NaN; calibM2 = Float.NaN            // re-measure the reference; the persisted one stays a prior
        if (calibProvisional) { calibMin = Float.NaN; calibProvisional = false }
        GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, tmpDisjoint, 0)
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* drain */ }
        Log.w(TAG, "GPU timer resync #$resyncs ($reason): query rings recreated, calibration restarted")
    }

    // ---- persisted reference: "v2 <bootTimeMs> <ms>"; an older boot's reference is ignored (re-run the calibration) ----
    private fun restoreCalib() {
        val f = calibFile ?: return
        try {
            if (!f.exists()) return
            val parts = f.readText().trim().split(Regex("\\s+"))
            var v: Float? = null; var boot: Long? = null
            if (parts.size >= 3 && parts[0] == "v2") { boot = parts[1].toLongOrNull(); v = parts[2].toFloatOrNull() }
            else if (parts.size == 1) v = parts[0].toFloatOrNull()   // v1 file (no boot stamp): treat as another boot
            if (v == null || !(v > 0.05f && v < 5f)) { Log.w(TAG, "GPU calibration file unreadable — recalibrating"); return }
            val now = bootTimeMs()
            if (boot == null || kotlin.math.abs(boot - now) > BOOT_TOLERANCE_MS) {
                Log.i(TAG, "GPU calibration reference %.3f ms is from a previous boot (%s) — ignored, recalibrating this boot".format(v, boot?.toString() ?: "v1 file"))
                return
            }
            calibMin = v; calibSaved = v
            Log.i(TAG, "GPU calibration reference restored: %.3f ms (this boot)".format(v))
        } catch (_: Exception) {}
    }

    private fun saveCalib() {
        val f = calibFile ?: return
        if (!calibSaved.isNaN() && calibMin > calibSaved * 0.98f) return
        try { f.writeText("v2 %d %.4f".format(bootTimeMs(), calibMin)); calibSaved = calibMin } catch (_: Exception) {}
    }

    /** A calibration reading: keep the two smallest of the session; the reference is the second-smallest. */
    private fun onCalib(ms: Float) {
        calibMs = ms
        if (calibM1.isNaN() || ms < calibM1) { calibM2 = calibM1; calibM1 = ms }
        else if (calibM2.isNaN() || ms < calibM2) calibM2 = ms
        if (calibM2.isNaN()) {
            // One reading only (session start, or right after resync()): use it provisionally so clockScale has
            // something to work with, but never persist it — a single garbage-low value must not become the boot's
            // reference. The second-smallest reading below replaces a provisional one unconditionally.
            if (calibMin.isNaN()) { calibMin = calibM1; calibProvisional = true }
            return
        }
        val ref = calibM2
        if (calibMin.isNaN() || calibProvisional || ref < calibMin) { calibMin = ref; calibProvisional = false; saveCalib() }
    }

    /**
     * Start timing this frame. First collects the results that finished since the last frame (both rings) and reads
     * GL_GPU_DISJOINT_EXT once: a disjoint event discards them and taints everything still in flight.
     */
    fun begin() {
        if (!available || active) return
        collect()
        if (issued[head]) return               // ring full: skip timing this frame rather than stall
        GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, ids[head])
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) { available = false; Log.w(TAG, "timer query begin failed — disabled"); return }
        active = true
    }

    /** Mark the frame being timed as unrepresentative (its result is dropped when it arrives). */
    fun taint() { if (active) taintActive = true }

    fun end() {
        if (!available || !active) return
        GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
        active = false
        issued[head] = true; tainted[head] = taintActive; taintActive = false
        head = (head + 1) % RING
    }

    /** Draw the calibration workload inside its own query (every CALIB_EVERY-th frame). Call after end(). */
    fun calibrate(frameIdx: Long) {
        val prog = calibProg ?: return
        if (!available || active) return
        if (frameIdx % CALIB_EVERY != 0L || cissued[chead]) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, calibFbo[0])
        GLES30.glViewport(0, 0, CALIB_PX, CALIB_PX)
        prog.use()
        seed = (seed + 0.173f) % 7f
        prog.set1f("uSeed", seed)
        GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, cids[chead])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        cissued[chead] = true
        chead = (chead + 1) % RING
    }

    /** Collect finished results of both rings, then the disjoint flag (spec protocol: results read BEFORE the flag are valid only if it is clear). */
    private fun collect() {
        pendFrame = Float.NaN; pendCalib = Float.NaN
        poll(ids, issued, head, true)
        poll(cids, cissued, chead, false)
        GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, tmpDisjoint, 0)
        if (tmpDisjoint[0] != 0) {
            disjointEvents++
            var inflight = 0
            for (i in 0 until RING) { if (issued[i]) { tainted[i] = true; inflight++ } }
            for (i in 0 until RING) { if (cissued[i]) { cissued[i] = false; inflight++ } }   // calibration queries are simply forgotten
            if (!pendFrame.isNaN()) dropped++
            if (!pendCalib.isNaN()) dropped++
            calibMs = Float.NaN                                // the next clean reading re-establishes the current clock
            val now = System.nanoTime()
            if (now - lastDisjointLogNs > 2_000_000_000L) { lastDisjointLogNs = now; Log.i(TAG, "GPU timer: disjoint event #$disjointEvents — ${if (pendFrame.isNaN()) 0 else 1} + ${if (pendCalib.isNaN()) 0 else 1} results discarded, $inflight in flight tainted") }
            return
        }
        if (!pendFrame.isNaN()) lastMs = pendFrame
        if (!pendCalib.isNaN()) onCalib(pendCalib)
    }

    private fun poll(q: IntArray, flags: BooleanArray, start: Int, frameRing: Boolean) {
        var i = start
        for (k in 0 until RING) {           // walk the ring from the oldest issued slot
            if (flags[i]) {
                GLES30.glGetQueryObjectuiv(q[i], GLES30.GL_QUERY_RESULT_AVAILABLE, tmp, 0)
                if (tmp[0] == 0) break
                GLES30.glGetQueryObjectuiv(q[i], GLES30.GL_QUERY_RESULT, tmp, 0)
                val ns = tmp[0].toLong() and 0xFFFFFFFFL
                val drop = frameRing && tainted[i]
                if (frameRing) tainted[i] = false
                flags[i] = false
                if (drop || ns <= 0L || ns > MAX_PLAUSIBLE_NS) dropped++
                else if (frameRing) pendFrame = ns * 1e-6f else pendCalib = ns * 1e-6f
            }
            i = (i + 1) % RING
        }
    }

    fun release() {
        if (!available) return
        GLES30.glDeleteQueries(RING, ids, 0); GLES30.glDeleteQueries(RING, cids, 0)
        calibProg?.release(); GLES30.glDeleteFramebuffers(1, calibFbo, 0); GLES30.glDeleteTextures(1, calibTex, 0)
        available = false
    }
}
