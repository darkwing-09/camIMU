package com.camimu.orient.core.sim

import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.ImuSample
import java.util.Random
import kotlin.math.sqrt

data class SimResult(val samples: List<ImuSample>, val truthQWD: List<Quaternion>)

/** Synthetic IMU with exact truth for tests and replay demos. Timestamps start at 1e12 ns. */
class ImuSimulator(
    private val rateHz: Double = 200.0,
    private val bias: (Double) -> Vec3,
    private val gyroNoiseDensity: Double,
    private val accelNoiseStd: Double,
    private val seed: Long = 7L,
    private val gravity: Double = 9.81,
    private val jitterFrac: Double = 0.0,
) {
    constructor(rateHz: Double, bias: Vec3, gyroNoiseDensity: Double, accelNoiseStd: Double, seed: Long = 7L) :
        this(rateHz, { bias }, gyroNoiseDensity, accelNoiseStd, seed)

    /** [omegaTrueD] body rate in device frame (rad/s), [linAccW] linear acceleration in world (m/s²), both vs t (s). */
    fun simulate(
        durationSec: Double,
        q0: Quaternion,
        omegaTrueD: (Double) -> Vec3,
        linAccW: (Double) -> Vec3 = { Vec3.ZERO },
    ): SimResult {
        val rnd = Random(seed)
        val sigmaN = gyroNoiseDensity * sqrt(rateHz)
        val samples = ArrayList<ImuSample>()
        val truth = ArrayList<Quaternion>()
        var q = q0
        var tSec = 0.0
        var tNs = T0_NS
        val nominal = 1.0 / rateHz
        while (tSec <= durationSec) {
            val w = omegaTrueD(tSec)
            val n = Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * sigmaN
            val specificForceW = Vec3(0.0, 0.0, gravity) + linAccW(tSec)
            val a = q.conj().rotate(specificForceW) +
                Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * accelNoiseStd
            samples += ImuSample(tNs, w + bias(tSec) + n, a)
            truth += q
            val h = nominal * (1 + jitterFrac * (2 * rnd.nextDouble() - 1))
            val sub = h / SUBSTEPS
            repeat(SUBSTEPS) { k ->
                q = (q * Quaternion.exp(omegaTrueD(tSec + (k + 0.5) * sub) * sub)).normalized()
            }
            tSec += h
            tNs += (h * 1e9).toLong()
        }
        return SimResult(samples, truth)
    }

    companion object {
        const val T0_NS = 1_000_000_000_000L
        const val SUBSTEPS = 10
    }
}
