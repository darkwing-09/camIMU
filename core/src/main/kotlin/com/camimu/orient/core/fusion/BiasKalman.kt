package com.camimu.orient.core.fusion

import com.camimu.orient.core.math.Vec3

/** Gyro-bias Kalman filter: three independent scalar random-walk filters (Architecture §5.4). */
class BiasKalman(initialBias: Vec3, initialVar: Double, private val sigmaBw: Double, private val chi2Gate: Double) {
    /** rad/s, device frame. */
    var bias: Vec3 = initialBias
        private set
    /** Per-axis variance P_x, P_y, P_z (rad²/s²). */
    var variance: Vec3 = Vec3(initialVar, initialVar, initialVar)
        private set

    /** Random-walk prediction over [dt] seconds. */
    fun predict(dt: Double) {
        val q = sigmaBw * sigmaBw * dt
        variance = Vec3(variance.x + q, variance.y + q, variance.z + q)
    }

    /**
     * Measurement [z] (rad/s) with per-axis variance [r] (rad²/s²).
     * @return Mahalanobis² and whether it passed the χ² gate (rejected updates leave the state unchanged).
     */
    fun update(z: Vec3, r: Double): Pair<Double, Boolean> = update(z, Vec3(r, r, r))

    /** Per-axis measurement variances [r] (rad²/s²). */
    fun update(z: Vec3, r: Vec3): Pair<Double, Boolean> {
        require(r.x > 0 && r.y > 0 && r.z > 0)
        val d = z - bias
        val d2 = d.x * d.x / (variance.x + r.x) + d.y * d.y / (variance.y + r.y) + d.z * d.z / (variance.z + r.z)
        if (!d2.isFinite() || d2 > chi2Gate) return d2 to false
        val kx = variance.x / (variance.x + r.x)
        val ky = variance.y / (variance.y + r.y)
        val kz = variance.z / (variance.z + r.z)
        bias = Vec3(bias.x + kx * d.x, bias.y + ky * d.y, bias.z + kz * d.z)
        variance = Vec3((1 - kx) * variance.x, (1 - ky) * variance.y, (1 - kz) * variance.z)
        return d2 to true
    }

    /** Bias variance about the vertical given the world up vector expressed in the device frame. */
    fun verticalVariance(upD: Vec3): Double =
        upD.x * upD.x * variance.x + upD.y * upD.y * variance.y + upD.z * upD.z * variance.z
}
