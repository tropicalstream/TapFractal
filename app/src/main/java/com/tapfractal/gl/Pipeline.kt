package com.tapfractal.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log
import com.tapfractal.Mat3
import com.tapfractal.Vec3
import com.tapfractal.engine.RenderState
import com.tapfractal.engine.SceneDraw
import com.tapfractal.scenes.Scene
import kotlin.math.abs
import kotlin.math.floor

/**
 * Adaptive-resolution rung controller (DESIGN §7.3 + CORE_API §10, rewritten for the timewarp).
 *
 * The scene is rendered in `slices` per PAIR: 1 slice = the whole scene every vsync (60 fps, the
 * Tidepool), 2 slices = half the rows on each of two vsyncs (30 fps scene rate, every 3D room), while
 * the post pass presents EVERY vsync through the rotation-only timewarp. The slice count is fixed per
 * room for the whole visit — no 60↔30 flips mid-room — so the only thing this controller moves is the
 * rung, and only within these rules:
 *  - per-vsync budget: the measured GPU time of one vsync (one slice + the present pass) must stay
 *    under `HI` (15.5 ms); a climb is attempted only when the PREDICTED cost of the next rung ≤ `BUDGET`
 *    (13.5 ms) for 45 frames, judged on the recent peak;
 *  - stereo vs. mono is decided ONCE per room entry (at 2 s, from the measured cost): stereo at one
 *    rung lower beats mono at a higher rung; a room whose stereo cost does not fit at rung 3 renders
 *    mono for the visit. A stereo room never drops below rung 3 (rung ≤ 2 would be mono by the
 *    ladder — a flip);
 *  - after the 6 s settling window a rung changes at most once per 5 s, never in the 2 s after a hit,
 *    and always by ±1.
 * Quality High: the scene cap, no controller (2 slices in 3D). Quality Low: cap 3. Per-slot memory
 * restores a room's settled (rung, mono) so a revisit does not re-settle through the ladder.
 */
class RungController {
    companion object {
        /** Relative scene cost per rung ≈ scale² × step budget. */
        val COST = floatArrayOf(0.036f, 0.061f, 0.096f, 0.142f, 0.200f, 0.250f, 0.3025f, 0.3844f, 0.490f, 0.5625f)
        /** Per-vsync fixed cost at the full clock: timewarp + post + HUD + probe (+ bloom/echo on the completing vsync); measured 1.1 ms with `noscene`. */
        const val FIXED_MS = 1.5f
        /** Clean (fed) frames needed before the once-per-entry stereo/mono decision. */
        const val DECIDE_FRAMES = 90
        /** Per-vsync thresholds, both pacings (a slice must fit one vsync for the warp to present on time). */
        const val HI = 15.5f; const val BUDGET = 13.5f
        const val MODE_AUTO = 0; const val MODE_LOW = 1; const val MODE_HIGH = 2
        const val SETTLE_NS = 6_000_000_000L
        /** Settling also needs this many FED (measured) vsyncs: a launch spends ~8.5 s in lazy shader compiles during which nothing is measured. */
        const val SETTLE_FED_FRAMES = 360   // 6 s of measured vsyncs (= SETTLE_NS); stall() already shifts the wall clock
        const val DECIDE_NS = 3_000_000_000L
        /** Fed frames after which the entry peak is reset: the first pairs of a room have no reprojection depth (2× steps) and would otherwise anchor the decision. */
        const val WARM_FRAMES = 30
        const val CHANGE_GAP_NS = 5_000_000_000L
        /** After settling a CLIMB waits this long (a drop keeps CHANGE_GAP_NS): resolution steps are the one visible "pop" left, so ≤ 3/min. */
        const val CLIMB_GAP_SETTLED_NS = 20_000_000_000L
        const val HIT_GUARD_NS = 2_000_000_000L
        /** Stereo needs at least this rung (rung ≤ 2 is mono by the ladder). */
        const val STEREO_FLOOR = 3
    }

    var rung = 5
    /** Scene slices per pair: 1 = 60 fps scene rate, 2 = 30 fps scene rate + timewarp. Fixed per room visit. */
    var slices = 1
        private set
    /** Compatibility name: 1 = 60 fps pacing, 2 = 30 fps pacing. */
    val pacing: Int get() = slices
    /** True when the room renders one eye rect (the once-per-entry decision; also false whenever the user's stereo is Off). */
    var mono = false
        private set
    private var stereoWanted = true
    private var decided = false
    /** A mono decision gets ONE recovery to stereo when stereo is provably cheap (a start pinned in a crevice must not cost the visit). */
    private var stereoRecovered = false
    private var recoverUnder = 0
    var ema = 12f
        private set
    /** Short EMA of the RAW time (τ ≈ 8 frames): a 0.7 s hit flash is a real overload for its duration
     *  but must cost one rung, not a cascade — so "over" is judged on this instead of 3 raw frames. */
    private var rawEma = 12f
    private var over = 0
    private var under = 0
    private var lastDownNs = 0L
    private var lastChangeNs = 0L
    private var lastUpNs = 0L
    private var upDwellNs = 1_500_000_000L
    private var entryNs = 0L
    private var windowFrames = 0
    private var windowMisses = 0
    private var mode = MODE_AUTO
    private var slot = 0
    private var is2D = false
    private var fedSinceEntry = 0
    /** Rung changes since the settling window ended (for the log / acceptance). */
    var changesAfterSettle = 0
        private set

    // per-slot memory of the settled state (index = slot − 1)
    private val memRung = IntArray(4) { -1 }
    private val memMono = BooleanArray(4)

    /** Decaying max of the true per-vsync cost (τ ≈ 6 s at 60 Hz): climbs are judged on the recent PEAK, not the mean,
     *  because a room's cost swings 2–3× with the view (grazing the Nave's bulb vs. open space). */
    var peak = 12f
        private set

    /** Relative per-vsync scene cost of a configuration: rung ladder × 2 for two eye rects (rung ≥ 3, stereo), ÷ slices. */
    private fun cost(r: Int, monoCfg: Boolean): Float =
        COST[r.coerceIn(0, 9)] * (if (r >= 3 && !monoCfg && stereoWanted) 2f else 1f) / slices

    /** Predicted GPU ms per vsync at (rung `to`, `toMono`) given the recent peak cost at the current configuration. */
    fun predict(from: Int, to: Int, fromMono: Boolean = mono, toMono: Boolean = mono): Float =
        FIXED_MS + maxOf(maxOf(ema, peak) - FIXED_MS, 0.5f) * cost(to, toMono) / cost(from, fromMono)
    /** The same from the current EMA alone (the room decision: the entry peak still carries the settle-phase rungs). */
    private fun predictEma(from: Int, to: Int, fromMono: Boolean, toMono: Boolean): Float =
        FIXED_MS + maxOf(ema - FIXED_MS, 0.5f) * cost(to, toMono) / cost(from, fromMono)

