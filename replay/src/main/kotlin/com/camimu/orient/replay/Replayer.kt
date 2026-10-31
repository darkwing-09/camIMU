package com.camimu.orient.replay

import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.fusion.GyroHistory
import com.camimu.orient.core.fusion.OrientationEstimator
import com.camimu.orient.core.io.ReplayAction
import com.camimu.orient.core.model.ImuSample
import com.camimu.orient.core.model.OrientationOutput

/** Drives [OrientationEstimator] with logged samples and actions in the same order as on the device. */
object Replayer {
    data class Result(val outputs: List<OrientationOutput>, val visionAccepted: Int, val visionTotal: Int)

    fun run(samples: List<ImuSample>, actions: List<ReplayAction>, cfg: EstimatorConfig): Result {
        val est = OrientationEstimator(cfg, GyroHistory(1024))
        val byTime = actions.groupBy { it.tAppliedNs }
        val outs = ArrayList<OrientationOutput>(samples.size)
        var accepted = 0
        var total = 0
        for (s in samples) {
            outs += est.onImu(s)
            byTime[s.tNs]?.forEach { a ->
                when (a) {
                    is ReplayAction.Vision -> { total++; if (est.onVision(a.m).accepted) accepted++ }
                    is ReplayAction.ResetYaw -> est.resetYaw()
                    is ReplayAction.Occlusion -> est.setVisionState(a.state, s.tNs)
                }
            }
        }
        return Result(outs, accepted, total)
    }
}
