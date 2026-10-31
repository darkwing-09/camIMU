package com.camimu.orient.core.vision

import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import java.util.Random
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

data class RansacResult(
    val q21: Quaternion?,
    val inlierMask: BooleanArray,
    val nInliers: Int,
    val rmsErrRad: Double,
    val iterations: Int,
)

/** 2-point RANSAC + Horn refinement for rotation-only motion (obstacle layer 2). Deterministic per seed. */
class RotationRansac(private val cfg: VisionConfig, seed: Long = 42L) {
    private val rnd = Random(seed)
    private val minPairCos = cos(AngleMath.rad(MIN_PAIR_ANGLE_DEG))

    /** @param thresholdRad angular inlier threshold (cfg.ransacThresholdPx / fx). */
    fun estimate(b1: List<Vec3>, b2: List<Vec3>, thresholdRad: Double): RansacResult {
        require(b1.size == b2.size)
        val n = b1.size
        if (n < 2) return RansacResult(null, BooleanArray(n), 0, Double.NaN, 0)
        val cosThr = cos(thresholdRad)
        var bestCount = 0
        var bestMask = BooleanArray(n)
        var maxIter = cfg.ransacMaxIter
        var iter = 0
        val mask = BooleanArray(n)
        while (iter < maxIter) {
            iter++
            val i = rnd.nextInt(n)
            val j = rnd.nextInt(n)
            if (i == j || (b1[i] dot b1[j]) > minPairCos) continue
            val q = WahbaSolver.solve(listOf(b1[i], b1[j]), listOf(b2[i], b2[j])) ?: continue
            val c = countInliers(q, b1, b2, cosThr, mask)
            if (c > bestCount) {
                bestCount = c
                bestMask = mask.copyOf()
                val w = c.toDouble() / n
                val needed = if (w >= 1.0) MIN_ITER else ceil(ln(1 - cfg.ransacConfidence) / ln(1 - w * w)).toInt()
                maxIter = needed.coerceIn(MIN_ITER, cfg.ransacMaxIter)
            }
        }
        if (bestCount < 2) return RansacResult(null, BooleanArray(n), 0, Double.NaN, iter)
        // Refine on inliers, then re-classify once with the refined rotation.
        var q = refine(b1, b2, bestMask) ?: return RansacResult(null, BooleanArray(n), 0, Double.NaN, iter)
        val c = countInliers(q, b1, b2, cosThr, mask)
        if (c >= bestCount) {
            bestMask = mask.copyOf()
            bestCount = c
            q = refine(b1, b2, bestMask) ?: q
        }
        var sum = 0.0
        for (k in 0 until n) if (bestMask[k]) {
            val e = angle(b2[k], q.rotate(b1[k]))
            sum += e * e
        }
        return RansacResult(q, bestMask, bestCount, sqrt(sum / bestCount), iter)
    }

    private fun refine(b1: List<Vec3>, b2: List<Vec3>, m: BooleanArray): Quaternion? {
        val i1 = ArrayList<Vec3>(); val i2 = ArrayList<Vec3>()
        for (k in m.indices) if (m[k]) { i1 += b1[k]; i2 += b2[k] }
        return WahbaSolver.solve(i1, i2)
    }

    private fun countInliers(q: Quaternion, b1: List<Vec3>, b2: List<Vec3>, cosThr: Double, out: BooleanArray): Int {
        var c = 0
        for (k in b1.indices) {
            val ok = (b2[k] dot q.rotate(b1[k])) > cosThr
            out[k] = ok
            if (ok) c++
        }
        return c
    }

    private fun angle(a: Vec3, b: Vec3) = acos((a dot b).coerceIn(-1.0, 1.0))

    companion object {
        const val MIN_ITER = 10
        const val MIN_PAIR_ANGLE_DEG = 2.0
    }
}