    /**
     * @param rawMs   measured GPU time of this vsync (decides "does not fit": down)
     * @param trueMs  the same normalised to the max GPU clock (decides "would the next rung fit": up)
     * @param stereo  the user wants stereo parallax (eyeSep > 0, no mono debug flag)
     * @param hitAgeS seconds since the last hit (no rung change within HIT_GUARD of one)
     */
    fun onFrame(rawMs: Float, trueMs: Float, missedVsync: Boolean, nowNs: Long, cap: Int, qualityMode: Int, stereo: Boolean, hitAgeS: Float) {
        val c = cap.coerceIn(0, 9)
        if (stereo != stereoWanted) { stereoWanted = stereo; if (!stereo) mono = false else { decided = false; entryNs = nowNs - DECIDE_NS + 500_000_000L } }
        if (qualityMode != mode) { mode = qualityMode; over = 0; under = 0; lastChangeNs = nowNs }
        ema += (trueMs - ema) * 0.15f
        rawEma += (rawMs - rawEma) * 0.12f
        fedSinceEntry++
        // peak-hold τ ≈ 6 s: hits come every ~10 s in play, so the rung settles where the flash fits too
        peak = maxOf(trueMs, peak - (peak - ema) * 0.0025f)
        if (fedSinceEntry == WARM_FRAMES) peak = ema          // forget the cold-start pairs (no reprojection depth yet)
        if (mode == MODE_HIGH) { rung = c; mono = false; remember(); return }   // fixed: the scene cap, no controller
        // "over" is judged on the clock-NORMALISED time: at 60 Hz presentation the governor parks the raw time near
        // the period at every rung (~13–15 ms at its lowest clock), so raw > hi says nothing; missed vsyncs do
        over = if (ema > HI) over + 1 else 0
        windowFrames++; if (missedVsync) windowMisses++
        val burst = windowMisses >= 4
        if (windowFrames >= 45) { windowFrames = 0; windowMisses = 0 }
        if (rung > c) { rung = c; lastChangeNs = nowNs }
        val sinceEntry = nowNs - entryNs
        val settledNow = sinceEntry > SETTLE_NS && fedSinceEntry >= SETTLE_FED_FRAMES
        // ---- once per entry: stereo or mono for this visit (after 3 s AND 90 clean measured frames) ----
        if (!decided && sinceEntry > DECIDE_NS && fedSinceEntry >= DECIDE_FRAMES) { decided = true; decide(c, nowNs) }
        // ---- the one recovery: a mono room whose PEAK cost predicts stereo one rung lower (≥ 3) ≤ budget for 5 s, ≥ 10 s after entry ----
        if (decided && mono && stereoWanted && !stereoRecovered && !is2D && sinceEntry > 10_000_000_000L) {
            val tr = maxOf(STEREO_FLOOR, rung - 1)
            val fits = tr <= c && predict(rung, tr, true, false) <= BUDGET
            recoverUnder = if (fits) recoverUnder + 1 else 0
            if (recoverUnder >= 300 && hitAgeS * 1e9f > HIT_GUARD_NS) {
                val p = predict(rung, tr, true, false)
                stereoRecovered = true; mono = false; rung = tr; lastChangeNs = nowNs; lastUpNs = nowNs; under = 0; over = 0
                if (settledNow) changesAfterSettle++
                Log.i(Pipeline.TAG, "stereo recovered at rung $rung (peak ${"%.1f".format(peak)} ms/vsync at mono rung ${rung + 1} predicted ${"%.1f".format(p)} ≤ $BUDGET for 5 s; final for this visit)")
            }
        }
        // a rung that has held for 15 s has earned back the short climb dwell
        if (nowNs - lastDownNs > 15_000_000_000L) upDwellNs = 1_500_000_000L
        val floor = if (stereoWanted && !mono && !is2D) STEREO_FLOOR else 0
        val hitOk = hitAgeS * 1e9f > HIT_GUARD_NS
        val gapDown = if (settledNow) CHANGE_GAP_NS else 600_000_000L
        val gapUp = if (settledNow) maxOf(CLIMB_GAP_SETTLED_NS, upDwellNs) else upDwellNs
        val canClimb = rung < c && predict(rung, rung + 1) <= BUDGET
        under = if (canClimb) under + 1 else 0
        when {
            (over >= 3 || burst) && rung > floor && hitOk && nowNs - lastChangeNs > gapDown && (burst || nowNs - lastDownNs > 600_000_000L) -> {
                val why = if (burst) "misses $windowMisses/$windowFrames" else "true ema ${"%.1f".format(ema)} ms > $HI (raw ${"%.1f".format(rawEma)})"
                lastDownNs = nowNs
                rung--
                if (nowNs - lastUpNs < 3_000_000_000L) upDwellNs = minOf(upDwellNs * 2, 12_000_000_000L)
                if (settledNow) changesAfterSettle++
                Log.i(Pipeline.TAG, "rung ↓ $rung${if (mono) " mono" else ""} (${slices}-slice; $why; true ema ${"%.1f".format(ema)} peak ${"%.1f".format(peak)} ms/vsync, up dwell ${upDwellNs / 1_000_000_000L} s${if (settledNow) ", settled" else ""})")
                lastChangeNs = nowNs; under = 0; over = 0; windowFrames = 0; windowMisses = 0
            }
            under >= 45 && hitOk && nowNs - lastChangeNs > gapUp && rung < c -> {
                rung++; lastChangeNs = nowNs; lastUpNs = nowNs; over = 0; under = 0; windowFrames = 0; windowMisses = 0
                if (settledNow) changesAfterSettle++
                Log.i(Pipeline.TAG, "rung ↑ $rung${if (mono) " mono" else ""} (${slices}-slice; true ema ${"%.1f".format(ema)} peak ${"%.1f".format(peak)} ms/vsync, predicted ${"%.1f".format(predict(rung - 1, rung))} ≤ $BUDGET${if (settledNow) ", settled" else ""})")
            }
        }
        remember()
    }

    /**
     * The once-per-entry stereo/mono decision from the measured cost (CORE_API §10.3), judged on the current
     * EMA (the entry peak still carries the settle-phase rungs). Stereo wins whenever it FITS (≤ HI) at rung ≥ 3
     * and is not more than one rung below the best mono rung; a room that cannot hold stereo at rung 3 at all
     * renders mono for the visit.
     */
    private fun decide(c: Int, nowNs: Long) {
        if (is2D || !stereoWanted) { mono = false; return }
        var sr = -1; for (r in c downTo STEREO_FLOOR) if (predictEma(rung, r, mono, false) <= BUDGET) { sr = r; break }
        val stereoFits = sr >= 0 || predictEma(rung, STEREO_FLOOR, mono, false) <= HI
        if (sr < 0 && stereoFits) sr = STEREO_FLOOR
        var mr = -1; for (r in c downTo 0) if (predictEma(rung, r, mono, true) <= BUDGET) { mr = r; break }
        val wasMono = mono; val was = rung
        when {
            stereoFits && sr >= mr - 1 -> { mono = false; rung = sr }   // stereo one rung lower beats mono higher
            mr >= 0 -> { mono = true; rung = mr }
            else -> { mono = true; rung = minOf(rung, 3) }               // nothing fits yet: mono, let the down rule settle it
        }
        if (mono != wasMono || rung != was) lastChangeNs = nowNs
        Log.i(Pipeline.TAG, "room decision: ${if (mono) "mono" else "stereo"} at rung $rung (stereo fits at ${if (sr >= 0) "rung $sr" else "no rung ≥ $STEREO_FLOOR"}, mono at ${if (mr >= 0) "rung $mr" else "none"}; ema ${"%.1f".format(ema)} peak ${"%.1f".format(peak)} ms/vsync at ${if (wasMono) "mono" else "stereo"} rung $was, ${slices}-slice)")
    }

    private fun remember() { val i = (slot - 1).coerceIn(0, 3); memRung[i] = rung; memMono[i] = mono }

    /**
     * A frame stall of `ns` (a shader compile, a resume — any period > 100 ms, during which the controller is not fed)
     * shifts every wall-clock reference of the controller so the settle window, the change gaps and the climb dwell
     * measure RUNNING time (fixer 2026-09-03: on a fresh `install -r` launch the four lazy compiles consumed the whole
     * 6 s settle window before a single frame was measured, and the Tidepool then climbed 5 → 6 → 7 → 8 at the settled
     * 20 s climb gap instead of within seconds).
     */
    fun stall(ns: Long) { if (ns <= 0L) return; entryNs += ns; lastChangeNs += ns; lastDownNs += ns; lastUpNs += ns }

