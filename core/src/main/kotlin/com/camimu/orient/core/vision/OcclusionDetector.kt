package com.camimu.orient.core.vision

import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.core.model.OcclusionState

/** Debounced blocked / low-texture detection (obstacle layer 9, Architecture §6.8). */
class OcclusionDetector(private val cfg: VisionConfig) {
    var state = OcclusionState.OK
        private set
    private var candidate = OcclusionState.OK
    private var candidateSinceNs = 0L

    fun update(tNs: Long, nTracked: Int, imageStd: Double): OcclusionState {
        val raw = when {
            imageStd < cfg.minImageStd || nTracked < BLOCKED_TRACKS -> OcclusionState.BLOCKED
            nTracked < cfg.minTracked -> OcclusionState.LOW_TEXTURE
            else -> OcclusionState.OK
        }
        if (raw != candidate) {
            candidate = raw
            candidateSinceNs = tNs
        }
        if (candidate != state && (tNs - candidateSinceNs) * 1e-9 >= cfg.occlusionDebounceSec) state = candidate
        return state
    }

    companion object {
        const val BLOCKED_TRACKS = 5
    }
}
