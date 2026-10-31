package com.camimu.orient.core.model

import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3

/** Output angles (rad). When [isVertical], yaw and roll are not meaningful. */
data class AttitudeAngles(val yawRad: Double, val pitchRad: Double, val rollRad: Double, val isVertical: Boolean) {
    val yawDeg get() = AngleMath.deg(yawRad)
    val pitchDeg get() = AngleMath.deg(pitchRad)
    val rollDeg get() = AngleMath.deg(rollRad)

    companion object {
        val ZERO = AttitudeAngles(0.0, 0.0, 0.0, false)
    }
}

enum class Confidence { HIGH, MEDIUM, LOW }

enum class OrientationWarning {
    INITIALIZING, INIT_NOT_STATIC, CAMERA_BLOCKED, LOW_TEXTURE, MOVING_TOO_FAST,
    VERTICAL, ACCEL_DISTURBED, SENSOR_GAP, TRACKING_LOST,
}

/** Immutable estimator snapshot (published to UI, logged to est.csv). */
data class OrientationOutput(
    val tNs: Long,
    val initialized: Boolean,
    /** Device → world attitude. */
    val qWD: Quaternion,
    val angles: AttitudeAngles,
    /** rad/s, device frame. */
    val gyroBias: Vec3,
    val sigmaYawRad: Double,
    /** Vertical gyro-bias std, rad/s. */
    val driftSigmaRadS: Double,
    val confidence: Confidence,
    val warnings: Set<OrientationWarning>,
    val lastVisionAcceptedNs: Long?,
)

/** One gyro event merged with the latest accelerometer event. */
data class ImuSample(
    val tNs: Long,
    /** rad/s, device frame, uncorrected. */
    val gyroRaw: Vec3,
    /** m/s², device frame (reads +g upward at rest). */
    val accel: Vec3,
    val accelValid: Boolean = true,
    val accelAgeNs: Long = 0L,
    /** OS bias estimate from TYPE_GYROSCOPE_UNCALIBRATED (log only). */
    val osBias: Vec3? = null,
)