    /** Scene switch: fixed slices for the room type, the slot's settled (rung, mono) if known, else stereo at min(5, cap). */
    fun onSceneSwitch(newSlot: Int, cap: Int, twoD: Boolean, nowNs: Long) {
        slot = newSlot; is2D = twoD
        val i = (slot - 1).coerceIn(0, 3)
        val c = cap.coerceIn(0, 9)
        slices = if (twoD) 1 else 2
        when (mode) {
            MODE_HIGH -> { rung = c; mono = false }
            else -> {
                if (memRung[i] >= 0) { rung = minOf(memRung[i], c); mono = memMono[i] && !twoD }
                else { rung = minOf(5, c); mono = false }
            }
        }
        if (!mono && !twoD && stereoWanted && rung < STEREO_FLOOR) rung = minOf(STEREO_FLOOR, c)
        decided = false; entryNs = nowNs; changesAfterSettle = 0; fedSinceEntry = 0; stereoRecovered = false; recoverUnder = 0
        upDwellNs = 1_500_000_000L; over = 0; under = 0; windowFrames = 0; windowMisses = 0
        lastChangeNs = nowNs; lastDownNs = nowNs
        rawEma = 10f; peak = ema
        Log.i(Pipeline.TAG, "room entry slot $slot: ${slices}-slice (${if (slices == 2) "30 fps scene + timewarp" else "60 fps"}), rung $rung${if (mono) " mono" else ""}${if (memRung[i] >= 0) " (remembered)" else ""}, cap $c")
    }

    val pacingName: String get() = if (slices == 2) "30" else "60"
}

/**
 * The rendering pipeline (DESIGN §7): scene FBO (RGBA16F + R16F depth, two eye rects, adaptive rungs)
 * → bloom (half res) → echo compose (ping-pong, world-locked) → [timewarp] → post per eye to the
 * 1280×480 screen → HUD composite. Stereo parallax per DESIGN §1.4 (mono renders one rect and blits it
 * to both eyes).
 *
 * TIMEWARP (CORE_API §10.1): a 3D room renders its scene PAIR (both eye rects) in two slices — the
 * bottom rows on one vsync, the top rows on the next — so each vsync's GPU work fits one 16.7 ms
 * period. The post pass runs EVERY vsync and reads the latest COMPLETE pair through a rotation-only
 * reprojection from the pair's render-time camera basis to the newest head basis (the same math as
 * `reprojectPrev`): head rotation is presented at 60 Hz while the scene, toys and translation update at
 * 30 Hz. The bloom is warped the same way; the HUD (screen-locked) composites at 60 Hz untouched.
 */
class Pipeline(private val am: AssetManager) {
    companion object {
        const val TAG = "TapFractal"
        const val EYE_W = 640; const val EYE_H = 480
        const val MAX_SCALE = 0.75f
        val RUNG_SCALE = floatArrayOf(0.30f, 0.35f, 0.40f, 0.45f, 0.50f, 0.50f, 0.55f, 0.62f, 0.70f, 0.75f)
        val RUNG_QUALITY = floatArrayOf(0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 1f, 1f, 1f, 1f, 1f)
        const val PROBE_W = 32; const val PROBE_H = 24
        /** APL flash guard (CORE_API §10.4): attack above this mean luma, never below EXPOSURE_MIN, release τ APL_RELEASE_S. */
        const val APL_ATTACK = 0.30f
        const val EXPOSURE_MIN = 0.55f
        const val APL_RELEASE_S = 0.6f

        /** Rotation-only timewarp: resamples one eye rect of `uSrc` through render-rot → present-rot. Same layout in and out. */
        private const val WARP_FRAG = """#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform vec2 uSrcSize;
uniform vec2 uEyeOrigin, uEyeRes;
uniform float uUvShift, uFov;
uniform mat3 uNewRot;        // present-time camera basis (columns right/up/forward)
uniform mat3 uRenderRotT;    // transpose of the pair's render-time basis
out vec4 fragColor;
void main() {
    vec2 g = ((gl_FragCoord.xy - uEyeOrigin) - 0.5 * uEyeRes) / uEyeRes.y;      // geometric uv of the output pixel
    vec3 dw = uNewRot * vec3((g + vec2(uUvShift, 0.0)) * uFov, 1.0);            // its world ray now
    vec3 dp = uRenderRotT * dw;                                                  // in the render-time frame
    vec2 gr = (dp.z > 0.05) ? dp.xy / (dp.z * uFov) - vec2(uUvShift, 0.0) : g;
    vec2 px = uEyeOrigin + 0.5 * uEyeRes + gr * uEyeRes.y;
    // the strip a fast turn uncovers fades to black over ~3 px: on the waveguide black is transparent (the room
    // shows through), whereas a smeared edge column would be a visible streak
    vec2 lo = uEyeOrigin + 0.5, hi = uEyeOrigin + uEyeRes - 0.5;
    vec2 e = min(px - lo, hi - px);
    float inside = clamp(min(e.x, e.y) / 3.0 + 1.0, 0.0, 1.0);
    px = clamp(px, lo, hi);
    fragColor = vec4(texture(uSrc, px / uSrcSize).rgb * inside, 1.0);
}
"""
    }

    val fboW = (EYE_W * MAX_SCALE).toInt() * 2   // 960
    val fboH = (EYE_H * MAX_SCALE).toInt()       // 360
    private val bloomW = fboW / 2; private val bloomH = fboH / 2

    val rungs = RungController()
    var screenW = 1280; var screenH = 480

    private val sceneFbo = IntArray(2); private val sceneColor = IntArray(2); private val sceneDepth = IntArray(2)
    private var sceneIdx = 0
    private val echoFbo = IntArray(2); private val echoTex = IntArray(2); private var echoIdx = 0
    private val bloomFbo = IntArray(2); private val bloomTex = IntArray(2)
    private val warpFbo = IntArray(1); private val warpTex = IntArray(1)
    private val warpBloomFbo = IntArray(1); private val warpBloomTex = IntArray(1)
    var hasFloat = false; private set
    var hasDepth = false; private set

    // ---- APL probe (DESIGN §7.5 MAY): the post pass re-rendered at PROBE_W×PROBE_H into a tiny RGBA8
    // FBO for the left eye, read back through a ping-pong PBO one frame late (no stall). The mean
    // sRGB-decoded luma drives a FLASH GUARD that only ever darkens: fast attack while the mean is above
    // APL_ATTACK, floor EXPOSURE_MIN, release back to 1.0 with τ APL_RELEASE_S. There is no steady-state
    // target any more — a room's authored brightness is what the player sees. ----
    private val probeFbo = IntArray(1); private val probeTex = IntArray(1)
    private val pbo = IntArray(2); private var pboIdx = 0; private var pboFrames = 0
    private var probeOk = false
    private val lumaLut = FloatArray(256) { i -> val c = i / 255f; if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() }
    /** Mean linear luma of the left eye as presented (HUD excluded), one frame late; NaN until measured. */
    var aplMean = Float.NaN; private set
    /** Servo exposure multiplier ≤ 1 applied on top of rs.exposure. */
    var aplExposure = 1f; private set
    var aplEnabled = true

