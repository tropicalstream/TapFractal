package com.tapfractal

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small float vector/matrix/quaternion kit for the toy, the flight controller and the head
 * tracker. World frame (DESIGN §1.2 / CONTRACT C2): x = right, y = up, z = forward, left-handed
 * in the sense that cross(right, up) = forward with the ordinary component formula.
 */
class Vec3(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun lengthSq() = x * x + y * y + z * z
    fun normalized(): Vec3 { val l = length(); return if (l > 1e-9f) Vec3(x / l, y / l, z / l) else Vec3(0f, 0f, 1f) }
    fun copy() = Vec3(x, y, z)
    fun set(o: Vec3): Vec3 { x = o.x; y = o.y; z = o.z; return this }
    fun set(nx: Float, ny: Float, nz: Float): Vec3 { x = nx; y = ny; z = nz; return this }
    fun addScaled(o: Vec3, s: Float): Vec3 { x += o.x * s; y += o.y * s; z += o.z * s; return this }
    fun scale(s: Float): Vec3 { x *= s; y *= s; z *= s; return this }
    fun distance(o: Vec3) = (this - o).length()
    fun isZero() = x == 0f && y == 0f && z == 0f
    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)

    companion object {
        fun lerp(a: Vec3, b: Vec3, t: Float) = Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
        fun reflect(v: Vec3, n: Vec3): Vec3 { val d = 2f * v.dot(n); return Vec3(v.x - n.x * d, v.y - n.y * d, v.z - n.z * d) }
        /** Spherical interpolation between unit directions (falls back to nlerp when nearly parallel). */
        fun slerpDir(a: Vec3, b: Vec3, t: Float): Vec3 {
            val d = a.dot(b).coerceIn(-1f, 1f)
            if (d > 0.9995f) return lerp(a, b, t).normalized()
            if (d < -0.9995f) { // opposite: pick any perpendicular pivot
                val piv = if (abs(a.y) < 0.9f) a.cross(Vec3(0f, 1f, 0f)).normalized() else a.cross(Vec3(1f, 0f, 0f)).normalized()
                val ang = t * Math.PI.toFloat()
                return (a * cos(ang) + piv * sin(ang)).normalized()
            }
            val th = acos(d); val s = sin(th)
            return (a * (sin((1f - t) * th) / s) + b * (sin(t * th) / s)).normalized()
        }
    }
}

class Vec2(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun length() = sqrt(x * x + y * y)
    fun normalized(): Vec2 { val l = length(); return if (l > 1e-9f) Vec2(x / l, y / l) else Vec2(1f, 0f) }
    fun set(nx: Float, ny: Float): Vec2 { x = nx; y = ny; return this }
    fun copy() = Vec2(x, y)
}

/** 3×3 matrix stored column-major (GL layout): m[col*3 + row]. Columns are right/up/forward for a camera basis. */
class Mat3 {
    @JvmField val m = FloatArray(9)

    init { identity() }

    fun identity(): Mat3 { m.fill(0f); m[0] = 1f; m[4] = 1f; m[8] = 1f; return this }
    fun setColumns(c0: Vec3, c1: Vec3, c2: Vec3): Mat3 {
        m[0] = c0.x; m[1] = c0.y; m[2] = c0.z
        m[3] = c1.x; m[4] = c1.y; m[5] = c1.z
        m[6] = c2.x; m[7] = c2.y; m[8] = c2.z
        return this
    }
    fun set(o: Mat3): Mat3 { System.arraycopy(o.m, 0, m, 0, 9); return this }
    fun col(i: Int) = Vec3(m[i * 3], m[i * 3 + 1], m[i * 3 + 2])
    val right get() = col(0)
    val up get() = col(1)
    val forward get() = col(2)
    /** M · v */
    fun mul(v: Vec3) = Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z,
    )
    /** Mᵀ · v (world → camera for an orthonormal basis). */
    fun mulT(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )
    fun transposed(): Mat3 {
        val r = Mat3()
        for (c in 0 until 3) for (row in 0 until 3) r.m[c * 3 + row] = m[row * 3 + c]
        return r
    }
    /** this · o */
    fun mul(o: Mat3): Mat3 {
        val r = Mat3()
        for (c in 0 until 3) for (row in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) s += m[k * 3 + row] * o.m[c * 3 + k]
            r.m[c * 3 + row] = s
        }
        return r
    }
}

