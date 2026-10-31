package com.camimu.orient.core.fusion

import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.ImuSample
import kotlin.math.abs

/**
 * Gyro propagation + Mahony-style proportional tilt correction (Architecture §5.3).
 * e_tilt = â × ĝ is perpendicular to gravity, so the accelerometer never changes yaw.
 */
class AttitudeFilter(private val cfg: EstimatorConfig, initial: Quaternion, private val gLocal: Double) {
    var qWD: Quaternion = initial
        private set
    /** Weight (0..1) the accelerometer got in the last step. */
    var lastAccelWeight = 0.0
        private set
    /** Bias-corrected rate magnitude of the last step, rad/s. */
    var lastRateRadS = 0.0
        private set
    private var prev: ImuSample? = null

    /** Sets the previous sample without integrating (used right after initialization). */
    fun seed(s: ImuSample) {
        prev = s
    }

    /** @return dt used (s), or 0.0 if the sample was dropped (first sample, duplicate, out of order). */
    fun step(s: ImuSample, bias: Vec3): Double {
        val p = prev ?: run { prev = s; return 0.0 }
        val dt = (s.tNs - p.tNs) * 1e-9
        if (dt <= 0.0) return 0.0
        val omega = (p.gyroRaw + s.gyroRaw) * 0.5 - bias
        lastRateRadS = omega.norm()
        var corr = Vec3.ZERO
        lastAccelWeight = 0.0
        if (s.accelValid) {
            val an = s.accel.norm()
            val w = (1.0 - abs(an - gLocal) / cfg.accelGateMs2).coerceIn(0.0, 1.0) *
                (if (lastRateRadS < cfg.maxRateForTiltRadS) 1.0 else 0.0)
            lastAccelWeight = w
            if (w > 0.0 && an > 1e-6) {
                val upPred = qWD.conj().rotate(Vec3.EZ)
                corr = ((s.accel / an) cross upPred) * (cfg.kTilt * w)
            }
        }
        qWD = (qWD * Quaternion.exp((omega + corr) * dt)).normalized()
        prev = s
        return dt
    }

    /** Right-multiplies a small correction (device frame), used by the vision update. */
    fun applyCorrection(qRight: Quaternion) {
        qWD = (qWD * qRight).normalized()
    }
}
