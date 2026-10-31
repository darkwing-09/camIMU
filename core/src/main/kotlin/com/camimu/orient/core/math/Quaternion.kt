package com.camimu.orient.core.math

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hamilton unit quaternion stored [w, x, y, z]. `qAB` maps B-frame vectors into frame A,
 * composition `qAC = qAB * qBC`. See docs/Architecture.md §4.2.
 */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(q: Quaternion) = Quaternion(
        w * q.w - x * q.x - y * q.y - z * q.z,
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
    )

    fun conj() = Quaternion(w, -x, -y, -z)
    fun norm() = sqrt(w * w + x * x + y * y + z * z)

    fun normalized(): Quaternion {
        val n = norm()
        require(n > 1e-12) { "cannot normalize a zero quaternion" }
        return Quaternion(w / n, x / n, y / n, z / n)
    }

    /** Same rotation with w ≥ 0 (q and −q represent the same rotation). */
    fun canonical() = if (w < 0) Quaternion(-w, -x, -y, -z) else this

    /** Rotates [v]: v' = v + 2w(u×v) + 2u×(u×v). */
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2.0
        return v + t * w + (u cross t)
    }

    fun toMatrix(): Mat3 = Mat3.of(
        1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
        2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
        2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
    )

    /** Rotation vector (axis · angle, rad), angle in [0, π]. */
    fun log(): Vec3 {
        val q = canonical()
        val s = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
        if (s < 1e-12) return Vec3(2 * q.x, 2 * q.y, 2 * q.z)
        val k = 2 * atan2(s, q.w) / s
        return Vec3(q.x * k, q.y * k, q.z * k)
    }

    /** Rotation angle between this and [o], rad, in [0, π]. */
    fun angleTo(o: Quaternion): Double = (conj() * o).log().norm()

    fun isFinite() = w.isFinite() && x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        /** Unit quaternion from rotation vector [phi] (rad). */
        fun exp(phi: Vec3): Quaternion {
            val th = phi.norm()
            if (th < 1e-8) return Quaternion(1.0, phi.x / 2, phi.y / 2, phi.z / 2).normalized()
            val s = sin(th / 2) / th
            return Quaternion(cos(th / 2), phi.x * s, phi.y * s, phi.z * s)
        }

        fun fromAxisAngle(axis: Vec3, angleRad: Double) = exp(axis.normalized() * angleRad)

        /** Shepperd's method; result normalized with w ≥ 0. [m] must be a rotation matrix. */
        fun fromMatrix(m: Mat3): Quaternion {
            val t = m[0, 0] + m[1, 1] + m[2, 2]
            val q = if (t > 0) {
                val s = 2 * sqrt(t + 1)
                Quaternion(s / 4, (m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s, (m[1, 0] - m[0, 1]) / s)
            } else if (m[0, 0] > m[1, 1] && m[0, 0] > m[2, 2]) {
                val s = 2 * sqrt(1 + m[0, 0] - m[1, 1] - m[2, 2])
                Quaternion((m[2, 1] - m[1, 2]) / s, s / 4, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s)
            } else if (m[1, 1] > m[2, 2]) {
                val s = 2 * sqrt(1 + m[1, 1] - m[0, 0] - m[2, 2])
                Quaternion((m[0, 2] - m[2, 0]) / s, (m[0, 1] + m[1, 0]) / s, s / 4, (m[1, 2] + m[2, 1]) / s)
            } else {
                val s = 2 * sqrt(1 + m[2, 2] - m[0, 0] - m[1, 1])
                Quaternion((m[1, 0] - m[0, 1]) / s, (m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, s / 4)
            }
            return q.normalized().canonical()
        }
    }
}
