package com.camimu.orient.core.fusion

import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.frames.AngleConverter
import com.camimu.orient.core.frames.Frames
import com.camimu.orient.core.math.Mat3
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.ImuSample

data class InitResult(
    val qWD: Quaternion,
    /** rad/s, device frame. */
    val bias: Vec3,
    /** Per-axis bias variance, rad²/s². */
    val biasVar: Double,
    /** Measured gravity magnitude, m/s². */
    val gLocal: Double,
    val yawRefRad: Double,
    val wasStatic: Boolean,
    val tNs: Long,
)

/** Initializes bias, local gravity and a gravity-aligned attitude with yaw 0 (Architecture §5.2). */
class AttitudeInitializer(private val cfg: EstimatorConfig) {
    private val detector = StaticDetector(cfg.staticInitSec, cfg.staticGyroStdThr, cfg.staticAccelStdThr)
    private var firstTNs: Long? = null

    /** Feed samples; returns non-null once initialized. */
    fun add(s: ImuSample): InitResult? {
        val t0 = firstTNs ?: s.tNs.also { firstTNs = it }
        if (!s.accelValid) return null
        detector.add(s)
        if (detector.isStatic()) {
            val a = detector.meanAccel()
            return result(a, detector.meanGyro(), cfg.gyroNoiseDensity * cfg.gyroNoiseDensity / cfg.staticInitSec, true, s.tNs)
        }
        if ((s.tNs - t0) * 1e-9 >= cfg.initTimeoutSec) {
            return result(s.accel, Vec3.ZERO, cfg.initBiasSigma * cfg.initBiasSigma, false, s.tNs)
        }
        return null
    }

    private fun result(accel: Vec3, bias: Vec3, biasVar: Double, wasStatic: Boolean, tNs: Long): InitResult {
        val qWD = attitudeFromUp(accel)
        val yawRef = AngleConverter.yawRaw(Frames.qWOFromDevice(qWD))
        return InitResult(qWD, bias, biasVar, accel.norm(), yawRef, wasStatic, tNs)
    }

    companion object {
        /** Gravity-aligned qWD whose world X is the horizontal heading of the camera axis (−z_D). */
        fun attitudeFromUp(upD: Vec3): Quaternion {
            val z = upD.normalized()
            val f = Vec3(0.0, 0.0, -1.0)
            var x = f - z * (f dot z)
            if (x.norm() < 0.1) x = Vec3.EY - z * (Vec3.EY dot z)
            x = x.normalized()
            val y = z cross x
            val rDW = Mat3.fromCols(x, y, z)
            return Quaternion.fromMatrix(rDW.transpose())
        }
    }
}
