package com.camimu.orient.core.config

import kotlinx.serialization.Serializable

enum class IntrinsicsSource { CALIBRATED, FOV_ESTIMATE, ARCORE }

/** Pinhole + Brown–Conrady intrinsics at a given resolution (px). */
@Serializable
data class CameraIntrinsics(
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val k1: Double = 0.0,
    val k2: Double = 0.0,
    val p1: Double = 0.0,
    val p2: Double = 0.0,
    val k3: Double = 0.0,
    val source: IntrinsicsSource = IntrinsicsSource.FOV_ESTIMATE,
) {
    init {
        require(width > 0 && height > 0 && fx > 0 && fy > 0) { "invalid intrinsics" }
    }

    /** Same camera at another resolution with the same aspect ratio, or null if aspect differs. */
    fun scaledTo(w: Int, h: Int): CameraIntrinsics? {
        if (w == width && h == height) return this
        if (kotlin.math.abs(w.toDouble() / h - width.toDouble() / height) > 0.01) return null
        val sx = w.toDouble() / width
        val sy = h.toDouble() / height
        return copy(width = w, height = h, fx = fx * sx, fy = fy * sy, cx = cx * sx, cy = cy * sy)
    }
}

/** Per-device calibration file (calibration.json). docs/DataModel.md §4. */
@Serializable
data class CalibrationData(
    val deviceModel: String = "unknown",
    val intrinsics: CameraIntrinsics? = null,
    val intrinsicsRmsPx: Double = 0.0,
    val timeOffsetNs: Long = 0L,
    val arcoreTimeOffsetNs: Long = 0L,
    val gyroNoiseDensity: Double? = null,
    val biasRandomWalk: Double? = null,
    val sensorOrientationDegOverride: Int? = null,
) {
    /** Applies measured noise values (if present) to an estimator config. */
    fun applyTo(cfg: EstimatorConfig): EstimatorConfig = cfg.copy(
        gyroNoiseDensity = gyroNoiseDensity ?: cfg.gyroNoiseDensity,
        biasRandomWalk = biasRandomWalk ?: cfg.biasRandomWalk,
    )
}
