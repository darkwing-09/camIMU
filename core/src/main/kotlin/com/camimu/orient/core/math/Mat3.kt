package com.camimu.orient.core.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Immutable row-major 3x3 matrix. A rotation `rAB` maps B-frame vectors into frame A. */
class Mat3 private constructor(private val m: DoubleArray) {
    operator fun get(r: Int, c: Int): Double = m[r * 3 + c]

    operator fun times(o: Mat3): Mat3 {
        val out = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            out[r * 3 + c] = m[r * 3] * o.m[c] + m[r * 3 + 1] * o.m[3 + c] + m[r * 3 + 2] * o.m[6 + c]
        }
        return Mat3(out)
    }

    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    fun transpose() = Mat3(doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    fun det(): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    /** Inverse via the adjugate; null if (near-)singular. */
    fun inverse(): Mat3? {
        val d = det()
        if (abs(d) < 1e-300) return null
        val i = 1.0 / d
        return Mat3(
            doubleArrayOf(
                (m[4] * m[8] - m[5] * m[7]) * i, (m[2] * m[7] - m[1] * m[8]) * i, (m[1] * m[5] - m[2] * m[4]) * i,
                (m[5] * m[6] - m[3] * m[8]) * i, (m[0] * m[8] - m[2] * m[6]) * i, (m[2] * m[3] - m[0] * m[5]) * i,
                (m[3] * m[7] - m[4] * m[6]) * i, (m[1] * m[6] - m[0] * m[7]) * i, (m[0] * m[4] - m[1] * m[3]) * i,
            ),
        )
    }

    operator fun plus(o: Mat3) = Mat3(DoubleArray(9) { m[it] + o.m[it] })
    operator fun times(s: Double) = Mat3(DoubleArray(9) { m[it] * s })

    fun col(c: Int) = Vec3(m[c], m[3 + c], m[6 + c])
    fun row(r: Int) = Vec3(m[r * 3], m[r * 3 + 1], m[r * 3 + 2])

    /** Copy of the 9 values in row-major order. */
    fun toRowMajor(): DoubleArray = m.copyOf()

    fun approxEquals(o: Mat3, tol: Double = 1e-9): Boolean = (0 until 9).all { abs(m[it] - o.m[it]) <= tol }

    override fun equals(other: Any?) = other is Mat3 && m.contentEquals(other.m)
    override fun hashCode() = m.contentHashCode()
    override fun toString() = "Mat3(${m.joinToString()})"

    companion object {
        val IDENTITY = diag(1.0, 1.0, 1.0)

        fun of(vararg rowMajor: Double): Mat3 {
            require(rowMajor.size == 9) { "Mat3 needs 9 values, got ${rowMajor.size}" }
            return Mat3(rowMajor.copyOf())
        }

        fun fromCols(c0: Vec3, c1: Vec3, c2: Vec3) = Mat3(doubleArrayOf(c0.x, c1.x, c2.x, c0.y, c1.y, c2.y, c0.z, c1.z, c2.z))
        fun fromRows(r0: Vec3, r1: Vec3, r2: Vec3) = Mat3(doubleArrayOf(r0.x, r0.y, r0.z, r1.x, r1.y, r1.z, r2.x, r2.y, r2.z))
        fun diag(a: Double, b: Double, c: Double) = Mat3(doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c))

        /** Right-handed rotation about +X by [rad]. */
        fun rotX(rad: Double): Mat3 {
            val c = cos(rad); val s = sin(rad)
            return Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c))
        }

        /** Right-handed rotation about +Y by [rad]. */
        fun rotY(rad: Double): Mat3 {
            val c = cos(rad); val s = sin(rad)
            return Mat3(doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c))
        }

        /** Right-handed rotation about +Z by [rad]. */
        fun rotZ(rad: Double): Mat3 {
            val c = cos(rad); val s = sin(rad)
            return Mat3(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
        }
    }
}
