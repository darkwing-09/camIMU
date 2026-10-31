package com.camimu.orient.pipeline

import android.os.Handler
import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.fusion.GyroHistory
import com.camimu.orient.core.fusion.OrientationEstimator
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.ImuSample
import com.camimu.orient.core.model.OcclusionState
import com.camimu.orient.core.model.OrientationOutput
import com.camimu.orient.core.model.VisionMeasurement
import com.camimu.orient.logging.SessionLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the [OrientationEstimator] and runs every call on the imu thread (single-writer rule).
 * Other threads use the post* methods. Output is published to a StateFlow at ≤ uiPublishHz.
 */
class EstimatorRunner(private val cfg: EstimatorConfig, private val logger: SessionLogger?) {
    val gyroHistory = GyroHistory(512)
    private val estimator = OrientationEstimator(cfg, gyroHistory)
    private val _output = MutableStateFlow<OrientationOutput?>(null)
    val output: StateFlow<OrientationOutput?> = _output

    @Volatile var latestBias: Vec3 = Vec3.ZERO
        private set
    @Volatile var lastImuTNs = 0L
        private set
    val visionAccepted = AtomicInteger()
    val visionTotal = AtomicInteger()

    lateinit var handler: Handler
    private var seq = 0L
    private var lastPublishNs = 0L
    private var sampleCount = 0L
    private val publishPeriodNs = (1e9 / cfg.uiPublishHz).toLong()

    /** imu thread. */
    fun onImu(s: ImuSample) {
        val out = estimator.onImu(s)
        lastImuTNs = s.tNs
        latestBias = out.gyroBias
        logger?.let {
            it.imu(s)
            if (sampleCount % cfg.estLogDecimation == 0L) it.est(out)
        }
        sampleCount++
        if (s.tNs - lastPublishNs >= publishPeriodNs) {
            _output.value = out
            lastPublishNs = s.tNs
        }
    }

    fun postVision(m: VisionMeasurement) {
        handler.post {
            val o = estimator.onVision(m)
            visionTotal.incrementAndGet()
            if (o.accepted) visionAccepted.incrementAndGet()
            logger?.vision(m, o, lastImuTNs, seq)
            seq++
        }
    }

    fun postVisionState(state: OcclusionState) {
        handler.post {
            estimator.setVisionState(state, lastImuTNs)
            logger?.event(lastImuTNs, seq, "OCCLUSION_STATE", state.name)
            seq++
        }
    }

    fun postResetYaw() {
        handler.post {
            estimator.resetYaw()
            logger?.event(lastImuTNs, seq, "RESET_YAW", "")
            seq++
        }
    }

    /** Informational event (does not affect the estimator). */
    fun postEvent(name: String, detail: String = "") {
        handler.post { logger?.event(lastImuTNs, -1, name, detail) }
    }
}
