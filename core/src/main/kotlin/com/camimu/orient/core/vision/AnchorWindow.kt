package com.camimu.orient.core.vision

import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.core.math.Mat3
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.model.VisionMeasurement

/** Anchor-window bookkeeping (Architecture §6.6). */
class AnchorWindow(private val cfg: VisionConfig) {
    enum class Decision { CONTINUE, CLOSE, RESET }

    var anchorTNs: Long? = null
        private set
    private var anchorCount = 0

    fun start(tNs: Long, nAnchorTracks: Int) {
        anchorTNs = tNs
        anchorCount = nAnchorTracks
    }

    fun reset() {
        anchorTNs = null
        anchorCount = 0
    }

    /** Decide per frame given how many of the anchor's tracks are still alive. */
    fun onFrame(tNs: Long, nAnchorTracksAlive: Int): Decision {
        val t0 = anchorTNs ?: return Decision.RESET
        val elapsed = (tNs - t0) * 1e-9
        val survival = if (anchorCount > 0) nAnchorTracksAlive.toDouble() / anchorCount else 0.0
        if (elapsed >= cfg.windowMaxSec) return Decision.CLOSE
        if (survival < cfg.windowMinSurvival) return if (elapsed >= cfg.windowMinSec) Decision.CLOSE else Decision.RESET
        return Decision.CONTINUE
    }

    companion object {
        /** Builds the device-frame measurement from the camera rotation q21 (anchor bearings → current bearings). */
        fun buildMeasurement(
            tAnchorNs: Long, tCurNs: Long, tCamCurNs: Long, q21: Quaternion, rCD: Mat3,
            q: QualityResult, nInliers: Int, rmsErrRad: Double,
        ): VisionMeasurement {
            val rotD1D2 = Quaternion.fromMatrix(rCD.transpose() * q21.toMatrix().transpose() * rCD)
            return VisionMeasurement(tAnchorNs, tCurNs, rotD1D2, q.quality, nInliers, q.inlierRatio, q.spread, rmsErrRad, tCamCurNs)
        }

        /** Device rotation D2 → D1 from camera rotation q21. */
        fun deviceRotation(q21: Quaternion, rCD: Mat3): Quaternion =
            Quaternion.fromMatrix(rCD.transpose() * q21.toMatrix().transpose() * rCD)
    }
}