/** Unit quaternion (x, y, z, w) — Android's GAME_ROTATION_VECTOR layout. */
class Quat(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f, @JvmField var w: Float = 1f) {
    fun set(o: Quat): Quat { x = o.x; y = o.y; z = o.z; w = o.w; return this }
    fun set(nx: Float, ny: Float, nz: Float, nw: Float): Quat { x = nx; y = ny; z = nz; w = nw; return this }
    fun normalize(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        if (l > 1e-9f) { x /= l; y /= l; z /= l; w /= l } else { x = 0f; y = 0f; z = 0f; w = 1f }
        return this
    }
    fun dot(o: Quat) = x * o.x + y * o.y + z * o.z + w * o.w
    /** Hamilton product this ⊗ o, result into out (may alias this). */
    fun mul(o: Quat, out: Quat): Quat {
        val nw = w * o.w - x * o.x - y * o.y - z * o.z
        val nx = w * o.x + x * o.w + y * o.z - z * o.y
        val ny = w * o.y - x * o.z + y * o.w + z * o.x
        val nz = w * o.z + x * o.y - y * o.x + z * o.w
        out.x = nx; out.y = ny; out.z = nz; out.w = nw
        return out
    }
    /** exp(½ ω dt) as a unit quaternion (small-angle safe). */
    fun setFromRate(wx: Float, wy: Float, wz: Float, dt: Float): Quat {
        val ang = sqrt(wx * wx + wy * wy + wz * wz) * dt
        if (ang < 1e-7f) { x = 0.5f * wx * dt; y = 0.5f * wy * dt; z = 0.5f * wz * dt; w = 1f; return normalize() }
        val s = sin(0.5f * ang) / (ang / dt)
        x = wx * s; y = wy * s; z = wz * s; w = cos(0.5f * ang)
        return this
    }
    fun angleTo(o: Quat): Float { val d = abs(dot(o)).coerceIn(0f, 1f); return 2f * acos(d) }
    /** this = slerp(this, target, t) */
    fun slerpTo(target: Quat, t: Float): Quat {
        var d = dot(target)
        var tx = target.x; var ty = target.y; var tz = target.z; var tw = target.w
        if (d < 0f) { d = -d; tx = -tx; ty = -ty; tz = -tz; tw = -tw }
        if (d > 0.9995f) {
            x += (tx - x) * t; y += (ty - y) * t; z += (tz - z) * t; w += (tw - w) * t
            return normalize()
        }
        val th = acos(d.coerceIn(-1f, 1f)); val s = sin(th)
        val a = sin((1f - t) * th) / s; val b = sin(t * th) / s
        x = x * a + tx * b; y = y * a + ty * b; z = z * a + tz * b; w = w * a + tw * b
        return normalize()
    }
    /** Rotation matrix (world ← device) into a column-major Mat3; matches SensorManager.getRotationMatrixFromVector. */
    fun toMat3(out: Mat3): Mat3 {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        val r = out.m
        // row-major R[row][col] → column-major m[col*3+row]
        r[0] = 1f - 2f * (yy + zz); r[3] = 2f * (xy - wz);     r[6] = 2f * (xz + wy)
        r[1] = 2f * (xy + wz);      r[4] = 1f - 2f * (xx + zz); r[7] = 2f * (yz - wx)
        r[2] = 2f * (xz - wy);      r[5] = 2f * (yz + wx);     r[8] = 1f - 2f * (xx + yy)
        return out
    }
}

object M {
    const val PI = 3.1415927f
    const val TAU = 6.2831855f
    fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
    fun smoothstep(e0: Float, e1: Float, x: Float): Float { val t = clamp((x - e0) / (e1 - e0), 0f, 1f); return t * t * (3f - 2f * t) }
    fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun deg(rad: Float) = rad * 57.29578f
    fun rad(deg: Float) = deg * 0.017453292f
    fun fract(v: Float) = v - kotlin.math.floor(v)
    /** Frame-rate independent ease factor 1 − e^(−dt/τ). */
    fun ease(dt: Float, tau: Float) = 1f - kotlin.math.exp(-dt / tau)
}
