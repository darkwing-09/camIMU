package com.camimu.orient.core.vision

import com.camimu.orient.core.model.Px

/**
 * Maps raw sensor-image pixels to a portrait view showing the same image with FILL_CENTER scaling
 * (centre-crop), in continuous image coordinates, given the camera SENSOR_ORIENTATION (clockwise rotation to upright).
 */
class OverlayMapping(
    private val sensorOrientationDeg: Int,
    private val imgW: Int,
    private val imgH: Int,
    private val viewW: Double,
    private val viewH: Double,
) {
    private val rotated = sensorOrientationDeg % 180 != 0
    private val upW = if (rotated) imgH else imgW
    private val upH = if (rotated) imgW else imgH
    private val scale = maxOf(viewW / upW, viewH / upH)

    fun toView(p: Px): Px {
        val (u, v) = when (((sensorOrientationDeg % 360) + 360) % 360) {
            90 -> Pair(imgH - p.v, p.u)
            180 -> Pair(imgW - p.u, imgH - p.v)
            270 -> Pair(p.v, imgW - p.u)
            else -> Pair(p.u, p.v)
        }
        return Px((u - upW / 2.0) * scale + viewW / 2.0, (v - upH / 2.0) * scale + viewH / 2.0)
    }
}
