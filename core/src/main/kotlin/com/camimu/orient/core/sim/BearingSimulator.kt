package com.camimu.orient.core.sim

import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic bearing correspondences with noise, random outliers and a consistently moving cluster. */
class BearingSimulator(seed: Long) {
    private val rnd = Random(seed)

    fun randomBearing(halfFovRad: Double): Vec3 {
        val r = halfFovRad * sqrt(rnd.nextDouble())
        val phi = rnd.nextDouble() * 2 * Math.PI
        return Vec3(sin(r) * cos(phi), sin(r) * sin(phi), cos(r))
    }

    private fun noisy(b: Vec3, sigmaRad: Double): Vec3 =
        (b + Vec3(rnd.nextGaussian(), rnd.nextGaussian(), 0.0) * sigmaRad).normalized()

    /** Returns (b1, b2) with b2 ≈ q21·b1 for the static share. */
    fun pairs(
        n: Int, q21: Quaternion, fx: Double, noisePx: Double, outlierFrac: Double = 0.0,
        movingFrac: Double = 0.0, movingQ21: Quaternion = Quaternion.IDENTITY, halfFovRad: Double = 0.5,
    ): Pair<List<Vec3>, List<Vec3>> {
        val sigma = noisePx / fx
        val b1 = ArrayList<Vec3>(); val b2 = ArrayList<Vec3>()
        val nOut = (n * outlierFrac).toInt()
        val nMov = (n * movingFrac).toInt()
        for (i in 0 until n) {
            val p = randomBearing(halfFovRad)
            b1 += noisy(p, sigma)
            b2 += when {
                i < nOut -> randomBearing(halfFovRad)
                i < nOut + nMov -> noisy(movingQ21.rotate(p), sigma)
                else -> noisy(q21.rotate(p), sigma)
            }
        }
        return b1 to b2
    }
}
