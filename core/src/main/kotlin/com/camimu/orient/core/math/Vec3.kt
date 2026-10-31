package com.camimu.orient.core.math

import kotlin.math.sqrt

/** Immutable 3-vector. Units and frame are given by the variable name (e.g. `omegaD` in rad/s, device frame). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    operator fun get(i: Int): Double = when (i) {
        0 -> x
        1 -> y
        2 -> z
        else -> throw IndexOutOfBoundsException("Vec3 index $i")
    }

    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm(): Double = sqrt(this dot this)

    /** Unit vector in the same direction. Throws for (near-)zero vectors. */
    fun normalized(): Vec3 {
        val n = norm()
        require(n > 1e-12) { "cannot normalize a zero vector" }
        return this / n
    }

    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val EX = Vec3(1.0, 0.0, 0.0)
        val EY = Vec3(0.0, 1.0, 0.0)
        val EZ = Vec3(0.0, 0.0, 1.0)
    }
}
