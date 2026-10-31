package com.camimu.orient.core.sim

import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.VisionMeasurement
import java.util.Random

/** Builds window measurements from simulator truth with rotation noise and optional outlier windows. */
class VisionMeasurementSimulator(private val sigmaRad: Double, seed: Long = 11L, private val outlierFrac: Double = 0.0) {
    private val rnd = Random(seed)

    /** Measurement for truth samples i1 < i2 (indices into [sim]). */
    fun measurement(sim: SimResult, i1: Int, i2: Int): VisionMeasurement {
        val qTrue = sim.truthQWD[i1].conj() * sim.truthQWD[i2]
        val noise = Quaternion.exp(Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * sigmaRad)
        var q = qTrue * noise
        if (rnd.nextDouble() < outlierFrac) q = q * Quaternion.exp(Vec3(0.0, Math.toRadians(5.0), 0.0))
        val t1 = sim.samples[i1].tNs
        val t2 = sim.samples[i2].tNs
        return VisionMeasurement(t1, t2, q.normalized(), 1.0, 100, 0.9, 0.6, sigmaRad, t2)
    }
}