    private var vertSrc = ""
    /** The fullscreen-triangle vertex shader source (for the GpuTimer's calibration program). */
    val vertSource: String get() = vertSrc
    private var common = ""; private var footer = ""
    private val scenePrograms = arrayOfNulls<ShaderProgram>(4)
    private var errorProg: ShaderProgram? = null
    private var echoProg: ShaderProgram? = null
    private var bloomProg: ShaderProgram? = null
    private var postProg: ShaderProgram? = null
    private var hudProg: ShaderProgram? = null
    private var warpProg: ShaderProgram? = null

    // previous-PAIR per-eye state for the depth reprojection (startT / echo)
    private val prevRotT = Array(2) { FloatArray(9) }
    private val prevPos = Array(2) { Vec3() }
    private val prevOrigin = Array(2) { FloatArray(2) }
    private val prevRes = Array(2) { floatArrayOf(320f, 240f) }
    private var prevValid = false

    // the pair in progress (latched at slice 0 so both slices share one layout)
    private var pairRung = 5; private var pairEyeW = 320; private var pairEyeH = 240; private var pairSep = 0f; private var pairMono = true; private var pairQuality = 1f
    // the latest COMPLETE pair = what the present pass shows
    private var presHdr = 0; private var presBloom = 0
    private var presEyeW = 320; private var presEyeH = 240; private var presSep = 0f; private var presMono = true
    private val presRotT = FloatArray(9); private val presRot = FloatArray(9)
    private var presValid = false
    /** True when the last present pass went through the timewarp (2-slice rooms). */
    var timewarpActive = false; private set
    /** Presents (post passes) and scene pairs completed — the 60/30 evidence in the log. */
    var presents = 0L; private set
    var pairs = 0L; private set
    /** Largest head rotation (degrees) the warp had to bridge since the last log. */
    var warpMaxDeg = 0f
    /** Debug flags (adb: `settings put global tapfractal_debug "noecho,nobloom,rung=3"`), read by the renderer. */
    var dbgNoScene = false; var dbgNoEcho = false; var dbgNoBloom = false; var dbgNoPost = false; var dbgNoHud = false
    var dbgRung = -1; var dbgMono = false; var dbgNoToys = false; var dbgNoWarp = false

    fun applyDebug(flags: String) {
        val f = flags.split(',').map { it.trim() }
        dbgNoScene = "noscene" in f; dbgNoEcho = "noecho" in f; dbgNoBloom = "nobloom" in f; dbgNoPost = "nopost" in f
        dbgNoHud = "nohud" in f; dbgMono = "mono" in f; dbgNoToys = "notoys" in f; dbgNoWarp = "nowarp" in f
        aplEnabled = "noapl" !in f
        dbgRung = f.firstOrNull { it.startsWith("rung=") }?.substringAfter('=')?.toIntOrNull() ?: -1
    }

    private val clearBlack = floatArrayOf(0f, 0f, 0f, 1f)
    private val clearZero = floatArrayOf(0f, 0f, 0f, 0f)
    private val tmpRope = FloatArray(48)
    private val tmpTargets = FloatArray(24)
    private val tmpRotT = FloatArray(9)
    var frameCount = 0L; private set
    var lastEyeW = 320; private set
    var lastEyeH = 240; private set

    // ------------------------------------------------------------------ init

    /**
     * Called from onSurfaceCreated — also after an EGL context LOSS, when every GL name of the previous
     * context is gone. Handles from the old context are dropped without release(): the new context
     * hands out the same ids in the same order, so a glDeleteProgram on a stale handle would delete a
     * freshly compiled program of the new context.
     */
    fun init(scenes: List<Scene>) {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        hasFloat = ext.contains("GL_EXT_color_buffer_float") || ext.contains("GL_EXT_color_buffer_half_float")
        Log.i(TAG, "GL ${GLES30.glGetString(GLES30.GL_VERSION)} / ${GLES30.glGetString(GLES30.GL_RENDERER)} float-renderable=$hasFloat")
        scenePrograms.fill(null); errorProg = null
        hudProg = null; postProg = null; bloomProg = null; echoProg = null; warpProg = null
        sceneIdx = 0; echoIdx = 0; pboIdx = 0; pboFrames = 0; aplMean = Float.NaN; aplExposure = 1f
        presValid = false; prevValid = false; timewarpActive = false
        createTargets()
        vertSrc = ShaderProgram.readAsset(am, "shaders/fullscreen.vert") ?: ""
        common = ShaderProgram.readAsset(am, "shaders/common.glsl") ?: ""
        footer = ShaderProgram.readAsset(am, "shaders/footer.glsl") ?: ""
        // small core programs first, then the scene programs lazily (one per frame, current scene first)
        // so the first frame (HUD + notice) appears in well under a second instead of after ~6 s of compiles
        hudProg = ShaderProgram.build(vertSrc, ShaderProgram.readAsset(am, "shaders/hud.frag") ?: "", "hud.frag")
        postProg = ShaderProgram.build(vertSrc, ShaderProgram.readAsset(am, "shaders/post.frag") ?: "", "post.frag")
        bloomProg = ShaderProgram.build(vertSrc, ShaderProgram.readAsset(am, "shaders/bloom.frag") ?: "", "bloom.frag")
        echoProg = ShaderProgram.build(vertSrc, common + "\n#line 1 1\n" + (ShaderProgram.readAsset(am, "shaders/echo.frag") ?: "") + "\n#line 1 2\n" + footer, "echo.frag")
        warpProg = ShaderProgram.build(vertSrc, WARP_FRAG, "timewarp")
        if (warpProg == null) Log.e(TAG, "timewarp program failed — presenting without reprojection")
        pending.clear(); pending.addAll(scenes); pendingError = true
        sceneTried.fill(false)
        checkGl("init")
    }

    private val pending = ArrayList<Scene>()
    private var pendingError = false
    private val sceneTried = BooleanArray(4)
    /** Set when render() had to compile a scene program on first draw (the frame's GPU time is then meaningless). */
    var compiledInRender = false

    /** Compile at most one pending program (called once per frame by the renderer). `first` is the current scene. */
    fun compilePending(first: Scene?): Boolean {
        if (first != null && pending.remove(first)) { loadScene(first); return true }
        if (pending.isNotEmpty()) { loadScene(pending.removeAt(0)); return true }
        if (pendingError) {
            pendingError = false
            errorProg = ShaderProgram.build(vertSrc, common + "\n#line 1 1\n" + ShaderProgram.ERROR_SCENE + "\n#line 1 2\n" + footer, "error-scene")
            return true
        }
        return false
    }

    fun loadScene(s: Scene) {
        val idx = (s.slot - 1).coerceIn(0, 3)
        scenePrograms[idx]?.release()
        scenePrograms[idx] = ShaderProgram.scene(am, s.fragAsset, common, footer, vertSrc)
        sceneTried[idx] = true
        if (scenePrograms[idx] == null) Log.e(TAG, "scene ${s.slot} (${s.fragAsset}) failed — using the built-in error scene")
    }

    fun sceneOk(slot: Int) = scenePrograms[(slot - 1).coerceIn(0, 3)] != null
    fun sceneReady(slot: Int) = sceneTried[(slot - 1).coerceIn(0, 3)]

