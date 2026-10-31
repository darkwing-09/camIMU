package com.camimu.orient.core.model

import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3

/** Pixel in the raw analysis image (u = column, v = row). */
data class Px(val u: Double, val v: Double)

/** A tracked feature; [anchor] is its position in the current anchor frame (null if born after it). */
data class Track(val id: Long, val anchor: Px?, val prev: Px, val cur: Px, val age: Int)

/** Grayscale frame (raw sensor orientation). [data] is reused between frames by the producer. */
class GrayFrame(
    val tCamNs: Long,
    /** Frame time on the IMU clock (clock conversion + time offset applied). */
    val tNs: Long,
    val width: Int,
    val height: Int,
    val data: ByteArray,
)

enum class OcclusionState { OK, LOW_TEXTURE, BLOCKED }

enum class VisionRejectReason { NOT_INITIALIZED, NO_GYRO_COVERAGE, TOO_FAST, GATE, LOW_QUALITY, BLOCKED, NOT_FINITE }

/** Rotation measured by the camera over one anchor window. */
data class VisionMeasurement(
    /** IMU-clock times of the anchor and current frame. */
    val t1Ns: Long,
    val t2Ns: Long,
    /** Device rotation D(t2) → D(t1). */
    val rotD1D2: Quaternion,
    val quality: Double,
    val nInliers: Int,
    val inlierRatio: Double,
    val spread: Double,
    val rmsErrRad: Double,
    val tCam2Ns: Long,
)

data class VisionUpdateOutcome(
    val accepted: Boolean,
    val reason: VisionRejectReason?,
    val mahalanobis2: Double = Double.NaN,
    /** Bias observation (rad/s, device frame), if computed. */
    val zBias: Vec3? = null,
)

enum class ArTracking { TRACKING, PAUSED, STOPPED }

data class ArCoreReading(
    val tNs: Long,
    val tArCoreNs: Long,
    val qWO: Quaternion,
    val tracking: ArTracking,
    val failureReason: String,
    val angles: AttitudeAngles,
)
