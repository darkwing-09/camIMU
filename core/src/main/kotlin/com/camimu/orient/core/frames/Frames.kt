package com.camimu.orient.core.frames

import com.camimu.orient.core.math.Mat3
import com.camimu.orient.core.math.Quaternion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Fixed frame rotations. See docs/Architecture.md §4.3. */
object Frames {
    /** Device → output camera (portrait back camera): rDO = diag(1, −1, −1), 180° about x_D. */
    val Q_DO = Quaternion(0.0, 1.0, 0.0, 0.0)

    /** ARCore world (Y up) → our world (Z up): +90° about x. */
    val Q_WA = Quaternion(cos(PI / 4), sin(PI / 4), 0.0, 0.0)

    /** ARCore OpenGL camera (x right, y up, −z view) → output camera O. */
    val Q_GO = Quaternion(0.0, 1.0, 0.0, 0.0)

    /** Reference pose (all angles 0): portrait upright, camera along +X_W. Columns r = −Y, d = −Z, f = +X. */
    val R_WO0: Mat3 = Mat3.of(
        0.0, 0.0, 1.0,
        -1.0, 0.0, 0.0,
        0.0, -1.0, 0.0,
    )

    /** rCD (device → raw camera image frame) for a back camera with this SENSOR_ORIENTATION (deg). */
    fun rCDForSensorOrientation(deg: Int): Mat3 = when (((deg % 360) + 360) % 360) {
        0 -> Mat3.diag(1.0, -1.0, -1.0)
        90 -> Mat3.of(0.0, -1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, -1.0)
        180 -> Mat3.diag(-1.0, 1.0, -1.0)
        270 -> Mat3.of(0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0)
        else -> throw IllegalArgumentException("unsupported sensor orientation $deg")
    }

    /** qWO from the device attitude qWD. */
    fun qWOFromDevice(qWD: Quaternion): Quaternion = qWD * Q_DO

    /** ARCore displayOrientedPose rotation given as [x, y, z, w] (ARCore order) → qWO in our world. */
    fun qWOFromArCore(xyzw: FloatArray): Quaternion {
        require(xyzw.size >= 4)
        val qAG = Quaternion(xyzw[3].toDouble(), xyzw[0].toDouble(), xyzw[1].toDouble(), xyzw[2].toDouble()).normalized()
        return (Q_WA * qAG * Q_GO).normalized()
    }
}
