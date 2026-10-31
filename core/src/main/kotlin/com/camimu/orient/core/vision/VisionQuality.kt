package com.camimu.orient.core.vision

import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.core.model.VisionRejectReason

data class QualityResult(
    val quality: Double,
    val valid: Boolean,
    val inlierRatio: Double,
    val spread: Double,
    val reason: VisionRejectReason?,
)

/** Quality score and validity of a rotation estimate (majority rule, spread, inlier count). */
object VisionQuality {
    /** @param inlierCells grid cell index of each inlier in the current image. */
    fun evaluate(nInliers: Int, nTrackedBeforeGating: Int, inlierCells: IntArray, cfg: VisionConfig): QualityResult {
        val ratio = if (nTrackedBeforeGating > 0) nInliers.toDouble() / nTrackedBeforeGating else 0.0
        val spread = inlierCells.distinct().size.toDouble() / (cfg.gridCols * cfg.gridRows)
        val q = (nInliers.toDouble() / cfg.goodInliers).coerceIn(0.0, 1.0) *
            ((ratio - cfg.minInlierRatio) / (1 - cfg.minInlierRatio)).coerceIn(0.0, 1.0) *
            (spread / cfg.goodSpread).coerceIn(0.0, 1.0)
        val valid = nInliers >= cfg.minInliers && ratio >= cfg.minInlierRatio && spread >= cfg.minSpread && q > 0.0
        return QualityResult(q, valid, ratio, spread, if (valid) null else VisionRejectReason.LOW_QUALITY)
    }
}
