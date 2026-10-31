package com.camimu.orient.core.config

import kotlinx.serialization.Serializable

/** Vision front-end parameters. Meaning and units: docs/DataModel.md §3. */
@Serializable
data class VisionConfig(
    val analysisWidth: Int = 640,
    val analysisHeight: Int = 480,
    val gridCols: Int = 8,
    val gridRows: Int = 6,
    val maxPerCell: Int = 4,
    val targetTracks: Int = 150,
    val replenishFraction: Double = 0.6,
    val detectMaxCorners: Int = 600,
    val qualityLevel: Double = 0.01,
    val minDistancePx: Double = 10.0,
    val blockSize: Int = 3,
    val kltWinSize: Int = 21,
    val kltMaxLevel: Int = 3,
    val fbThresholdPx: Double = 1.0,
    val gateBasePx: Double = 4.0,
    val gateRateFactor: Double = 0.15,
    val ransacThresholdPx: Double = 1.5,
    val ransacMaxIter: Int = 100,
    val ransacConfidence: Double = 0.99,
    val minInliers: Int = 15,
    val goodInliers: Int = 60,
    val minInlierRatio: Double = 0.4,
    val minSpread: Double = 0.15,
    val goodSpread: Double = 0.4,
    val windowMaxSec: Double = 1.0,
    val windowMinSec: Double = 0.2,
    val windowMinSurvival: Double = 0.5,
    val minTracked: Int = 20,
    val minImageStd: Double = 8.0,
    val occlusionDebounceSec: Double = 0.5,
    val debugOverlay: Boolean = true,
    /** Lock focus at infinity (more accurate, blurry for near objects) instead of continuous AF. */
    val lockFocusInfinity: Boolean = false,
) {
    init {
        require(analysisWidth > 0 && analysisHeight > 0)
        require(gridCols > 0 && gridRows > 0 && maxPerCell > 0)
        require(replenishFraction in 0.0..1.0)
        require(ransacThresholdPx > 0 && ransacMaxIter >= 10)
        require(ransacConfidence > 0 && ransacConfidence < 1)
        require(minInlierRatio in 0.0..0.99)
        require(windowMinSec > 0 && windowMinSec < windowMaxSec) { "windowMinSec must be < windowMaxSec" }
        require(windowMinSurvival in 0.0..1.0)
    }
}