    private fun tex2D(internal: Int, w: Int, h: Int, filter: Int): Int {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internal, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun fboComplete(): Boolean = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE

    private fun colorFbo(fbo: IntArray, tex: IntArray, fmt: Int, w: Int, h: Int, label: String) {
        GLES30.glGenFramebuffers(1, fbo, 0)
        tex[0] = tex2D(fmt, w, h, GLES30.GL_LINEAR)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0)
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!fboComplete()) Log.e(TAG, "$label FBO incomplete")
    }

    private fun createTargets() {
        val colorFmt = if (hasFloat) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        // scene FBOs (ping-pong so last pair's depth can be read while this pair's is written)
        GLES30.glGenFramebuffers(2, sceneFbo, 0)
        hasDepth = hasFloat
        for (i in 0 until 2) {
            sceneColor[i] = tex2D(colorFmt, fboW, fboH, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, sceneColor[i], 0)
            if (hasDepth) {
                sceneDepth[i] = tex2D(GLES30.GL_R16F, fboW, fboH, GLES30.GL_NEAREST)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1, GLES30.GL_TEXTURE_2D, sceneDepth[i], 0)
                GLES30.glDrawBuffers(2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1), 0)
                if (!fboComplete()) {
                    Log.w(TAG, "R16F depth attachment not renderable — reprojection disabled")
                    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1, GLES30.GL_TEXTURE_2D, 0, 0)
                    GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0)
                    hasDepth = false
                }
            }
            if (!fboComplete()) {
                Log.e(TAG, "scene FBO incomplete with format $colorFmt — falling back to RGBA8")
                GLES30.glDeleteTextures(1, sceneColor, i)
                sceneColor[i] = tex2D(GLES30.GL_RGBA8, fboW, fboH, GLES30.GL_LINEAR)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, sceneColor[i], 0)
                hasFloat = false
            }
        }
        val fmt2 = if (hasFloat) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        GLES30.glGenFramebuffers(2, echoFbo, 0)
        for (i in 0 until 2) {
            echoTex[i] = tex2D(fmt2, fboW, fboH, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, echoFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, echoTex[i], 0)
            GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0)
            GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (!fboComplete()) Log.e(TAG, "echo FBO $i incomplete")
        }
        GLES30.glGenFramebuffers(2, bloomFbo, 0)
        for (i in 0 until 2) {
            bloomTex[i] = tex2D(fmt2, bloomW, bloomH, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, bloomTex[i], 0)
            GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0)
            if (!fboComplete()) Log.e(TAG, "bloom FBO $i incomplete")
        }
        // timewarp targets (same layout as the scene FBO / bloom)
        colorFbo(warpFbo, warpTex, fmt2, fboW, fboH, "timewarp")
        colorFbo(warpBloomFbo, warpBloomTex, fmt2, bloomW, bloomH, "timewarp bloom")
        // APL probe FBO + PBOs
        GLES30.glGenFramebuffers(1, probeFbo, 0)
        probeTex[0] = tex2D(GLES30.GL_RGBA8, PROBE_W, PROBE_H, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, probeFbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, probeTex[0], 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0)
        probeOk = fboComplete()
        if (!probeOk) Log.e(TAG, "APL probe FBO incomplete — exposure servo disabled")
        GLES30.glGenBuffers(2, pbo, 0)
        for (i in 0 until 2) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[i])
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, PROBE_W * PROBE_H * 4, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        Log.i(TAG, "targets: scene ${fboW}x$fboH ${if (hasFloat) "RGBA16F" else "RGBA8"} depth=$hasDepth bloom ${bloomW}x$bloomH warp ${fboW}x$fboH probe ${PROBE_W}x$PROBE_H ok=$probeOk")
    }

    fun release() {
        for (p in scenePrograms) p?.release()
        errorProg?.release(); echoProg?.release(); bloomProg?.release(); postProg?.release(); hudProg?.release(); warpProg?.release()
        GLES30.glDeleteFramebuffers(2, sceneFbo, 0); GLES30.glDeleteFramebuffers(2, echoFbo, 0); GLES30.glDeleteFramebuffers(2, bloomFbo, 0)
        GLES30.glDeleteTextures(2, sceneColor, 0); GLES30.glDeleteTextures(2, sceneDepth, 0)
        GLES30.glDeleteTextures(2, echoTex, 0); GLES30.glDeleteTextures(2, bloomTex, 0)
        GLES30.glDeleteFramebuffers(1, warpFbo, 0); GLES30.glDeleteTextures(1, warpTex, 0)
        GLES30.glDeleteFramebuffers(1, warpBloomFbo, 0); GLES30.glDeleteTextures(1, warpBloomTex, 0)
        GLES30.glDeleteFramebuffers(1, probeFbo, 0); GLES30.glDeleteTextures(1, probeTex, 0); GLES30.glDeleteBuffers(2, pbo, 0)
    }

    /**
     * Reads last frame's probe (PBO map, no stall), updates the flash guard, then renders this frame's
     * probe with the post program and queues its readback. Called with the post program's per-frame
     * uniforms already set; leaves the default framebuffer bound.
     */
    private fun aplProbe(pp: ShaderProgram, rs: RenderState, dt: Float, eyeW: Int, eyeH: Int, sep: Float) {
        if (!probeOk) return
        // 1. consume the PBO written PBO_LAG frames ago
        if (pboFrames >= 2) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[pboIdx])
            val buf = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, PROBE_W * PROBE_H * 4, GLES30.GL_MAP_READ_BIT) as? java.nio.ByteBuffer
            if (buf != null) {
                var sum = 0f
                val n = PROBE_W * PROBE_H
                for (i in 0 until n) {
                    val r = buf.get(i * 4).toInt() and 0xFF; val g = buf.get(i * 4 + 1).toInt() and 0xFF; val b = buf.get(i * 4 + 2).toInt() and 0xFF
                    sum += 0.2126f * lumaLut[r] + 0.7152f * lumaLut[g] + 0.0722f * lumaLut[b]
                }
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                aplMean = sum / n
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        }
        // 2. flash guard (only ever darkens; 1.0 = the scene as authored; no steady-state pull-down)
        if (!aplMean.isNaN() && aplEnabled) {
            val m = aplMean
            if (m > APL_ATTACK) aplExposure *= (APL_ATTACK / m).coerceIn(0.7f, 1f)          // fast attack on a flash (≤ 2 frames late)
            else aplExposure += (1f - aplExposure) * (1f - kotlin.math.exp(-dt / APL_RELEASE_S))   // release back to authored
            aplExposure = aplExposure.coerceIn(EXPOSURE_MIN, 1f)
        } else if (!aplEnabled) aplExposure = 1f
        // 3. render this frame's probe (left eye, same uniforms, tiny viewport) and queue the readback
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, probeFbo[0])
        GLES30.glViewport(0, 0, PROBE_W, PROBE_H)
        pp.set2f("uScreenOrigin", 0f, 0f); pp.set2f("uScreenRes", PROBE_W.toFloat(), PROBE_H.toFloat())
        pp.set2f("uEyeOrigin", 0f, 0f); pp.set2f("uEyeRes", eyeW.toFloat(), eyeH.toFloat())
        pp.set1f("uUvShift", uvShift(0, sep, rs))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        val next = 1 - pboIdx
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[next])
        GLES30.glReadPixels(0, 0, PROBE_W, PROBE_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        pboIdx = next; pboFrames++
    }

    // ------------------------------------------------------------------ frame

    fun currentRung(rs: RenderState): Int {
        if (dbgRung >= 0) return dbgRung.coerceIn(0, 9)
        val r = if (rs.forceRung >= 0) minOf(rungs.rung, rs.forceRung) else rungs.rung
        return r.coerceIn(0, 9)
    }

    /**
     * One vsync. `slice`/`slices` = which part of the scene pair to render this vsync (the renderer
     * runs the game only at slice 0, so `rs` is identical for every slice of a pair); `presentRot` =
     * the newest head basis for the timewarp (== rs.camRot when the game ran this vsync).
     */
    fun render(rs: RenderState, hudTex: Int, presentRot: Mat3, slice: Int, slices: Int, dtVsync: Float) {
        frameCount++
        // ---- latch the pair's layout at slice 0 ----
        if (slice == 0) {
            pairRung = currentRung(rs)
            val scale = RUNG_SCALE[pairRung]
            pairEyeW = floor(EYE_W * scale).toInt(); pairEyeH = floor(EYE_H * scale).toInt()
            // rung ≤ 2 → mono (sub-pixel difference), DESIGN §1.4; the controller's once-per-room decision (CORE_API §10.3)
            pairSep = if (pairRung <= 2 || dbgMono || (rungs.mono && dbgRung < 0)) 0f else rs.eyeSep
            pairMono = pairSep == 0f
            pairQuality = RUNG_QUALITY[pairRung]
        }
        val rung = pairRung; val eyeW = pairEyeW; val eyeH = pairEyeH; val sep = pairSep; val mono = pairMono; val quality = pairQuality
        lastEyeW = eyeW; lastEyeH = eyeH
        val eyes = if (mono) 1 else 2

        // ---- 1. scene pass: this vsync's slice of the pair (rows [y0, y1) of both eye rects) ----
        val cur = sceneIdx; val prev = 1 - sceneIdx
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo[cur])
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glViewport(0, 0, fboW, fboH)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        if (slice == 0) {
            // clear only the rects in use (keeps the tiler's dirty region — and its store — small)
            GLES30.glScissor(0, 0, eyes * eyeW, eyeH)
            GLES30.glClearBufferfv(GLES30.GL_COLOR, 0, clearBlack, 0)
            if (hasDepth) GLES30.glClearBufferfv(GLES30.GL_COLOR, 1, clearZero, 0)
        }
        val y0 = eyeH * slice / slices; val y1 = eyeH * (slice + 1) / slices
        GLES30.glScissor(0, y0, eyes * eyeW, y1 - y0)

        val draws = if (dbgNoScene) emptyList() else if (rs.crossfading && rs.outgoing.scene != null) listOf(rs.outgoing, rs.current) else listOf(rs.current)
        for ((di, draw) in draws.withIndex()) {
            val scene = draw.scene ?: continue
            val si = (scene.slot - 1).coerceIn(0, 3)
            if (!sceneTried[si]) { pending.remove(scene); loadScene(scene); compiledInRender = true }
            val prog = scenePrograms[si] ?: errorProg ?: continue
            val isOutgoing = rs.crossfading && di == 0 && draws.size == 2
            if (isOutgoing) {
                GLES30.glDrawBuffers(2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_NONE), 0)
                GLES30.glDisable(GLES30.GL_BLEND)
            } else {
                if (hasDepth) GLES30.glDrawBuffers(2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1), 0)
                else GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0)
                if (rs.crossfading && draws.size == 2) { GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE) }
                else GLES30.glDisable(GLES30.GL_BLEND)
            }
            prog.use()
            for (eye in 0 until eyes) {
                GLES30.glViewport(eye * eyeW, 0, eyeW, eyeH)
                uploadCommon(prog, rs, draw, eye, eyeW, eyeH, sep, quality, sceneDepth[prev])
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            }
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)

        // ---- the pair completes on its last slice: bloom, echo, reprojection state, present descriptor ----
        if (slice == slices - 1) {
            // 2. bloom (half res, from the scene colour)
            if (!dbgNoBloom) bloomProg?.let { bp ->
                bp.use()
                GLES30.glViewport(0, 0, bloomW, bloomH)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[0])
                bp.setTex("uSrc", 0, sceneColor[cur])
                bp.set2f("uDir", 1f, 0f); bp.set1f("uStep", 1.5f); bp.set1f("uThreshold", 0.55f)
                bp.set2f("uEyeRect", eyeW.toFloat(), eyeH.toFloat()); bp.set1i("uMono", if (mono) 1 else 0)
                bp.set2f("uSrcSize", fboW.toFloat(), fboH.toFloat()); bp.set2f("uDstSize", bloomW.toFloat(), bloomH.toFloat())
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[1])
                bp.setTex("uSrc", 0, bloomTex[0])
                bp.set2f("uDir", 0f, 1f); bp.set1f("uStep", 1.5f); bp.set1f("uThreshold", 0f)
                bp.set2f("uEyeRect", eyeW * 0.5f, eyeH * 0.5f)
                bp.set2f("uSrcSize", bloomW.toFloat(), bloomH.toFloat()); bp.set2f("uDstSize", bloomW.toFloat(), bloomH.toFloat())
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            }
            // 3. echo compose (ping-pong; the hdr buffer for post)
            val echoNext = 1 - echoIdx
            var hdrTex = sceneColor[cur]
            if (!dbgNoEcho) echoProg?.let { ep ->
                ep.use()
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, echoFbo[echoNext])
                val mix = if (prevValid) rs.echoMix else 0f
                for (eye in 0 until eyes) {
                    GLES30.glViewport(eye * eyeW, 0, eyeW, eyeH)
                    uploadCommon(ep, rs, rs.current, eye, eyeW, eyeH, sep, quality, sceneDepth[prev])
                    ep.set1f("uFade", 1f)
                    ep.setTex("uSceneTex", 1, sceneColor[cur])
                    ep.setTex("uPrevEcho", 2, echoTex[echoIdx])
                    ep.set1f("uEchoMix", mix); ep.set1f("uEchoZoom", rs.echoZoom); ep.set1f("uEchoRot", rs.echoRot)
                    ep.set2f("uEchoCentre", rs.echoCentre.x, rs.echoCentre.y)
                    ep.set3f("uEchoTint", rs.echoTint.x, rs.echoTint.y, rs.echoTint.z)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                }
                hdrTex = echoTex[echoNext]
                echoIdx = echoNext
            }
            // remember this pair for the depth / echo reprojection of the next one
            for (eye in 0 until 2) {
                val src = if (mono) 0 else eye
                System.arraycopy(rs.camRot.transposed().m, 0, prevRotT[eye], 0, 9)
                prevPos[eye].set(eyeCamPos(rs.current, rs, src, sep))
                prevOrigin[eye][0] = (src * eyeW).toFloat(); prevOrigin[eye][1] = 0f
                prevRes[eye][0] = eyeW.toFloat(); prevRes[eye][1] = eyeH.toFloat()
            }
            prevValid = true
            sceneIdx = 1 - sceneIdx
            // the present descriptor: what every vsync shows until the next pair completes
            presHdr = hdrTex; presBloom = bloomTex[1]
            presEyeW = eyeW; presEyeH = eyeH; presSep = sep; presMono = mono
            System.arraycopy(rs.camRot.transposed().m, 0, presRotT, 0, 9)
            System.arraycopy(rs.camRot.m, 0, presRot, 0, 9)
            presValid = true
            pairs++
        }

        // ---- 4. present: [timewarp] + post per eye to the screen ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        val scrEyeW = screenW / 2
        if (dbgNoPost || !presValid) {
            GLES30.glViewport(0, 0, screenW, screenH); GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            timewarpActive = false
        } else {
            val pEyeW = presEyeW; val pEyeH = presEyeH; val pSep = presSep; val pMono = presMono
            val pEyes = if (pMono) 1 else 2
            var hdrForPost = presHdr; var bloomForPost = presBloom
            // rotation-only timewarp when the pair was rendered with an older head basis than the one we present with
            val delta = rotDeltaDeg(presRot, presentRot.m)
            if (delta > warpMaxDeg) warpMaxDeg = delta
            val wp = warpProg
            val warp = wp != null && !dbgNoWarp && (slices > 1 || delta > 0.02f)
            if (warp && wp != null) {
                wp.use()
                wp.setMat3("uNewRot", presentRot.m); wp.setMat3("uRenderRotT", presRotT); wp.set1f("uFov", rs.fov)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, warpFbo[0])
                wp.setTex("uSrc", 0, presHdr); wp.set2f("uSrcSize", fboW.toFloat(), fboH.toFloat())
                for (eye in 0 until pEyes) {
                    GLES30.glViewport(eye * pEyeW, 0, pEyeW, pEyeH)
                    wp.set2f("uEyeOrigin", (eye * pEyeW).toFloat(), 0f); wp.set2f("uEyeRes", pEyeW.toFloat(), pEyeH.toFloat())
                    wp.set1f("uUvShift", uvShift(eye, pSep, rs))
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                }
                if (!dbgNoBloom) {
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, warpBloomFbo[0])
                    wp.setTex("uSrc", 0, presBloom); wp.set2f("uSrcSize", bloomW.toFloat(), bloomH.toFloat())
                    val hw = pEyeW / 2; val hh = (pEyeH + 1) / 2
                    for (eye in 0 until pEyes) {
                        GLES30.glViewport(eye * hw, 0, hw, hh)
                        wp.set2f("uEyeOrigin", eye * pEyeW * 0.5f, 0f); wp.set2f("uEyeRes", pEyeW * 0.5f, pEyeH * 0.5f)
                        wp.set1f("uUvShift", uvShift(eye, pSep, rs))
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                    }
                    bloomForPost = warpBloomTex[0]
                }
                hdrForPost = warpTex[0]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            }
            timewarpActive = warp
            postProg?.let { pp ->
                pp.use()
                pp.setTex("uHdr", 0, hdrForPost); pp.setTex("uBloom", 1, bloomForPost)
                pp.set2f("uFboSize", fboW.toFloat(), fboH.toFloat())
                pp.set1i("uSceneId", ((rs.current.scene?.slot ?: 1) - 1))
                pp.set1f("uEchoMix", rs.echoMix); pp.set1f("uEchoZoom", rs.echoZoom); pp.set1f("uEchoRot", rs.echoRot)
                pp.set2f("uEchoCentre", rs.echoCentre.x, rs.echoCentre.y)
                pp.set3f("uEchoTint", rs.echoTint.x, rs.echoTint.y, rs.echoTint.z)
                val pal = rs.current.scene?.palette
                pp.set3f("uBloomTint", pal?.bloomTint?.x ?: 1f, pal?.bloomTint?.y ?: 1f, pal?.bloomTint?.z ?: 1f)
                pp.set1f("uBloomStrength", pal?.bloomStrength ?: 0.35f)
                pp.set3f("uCoreTint", pal?.coreTint?.x ?: 1f, pal?.coreTint?.y ?: 1f, pal?.coreTint?.z ?: 1f)
                pp.set1f("uAberr", rs.aberr); pp.set1f("uBreath", rs.breath); pp.set1f("uExposure", rs.exposure * aplExposure)
                pp.set1i("uPrism", rs.prism); pp.set1f("uHitPulse", rs.hitPulse)
                pp.set1f("uEnergy", rs.energy); pp.set1f("uBeat", rs.beat)
                for (eye in 0 until 2) {
                    val srcEye = if (pMono) 0 else eye
                    GLES30.glViewport(eye * scrEyeW, 0, scrEyeW, screenH)
                    pp.set2f("uScreenOrigin", (eye * scrEyeW).toFloat(), 0f); pp.set2f("uScreenRes", scrEyeW.toFloat(), screenH.toFloat())
                    pp.set2f("uEyeOrigin", (srcEye * pEyeW).toFloat(), 0f); pp.set2f("uEyeRes", pEyeW.toFloat(), pEyeH.toFloat())
                    pp.set1f("uUvShift", uvShift(eye, pSep, rs))
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                }
                // 4b. APL flash guard probe (left eye, one frame late)
                aplProbe(pp, rs, dtVsync, pEyeW, pEyeH, pSep)
            }
            presents++
        }

        // ---- 5. HUD composite (zero disparity, every vsync) ----
        if (hudTex != 0 && rs.hudAlpha > 0f && !dbgNoHud) hudProg?.let { hp ->
            hp.use()
            GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            hp.setTex("uHud", 0, hudTex); hp.set1f("uHudAlpha", rs.hudAlpha)
            for (eye in 0 until 2) {
                GLES30.glViewport(eye * scrEyeW, 0, scrEyeW, screenH)
                hp.set2f("uScreenOrigin", (eye * scrEyeW).toFloat(), 0f); hp.set2f("uScreenRes", scrEyeW.toFloat(), screenH.toFloat())
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            }
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        if (frameCount % 300L == 1L) checkGl("frame")
    }

    /** Angle (degrees) between two camera bases (column-major 3×3). */
    private fun rotDeltaDeg(a: FloatArray, b: FloatArray): Float {
        // trace(Aᵀ B) = 1 + 2 cos θ
        var tr = 0f
        for (c in 0 until 3) for (r in 0 until 3) tr += a[c * 3 + r] * b[c * 3 + r]
        val cs = ((tr - 1f) * 0.5f).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cs).toDouble()).toFloat()
    }

    // ------------------------------------------------------------------ uniforms

    private fun eyeSign(eye: Int, sep: Float) = if (sep == 0f) 0f else if (eye == 0) -1f else 1f

    /** Per-eye camera position: camCenter ± s·right (− left, + right). 2D rooms: the toy camera sits at the origin of the head. */
    private fun eyeCamPos(draw: SceneDraw, rs: RenderState, eye: Int, sep: Float): Vec3 {
        val s = eyeSign(eye, sep) * sep
        return draw.camCenter + rs.camRot.right * s
    }

    /** uUvShift: + for the left eye, − for the right, so the convergence point lands at geometric uv.x = 0 in both eyes. */
    private fun uvShift(eye: Int, sep: Float, rs: RenderState): Float = -eyeSign(eye, sep) * sep / (rs.convergence * rs.fov)

    /**
     * Uploads every contract uniform (Part A, Part B, core additions) for one scene draw and one eye.
     * Order: Part A → B.1 toys → B.2 events → B.3 navigation → B.4 reprojection → core additions.
     */
    private fun uploadCommon(p: ShaderProgram, rs: RenderState, draw: SceneDraw, eye: Int, eyeW: Int, eyeH: Int,
                             sep: Float, quality: Float, prevDepthTex: Int) {
        val scene = draw.scene
        val is2D = scene?.is2D == true
        val camPosEye = eyeCamPos(draw, rs, eye, sep)
        val shift = uvShift(eye, sep, rs)
        // toy positions are in the CURRENT scene's world; shift them into this draw's world
        val off = draw.camCenter - rs.current.camCenter

        // ---- Part A ----
        p.set2f("uEyeRes", eyeW.toFloat(), eyeH.toFloat())
        p.set2f("uEyeOrigin", (eye * eyeW).toFloat(), 0f)
        p.set1i("uEye", eye)
        p.set1f("uTime", draw.time); p.set1f("uDt", rs.dt)
        if (is2D) p.set3f("uCamPos", draw.cam2D.x, draw.cam2D.y, draw.cam2D.z)
        else p.set3f("uCamPos", camPosEye.x, camPosEye.y, camPosEye.z)
        p.setMat3("uCamRot", rs.camRot.m)
        p.set1f("uUvShift", shift)
        p.set1f("uFov", rs.fov)
        if (is2D) {
            // projected toys: xy = eye-uv (same space as eyeUV()), z = view depth, w = projected radius
            val b = project(rs.sluurp + off, camPosEye, rs, rs.sluurpRadius)
            p.set4f("uSluurp", b[0], b[1], b[2], b[3])
            val a = project(rs.anchor + off, camPosEye, rs, 0f)
            p.set3f("uAnchor", a[0], a[1], a[2])
            for (i in 0 until 12) {
                val r = project(rs.rope[i] + off, camPosEye, rs, 0f)
                tmpRope[i * 4] = r[0]; tmpRope[i * 4 + 1] = r[1]; tmpRope[i * 4 + 2] = r[2]; tmpRope[i * 4 + 3] = 0f
            }
            for (i in 0 until 6) {
                if (rs.targetRadius[i] > 0f) {
                    val t = project(rs.targetPos[i] + off, camPosEye, rs, rs.targetRadius[i])
                    tmpTargets[i * 4] = t[0]; tmpTargets[i * 4 + 1] = t[1]; tmpTargets[i * 4 + 2] = t[2]; tmpTargets[i * 4 + 3] = t[3]
                } else { tmpTargets[i * 4] = 0f; tmpTargets[i * 4 + 1] = 0f; tmpTargets[i * 4 + 2] = 0f; tmpTargets[i * 4 + 3] = 0f }
            }
        } else {
            p.set4f("uSluurp", rs.sluurp.x + off.x, rs.sluurp.y + off.y, rs.sluurp.z + off.z, rs.sluurpRadius)
            p.set3f("uAnchor", rs.anchor.x + off.x, rs.anchor.y + off.y, rs.anchor.z + off.z)
            for (i in 0 until 12) {
                tmpRope[i * 4] = rs.rope[i].x + off.x; tmpRope[i * 4 + 1] = rs.rope[i].y + off.y; tmpRope[i * 4 + 2] = rs.rope[i].z + off.z; tmpRope[i * 4 + 3] = 0f
            }
            for (i in 0 until 6) {
                tmpTargets[i * 4] = rs.targetPos[i].x + off.x; tmpTargets[i * 4 + 1] = rs.targetPos[i].y + off.y
                tmpTargets[i * 4 + 2] = rs.targetPos[i].z + off.z; tmpTargets[i * 4 + 3] = rs.targetRadius[i]
            }
        }
        p.set1f("uSluurpGlow", rs.sluurpGlow)
        p.set4fv("uRope", 12, tmpRope); p.set1i("uRopeN", if (dbgNoToys) 0 else rs.ropeN)
        if (dbgNoToys) { java.util.Arrays.fill(tmpTargets, 0f); p.set4f("uSluurp", 0f, 0f, 0f, 0f) }
        p.set4fv("uTargets", 6, tmpTargets); p.set1fv("uTargetPulse", 6, rs.targetPulse)
        p.set1f("uZoom", draw.zoom)
        p.set1f("uBeat", rs.beat); p.set1f("uEnergy", rs.energy); p.set1f("uBass", rs.bass); p.set1f("uTreble", rs.treble)
        p.set1f("uHue", rs.hue)
        p.set1f("uQuality", quality)
        p.set1f("uFade", draw.fade * (if (rs.menuDim) 0.35f else 1f))

        // ---- Part B.1 toys ----
        p.set4f("uSluurpVel", rs.sluurpVel.x, rs.sluurpVel.y, rs.sluurpVel.z, rs.sluurpSquash)
        p.set3f("uSluurpEye", rs.sluurpEye.x, rs.sluurpEye.y, rs.sluurpEye.z)
        p.set1f("uRopeTension", rs.ropeTension); p.set1f("uRopeFree", rs.ropeFree)
        p.set1fv("uTargetLife", 6, rs.targetLife)
        p.set4f("uToyBounds", rs.toyBounds.x + off.x, rs.toyBounds.y + off.y, rs.toyBounds.z + off.z, rs.toyBoundsR)
        // ---- B.2 events ----
        p.set1f("uHitPulse", rs.hitPulse); p.set1f("uHitFlash", rs.hitFlash)
        p.set1f("uTransition", draw.transition); p.set1f("uAttract", rs.attract); p.set1f("uLookAway", draw.lookAway)
        // ---- B.3 navigation ----
        p.set1f("uSpeed", draw.speed); p.set1f("uTravel", draw.travel)
        p.set3f("uGravity", rs.gravity.x, rs.gravity.y, rs.gravity.z)
        p.set3f("uHeadRate", rs.headRate.x, rs.headRate.y, rs.headRate.z)
        p.set1f("uEyeSep", eyeSign(eye, sep) * sep)
        // ---- B.4 reprojection ----
        p.setTex("uPrevDepth", 0, prevDepthTex)
        val pe = if (sep == 0f) 0 else eye
        if (prevValid) {
            p.setMat3("uPrevCamRotT", prevRotT[pe])
            p.set3f("uPrevCamPos", prevPos[pe].x + off.x, prevPos[pe].y + off.y, prevPos[pe].z + off.z)
            p.set2f("uPrevEyeOrigin", prevOrigin[pe][0], prevOrigin[pe][1]); p.set2f("uPrevEyeRes", prevRes[pe][0], prevRes[pe][1])
        } else {
            System.arraycopy(rs.camRot.transposed().m, 0, tmpRotT, 0, 9)
            p.setMat3("uPrevCamRotT", tmpRotT)
            p.set3f("uPrevCamPos", camPosEye.x, camPosEye.y, camPosEye.z)
            p.set2f("uPrevEyeOrigin", (eye * eyeW).toFloat(), 0f); p.set2f("uPrevEyeRes", eyeW.toFloat(), eyeH.toFloat())
        }
        // ---- core additions ----
        p.set1i("uIs2D", if (is2D) 1 else 0)
        for (g in 0 until 3) {
            val i0 = g * 4; val i1 = minOf(i0 + 4, 11)
            var cx = 0f; var cy = 0f; var cz = 0f; var n = 0
            for (i in i0..i1) { cx += rs.rope[i].x; cy += rs.rope[i].y; cz += rs.rope[i].z; n++ }
            cx /= n; cy /= n; cz /= n
            var r = 0f
            for (i in i0..i1) { val dx = rs.rope[i].x - cx; val dy = rs.rope[i].y - cy; val dz = rs.rope[i].z - cz; r = maxOf(r, kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)) }
            tmpGroups[g * 4] = cx + off.x; tmpGroups[g * 4 + 1] = cy + off.y; tmpGroups[g * 4 + 2] = cz + off.z; tmpGroups[g * 4 + 3] = r + 0.012f + 0.03f
        }
        p.set4fv("uRopeGroups", 3, tmpGroups)
    }
    private val tmpGroups = FloatArray(12)

    private val projTmp = FloatArray(4)
    /** World point → (uv.x, uv.y, depth, projected radius) through this eye's camera (uv includes the convergence shift, like eyeUV()). */
    private fun project(q: Vec3, camPosEye: Vec3, rs: RenderState, radius: Float): FloatArray {
        val d = rs.camRot.mulT(q - camPosEye)
        val z = abs(d.z).coerceAtLeast(1e-3f)
        projTmp[0] = d.x / (z * rs.fov); projTmp[1] = d.y / (z * rs.fov); projTmp[2] = d.z; projTmp[3] = radius / (z * rs.fov)
        return projTmp
    }

    fun checkGl(where: String) {
        var e = GLES30.glGetError()
        while (e != GLES30.GL_NO_ERROR) { Log.e(TAG, "GL error 0x${Integer.toHexString(e)} at $where"); e = GLES30.glGetError() }
    }
}
