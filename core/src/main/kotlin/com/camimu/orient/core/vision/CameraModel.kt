package com.camimu.orient.core.vision

import com.camimu.orient.core.config.CameraIntrinsics
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.Px

/** Pinhole + Brown–Conrady camera at analysis resolution (Architecture §6.2). */
class CameraModel(val k: CameraIntrinsics) {
    /** Unit bearing in the raw camera frame C for pixel (u, v). */
    fun bearing(u: Double, v: Double): Vec3 {
        val xd = (u - k.cx) / k.fx
        val yd = (v - k.cy) / k.fy
        var x = xd
        var y = yd
        for (it in 0 until UNDISTORT_MAX_ITERATIONS) {
            val r2 = x * x + y * y
            val radial = 1 + k.k1 * r2 + k.k2 * r2 * r2 + k.k3 * r2 * r2 * r2
            val dx = 2 * k.p1 * x * y + k.p2 * (r2 + 2 * x * x)
            val dy = k.p1 * (r2 + 2 * y * y) + 2 * k.p2 * x * y
            val nx = (xd - dx) / radial
            val ny = (yd - dy) / radial
            val change = kotlin.math.abs(nx - x) + kotlin.math.abs(ny - y)
            x = nx
            y = ny
            if (change < UNDISTORT_TOL) break
        }
        return Vec3(x, y, 1.0).normalized()
    }

    fun bearing(p: Px) = bearing(p.u, p.v)

    /** Forward distortion of normalized coordinates. */
    fun distort(x: Double, y: Double): Pair<Double, Double> {
        val r2 = x * x + y * y
        val radial = 1 + k.k1 * r2 + k.k2 * r2 * r2 + k.k3 * r2 * r2 * r2
        val dx = 2 * k.p1 * x * y + k.p2 * (r2 + 2 * x * x)
        val dy = k.p1 * (r2 + 2 * y * y) + 2 * k.p2 * x * y
        return Pair(x * radial + dx, y * radial + dy)
    }

    /** Projection without distortion; null if behind the camera. */
    fun project(b: Vec3): Px? {
        if (b.z <= 1e-6) return null
        return Px(k.fx * b.x / b.z + k.cx, k.fy * b.y / b.z + k.cy)
    }

    /** Projection including distortion (matches measured pixels); null if behind the camera. */
    fun projectDistorted(b: Vec3): Px? {
        if (b.z <= 1e-6) return null
        val (xd, yd) = distort(b.x / b.z, b.y / b.z)
        return Px(k.fx * xd + k.cx, k.fy * yd + k.cy)
    }

    companion object {
        /** Fixed-point undistortion stops at this many iterations or when the update is below [UNDISTORT_TOL]. */
        const val UNDISTORT_MAX_ITERATIONS = 20
        const val UNDISTORT_TOL = 1e-10

        /** Rough intrinsics from focal length and physical sensor width (mm), principal point at the centre. */
        fun fovEstimate(width: Int, height: Int, focalMm: Double, sensorWidthMm: Double): CameraIntrinsics {
            val f = focalMm / sensorWidthMm * width
            return CameraIntrinsics(width, height, f, f, width / 2.0, height / 2.0)
        }
    }
}
