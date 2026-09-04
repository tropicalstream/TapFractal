package com.tapfractal.head

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.tapfractal.M
import com.tapfractal.Mat3
import com.tapfractal.Quat
import com.tapfractal.Vec3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 3-DoF head tracking (DESIGN §2). GAME_ROTATION_VECTOR is the drift-bounded reference; the
 * gyroscope integrates between samples; the GL thread eases (τ 20 ms), predicts one vsync's worth
 * ahead (`0.5·vsync + 10 ms` ≈ 18 ms — the timewarp presents every vsync with a fresh basis, so the
 * 30 fps rooms no longer need the 27 ms horizon that overshot at the start/end of every head move)
 * and applies the recentre basis W. Output: `camRot` (columns right/up/forward in the scene frame),
 * `gaze`, `gravity` (real-world down in scene coords), `headRate` (yaw/pitch/roll rad/s), and
 * One-Euro-filtered logic yaw/pitch/roll.
 *
 * Scene frame: x right, y up, z forward at the last recentre (CONTRACT C2). ENU → S is (e, n, u) → (e, u, n).
 * Optical forward is device −Z (AxisMap.ENU_MINUS_Z). If the on-device self-test (§2.5) shows swapped
 * yaw/pitch, switch to MOUNT_SWAP.
 */
class HeadTracker(ctx: Context) : SensorEventListener {
    enum class AxisMap { ENU_MINUS_Z, MOUNT_SWAP }

    companion object {
        const val TAG = "TapFractal"
        private const val EASE_TAU = 0.020f
        private const val PITCH_CLAMP = 20f * 0.017453292f
    }

    var axisMap = AxisMap.ENU_MINUS_Z
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val grv: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // ---- sensor-thread state (published under `lock`) ----
    private val lock = Any()
    private val qRef = Quat()
    private var haveRef = false
    private var firstSampleNs = 0L
    private var lastGyroNs = 0L
    private val omega = FloatArray(3)      // mean of the last two gyro samples, device frame
    private val omegaPrev = FloatArray(3)
    private val gyroDelta = Quat()
    private var conventionChecked = false

    // ---- GL-thread state ----
    private val qCur = Quat(); private var haveCur = false
    private val qRender = Quat(); private val qPred = Quat()
    private val devRot = Mat3()
    private val w = FloatArray(3)
    val camRot = Mat3()
    val gaze = Vec3(0f, 0f, 1f)
    val gravity = Vec3(0f, -1f, 0f)
    val headRate = Vec3()
    /** Logic angles (radians, One-Euro filtered): yaw + = right, pitch + = up, roll + = right ear down. */
    var yaw = 0f; var pitch = 0f; var roll = 0f
        private set
    var yawRaw = 0f; var pitchRaw = 0f; var rollRaw = 0f
        private set
    /** |ω| in rad/s (smoothed) — used for attract/creep gates. */
    var omegaMag = 0f; private set
    var enabled = true
        set(v) { if (field != v) { field = v; if (v) start() else stop() } }
    var running = false; private set
    var recentred = false; private set
    /** Set by the game each frame: soft yaw creep allowed (HOVER or looking away, no flick in 3 s). */
    var creepAllowed = false
    private var creepHoldT = 0f

    // recentre basis W (rows r0, u0, f0)
    private var yaw0 = 0f; private var pitch0 = 0f
    private val r0 = Vec3(1f, 0f, 0f); private val u0 = Vec3(0f, 1f, 0f); private val f0 = Vec3(0f, 0f, 1f)
    private val rightS = Vec3(); private val upS = Vec3(); private val fwdS = Vec3()

    // One-Euro for logic angles
    private val euroYaw = OneEuro(); private val euroPitch = OneEuro(); private val euroRoll = OneEuro()
    private var frameTime = 0f

