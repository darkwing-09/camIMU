package com.camimu.orient.core.vision

import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.core.math.Mat3
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.model.Px

/** Predicts feature motion from the gyro rotation (obstacle layer 1, Architecture §6.4). */
class GyroFlowPredictor(private val camera: CameraModel, private val rCD: Mat3) {
    /** rCD · R · rCDᵀ, i.e. camera frame 2 → camera frame 1 for a device rotation D2 → D1. */
    fun cameraRotation(rotD1D2: Quaternion): Mat3 = rCD * rotD1D2.toMatrix() * rCD.transpose()

    /** Predicted pixel in frame 2 for a static far point at [p1] in frame 1. */
    fun predict(p1: Px, rotD1D2: Quaternion): Px? = predictWith(p1, cameraRotation(rotD1D2).transpose())

    /** Same as [predict] with a precomputed rC2C1 (= cameraRotation(..)ᵀ), for per-frame loops. */
    fun predictWith(p1: Px, rC2C1: Mat3): Px? = camera.projectDistorted(rC2C1 * camera.bearing(p1))

    /** Gate radius in px: base + factor · fx · rotation angle. */
    fun gatePx(rotD1D2: Quaternion, cfg: VisionConfig): Double =
        cfg.gateBasePx + cfg.gateRateFactor * camera.k.fx * rotD1D2.log().norm()
}
