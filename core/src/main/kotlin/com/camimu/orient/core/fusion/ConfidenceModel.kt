package com.camimu.orient.core.fusion

import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.model.Confidence
import com.camimu.orient.core.model.OrientationWarning

/** Three-level confidence (Architecture §7). Never HIGH without recent accepted vision. */
object ConfidenceModel {
    fun level(
        visionAgeSec: Double?,
        driftDegPerMin: Double,
        warnings: Set<OrientationWarning>,
        blockedForSec: Double,
        cfg: EstimatorConfig,
    ): Confidence {
        if (OrientationWarning.INITIALIZING in warnings || OrientationWarning.VERTICAL in warnings) return Confidence.LOW
        if (blockedForSec > cfg.blockedHoldSec) return Confidence.LOW
        val cameraProblem = OrientationWarning.CAMERA_BLOCKED in warnings || OrientationWarning.LOW_TEXTURE in warnings
        if (!cameraProblem && visionAgeSec != null && visionAgeSec < cfg.highVisionAgeSec && driftDegPerMin < cfg.highDriftDegPerMin) {
            return Confidence.HIGH
        }
        if ((visionAgeSec != null && visionAgeSec < cfg.mediumVisionAgeSec) || driftDegPerMin < cfg.mediumDriftDegPerMin) {
            return Confidence.MEDIUM
        }
        return Confidence.LOW
    }
}
