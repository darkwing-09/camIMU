package com.camimu.orient.core.fusion

import kotlin.math.sqrt

/** Conservative yaw-uncertainty heuristic (Architecture §5.5); not a full covariance. */
class YawUncertainty(private val sigmaG: Double) {
    /** rad² */
    var varianceRad2 = 0.0
        private set
    var timeSinceVisionSec = 0.0
        private set

    /** @param pBv vertical bias variance (rad²/s²) @param extraVar extra variance (rad²), e.g. sensor gaps. */
    fun step(dt: Double, pBv: Double, extraVar: Double = 0.0) {
        varianceRad2 += sigmaG * sigmaG * dt + pBv * (2 * timeSinceVisionSec + dt) * dt + extraVar
        timeSinceVisionSec += dt
    }

    fun onVisionAccepted(sigmaVis2: Double) {
        timeSinceVisionSec = 0.0
        varianceRad2 += sigmaVis2
    }

    fun reset() {
        varianceRad2 = 0.0
    }

    val sigmaRad get() = sqrt(varianceRad2)
}