    fun start() {
        if (running) return
        if (grv == null && gyro == null) { Log.e(TAG, "no rotation sensors — head tracking unavailable"); return }
        thread = HandlerThread("TapFractal.sensors").also { it.start() }
        handler = Handler(thread!!.looper)
        synchronized(lock) { haveRef = false; firstSampleNs = 0L; lastGyroNs = 0L }
        haveCur = false; recentred = false
        grv?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        // 200 Hz is the fastest rate allowed without HIGH_SAMPLING_RATE_SENSORS (zero-permission rule)
        gyro?.let {
            val ok = try { sm.registerListener(this, it, 5000, handler) } catch (se: SecurityException) { false }
            if (!ok) sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        running = true
        Log.i(TAG, "head tracking started grv=${grv?.name} gyro=${gyro?.name}")
    }

    fun stop() {
        if (!running) return
        sm.unregisterListener(this)
        thread?.quitSafely(); thread = null; handler = null
        running = false
        camRot.identity(); gaze.set(0f, 0f, 1f); gravity.set(0f, -1f, 0f); headRate.set(0f, 0f, 0f)
        yaw = 0f; pitch = 0f; roll = 0f
    }

    // ------------------------------------------------------------------ sensor thread

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> synchronized(lock) {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                val ww = if (e.values.size > 3) e.values[3] else kotlin.math.sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
                if (!conventionChecked) { conventionChecked = true; checkConvention(e.values, x, y, z, ww) }
                if (!haveRef) { qRef.set(x, y, z, ww).normalize(); haveRef = true; firstSampleNs = e.timestamp }
                else {
                    qPredTmp.set(x, y, z, ww).normalize()
                    if (qRef.angleTo(qPredTmp) > 0.5f) qRef.set(qPredTmp) else qRef.slerpTo(qPredTmp, 0.35f)
                }
            }
            Sensor.TYPE_GYROSCOPE -> synchronized(lock) {
                val ns = e.timestamp
                if (lastGyroNs != 0L && haveRef) {
                    val dt = ((ns - lastGyroNs) * 1e-9f).coerceIn(1e-5f, 0.05f)
                    gyroDelta.setFromRate(e.values[0], e.values[1], e.values[2], dt)
                    qRef.mul(gyroDelta, qRef).normalize()
                }
                lastGyroNs = ns
                for (i in 0 until 3) { omega[i] = 0.5f * (e.values[i] + omegaPrev[i]); omegaPrev[i] = e.values[i] }
            }
        }
    }
    private val qPredTmp = Quat()

    /** One-off sanity log: our quaternion→matrix must match SensorManager's. */
    private fun checkConvention(values: FloatArray, x: Float, y: Float, z: Float, ww: Float) {
        try {
            val r = FloatArray(9); SensorManager.getRotationMatrixFromVector(r, values)
            val m = Quat(x, y, z, ww).normalize().toMat3(Mat3())
            var maxDiff = 0f
            for (row in 0 until 3) for (col in 0 until 3) maxDiff = maxOf(maxDiff, abs(r[row * 3 + col] - m.m[col * 3 + row]))
            Log.i(TAG, "GRV convention check: max|R_android − R_ours| = $maxDiff")
        } catch (t: Throwable) { Log.w(TAG, "convention check failed: $t") }
    }

    // ------------------------------------------------------------------ GL thread

    /** Per-vsync update; framePeriod = the PRESENT period (s) for prediction — the vsync period, since the timewarp presents every vsync. */
    fun update(dt: Float, framePeriod: Float) {
        frameTime += dt
        if (!running) { camRot.identity(); gaze.set(0f, 0f, 1f); gravity.set(0f, -1f, 0f); headRate.set(0f, 0f, 0f); return }
        var ok: Boolean
        var refAgeOk = false
        synchronized(lock) {
            ok = haveRef
            if (ok) {
                qPred.set(qRef)
                w[0] = omega[0]; w[1] = omega[1]; w[2] = omega[2]
                refAgeOk = lastGyroNs == 0L || (lastGyroNs - firstSampleNs) > 400_000_000L
            }
        }
        if (!ok) return
        if (!haveCur) { qCur.set(qPred); haveCur = true } else qCur.slerpTo(qPred, M.ease(dt, EASE_TAU))
        val dtPred = (0.5f * framePeriod + 0.010f).coerceAtMost(0.040f)
        gyroPredict.setFromRate(w[0], w[1], w[2], dtPred)
        qCur.mul(gyroPredict, qRender).normalize()
        qRender.toMat3(devRot)
        // device axes in ENU are the columns of devRot; map ENU (e, n, u) → S (e, u, n); optical forward = −Z_device
        val m = devRot.m
        when (axisMap) {
            AxisMap.ENU_MINUS_Z -> {
                enuToS(m[0], m[1], m[2], rightS)
                enuToS(m[3], m[4], m[5], upS)
                enuToS(-m[6], -m[7], -m[8], fwdS)
            }
            AxisMap.MOUNT_SWAP -> { // alternative mount: forward = −Y, up = +Z
                enuToS(m[0], m[1], m[2], rightS)
                enuToS(m[6], m[7], m[8], upS)
                enuToS(-m[3], -m[4], -m[5], fwdS)
            }
        }
        yawRaw = atan2(fwdS.x, fwdS.z)
        pitchRaw = asin(fwdS.y.coerceIn(-1f, 1f))
        rollRaw = -asin(rightS.y.coerceIn(-1f, 1f))
        if (!recentred && refAgeOk) recentre()
        // head rate (device frame gyro → yaw/pitch/roll): yaw = −ω_y, pitch = +ω_x, roll = −ω_z
        val a = M.ease(dt, 0.05f)
        headRate.x += (-w[1] - headRate.x) * a
        headRate.y += (w[0] - headRate.y) * a
        headRate.z += (-w[2] - headRate.z) * a
        val om = kotlin.math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        omegaMag += (om - omegaMag) * M.ease(dt, 0.08f)
        // soft yaw creep (DESIGN §2.4.3)
        if (creepAllowed && omegaMag < M.rad(4f)) creepHoldT += dt else creepHoldT = 0f
        if (creepHoldT >= 2f) {
            var d = yawRaw - yaw0
            while (d > M.PI) d -= M.TAU; while (d < -M.PI) d += M.TAU
            yaw0 += d * (1f - exp(-dt / 12f)); rebuildW()
        }
        // apply W
        camRot.setColumns(applyW(rightS), applyW(upS), applyW(fwdS))
        gaze.set(camRot.forward)
        gravity.set(applyW(Vec3(0f, -1f, 0f)))
        // logic angles from the recentred basis, One-Euro filtered (camera basis is NOT filtered)
        val f = camRot.forward; val r = camRot.right
        val ly = if (abs(f.y) < 0.985f) atan2(f.x, f.z) else yaw   // frozen near the poles
        yaw = euroYaw.filter(ly, dt); pitch = euroPitch.filter(asin(f.y.coerceIn(-1f, 1f)), dt); roll = euroRoll.filter(-asin(r.y.coerceIn(-1f, 1f)), dt)
    }
    private val gyroPredict = Quat()

    private fun enuToS(e: Float, n: Float, u: Float, out: Vec3) { out.x = e; out.y = u; out.z = n }
    private fun applyW(v: Vec3) = Vec3(r0.dot(v), u0.dot(v), f0.dot(v))

    /** Hard recentre (triple-tap): current forward → +z, pitch clamped ±20°, roll removed. */
    fun recentre() {
        if (!running) return
        yaw0 = yawRaw; pitch0 = pitchRaw.coerceIn(-PITCH_CLAMP, PITCH_CLAMP)
        rebuildW(); recentred = true; creepHoldT = 0f
        euroYaw.reset(); euroPitch.reset(); euroRoll.reset()
        Log.i(TAG, "recentre yaw0=%.1f° pitch0=%.1f°".format(M.deg(yaw0), M.deg(pitch0)))
    }

    private fun rebuildW() {
        f0.set(cos(pitch0) * sin(yaw0), sin(pitch0), cos(pitch0) * cos(yaw0))
        val up = Vec3(0f, 1f, 0f)
        r0.set(up.cross(f0).normalized())
        u0.set(f0.cross(r0).normalized())
    }

    /** Debug readout in degrees (yaw, pitch, roll) for the §2.5 self-test. */
    fun debugString() = "yaw %.0f pitch %.0f roll %.0f".format(M.deg(yaw), M.deg(pitch), M.deg(roll))

    /** One-Euro filter (minCutoff 1 Hz, β 0.4) for logic angles only. */
    private class OneEuro(private val minCutoff: Float = 1f, private val beta: Float = 0.4f, private val dCutoff: Float = 1f) {
        private var xPrev = 0f; private var dxPrev = 0f; private var init = false
        fun reset() { init = false }
        private fun alpha(dt: Float, cutoff: Float): Float { val tau = 1f / (M.TAU * cutoff); return 1f / (1f + tau / dt.coerceAtLeast(1e-4f)) }
        fun filter(x: Float, dt: Float): Float {
            if (!init) { init = true; xPrev = x; dxPrev = 0f; return x }
            var d = x - xPrev
            if (d > M.PI) d -= M.TAU; if (d < -M.PI) d += M.TAU
            val dx = d / dt.coerceAtLeast(1e-4f)
            val ad = alpha(dt, dCutoff); dxPrev += (dx - dxPrev) * ad
            val cutoff = minCutoff + beta * abs(dxPrev)
            val a = alpha(dt, cutoff)
            xPrev += d * a
            if (xPrev > M.PI) xPrev -= M.TAU; if (xPrev < -M.PI) xPrev += M.TAU
            return xPrev
        }
    }
}
