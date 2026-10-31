package com.camimu.orient.core.fusion

import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.frames.AngleConverter
import com.camimu.orient.core.frames.Frames
import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.AttitudeAngles
import com.camimu.orient.core.model.Confidence
import com.camimu.orient.core.model.ImuSample
import com.camimu.orient.core.model.OcclusionState
import com.camimu.orient.core.model.OrientationOutput
import com.camimu.orient.core.model.OrientationWarning
import com.camimu.orient.core.model.VisionMeasurement
import com.camimu.orient.core.model.VisionRejectReason
import com.camimu.orient.core.model.VisionUpdateOutcome
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Orientation estimator facade. NOT thread-safe: call every method from one thread (the imu thread).
 * Pipeline: init → complementary attitude filter → bias Kalman (ZRU + vision) → vision attitude nudge.
 */
class OrientationEstimator(private val cfg: EstimatorConfig, val gyroHistory: GyroHistory = GyroHistory()) {
    private val initializer = AttitudeInitializer(cfg)
    private val zru = StaticDetector(cfg.staticWindowSec, cfg.staticGyroStdThr, cfg.staticAccelStdThr)
    private val yawUnc = YawUncertainty(cfg.gyroNoiseDensity)
    private val verticalPitchRad = AngleMath.rad(cfg.verticalPitchDeg)

    private var filter: AttitudeFilter? = null
    private var biasKf: BiasKalman? = null
    private var yawRef = 0.0
    private var initTNs = 0L
    private var initNotStatic = false
    private var lastTNs = 0L
    private var lastGapNs: Long? = null
    private var occlusion = OcclusionState.OK
    private var occlusionSinceNs: Long? = null
    private var lastVisionAcceptedNs: Long? = null
    private var lastVisionRateRadS: Double? = null
    private var lastVisionRateTNs = 0L
    private var lastOutput: OrientationOutput? = null

    val isInitialized get() = filter != null
    val bias: Vec3 get() = biasKf?.bias ?: Vec3.ZERO
    val zruUpdates get() = zruCount
    private var zruCount = 0

    fun onImu(s: ImuSample): OrientationOutput {
        gyroHistory.add(s.tNs, s.gyroRaw)
        val f = filter
        if (f == null) {
            val r = initializer.add(s) ?: return uninitialized(s.tNs)
            filter = AttitudeFilter(cfg, r.qWD, r.gLocal).also { it.seed(s) }
            biasKf = BiasKalman(r.bias, r.biasVar, cfg.biasRandomWalk, cfg.chi2Gate3)
            yawRef = r.yawRefRad
            initTNs = s.tNs
            initNotStatic = !r.wasStatic
            lastTNs = s.tNs
            return output(s.tNs).also { lastOutput = it }
        }
        val kf = biasKf!!
        val dt = f.step(s, kf.bias)
        if (dt == 0.0) return lastOutput ?: output(s.tNs)
        lastTNs = s.tNs
        kf.predict(dt)
        var extraVar = 0.0
        if (dt > cfg.maxGapSec) {
            lastGapNs = s.tNs
            extraVar = cfg.gyroNoiseDensity * cfg.gyroNoiseDensity * dt
        }
        zeroRateUpdate(s, kf)
        val up = f.qWD.conj().rotate(Vec3.EZ)
        yawUnc.step(dt, kf.verticalVariance(up), extraVar)
        return output(s.tNs).also { lastOutput = it }
    }

    private fun zeroRateUpdate(s: ImuSample, kf: BiasKalman) {
        zru.add(s)
        if (!zru.isStatic()) return
        val mean = zru.meanGyro()
        val visionRecent = lastVisionRateRadS != null && s.tNs - lastVisionRateTNs < 2_000_000_000L
        val visionAgrees = !visionRecent || occlusion != OcclusionState.OK || lastVisionRateRadS!! < cfg.zruMaxVisionRateRadS
        if ((mean - kf.bias).norm() < cfg.zruMaxRateRadS && visionAgrees) {
            val r = cfg.gyroNoiseDensity * cfg.gyroNoiseDensity / zru.windowDurationSec().coerceAtLeast(1e-3)
            if (kf.update(mean, r).second) zruCount++
        }
        zru.reset()
    }

    /** Applies a camera window measurement (Architecture §6.7). */
    fun onVision(m: VisionMeasurement): VisionUpdateOutcome {
        val f = filter
        val kf = biasKf
        if (f == null || kf == null) return VisionUpdateOutcome(false, VisionRejectReason.NOT_INITIALIZED)
        if (occlusion != OcclusionState.OK) return VisionUpdateOutcome(false, VisionRejectReason.BLOCKED)
        if (!m.rotD1D2.isFinite() || m.t2Ns <= m.t1Ns) return VisionUpdateOutcome(false, VisionRejectReason.NOT_FINITE)
        val dt = (m.t2Ns - m.t1Ns) * 1e-9
        val raw = gyroHistory.integrateWithBiasJacobian(m.t1Ns, m.t2Ns, Vec3.ZERO)
            ?: return VisionUpdateOutcome(false, VisionRejectReason.NO_GYRO_COVERAGE)
        val rotRaw = raw.rotD1D2
        val rotCorr = gyroHistory.integrate(m.t1Ns, m.t2Ns, kf.bias)!!
        lastVisionRateRadS = m.rotD1D2.log().norm() / dt
        lastVisionRateTNs = lastTNs
        val meanRate = rotRaw.log().norm() / dt
        if (meanRate > cfg.maxRateForVisionRadS) return VisionUpdateOutcome(false, VisionRejectReason.TOO_FAST)
        if (m.quality <= 0.0) return VisionUpdateOutcome(false, VisionRejectReason.LOW_QUALITY)
        // e = Log(rotVisᵀ·rotRaw) ≈ H·b  →  b_meas = H⁻¹·e, Cov = σ²·H⁻¹H⁻ᵀ (diagonal kept).
        val hInv = raw.biasJacobian.inverse() ?: return VisionUpdateOutcome(false, VisionRejectReason.NOT_FINITE)
        val zBias = hInv * (m.rotD1D2.conj() * rotRaw).log()
        val sigmaVis2 = (cfg.sigmaRotRad.pow(2) + (meanRate * cfg.sigmaTimeOffsetSec).pow(2)) / m.quality.coerceAtLeast(1e-3)
        val r = Vec3(hInv.row(0).let { it dot it }, hInv.row(1).let { it dot it }, hInv.row(2).let { it dot it }) * sigmaVis2
        val (d2, ok) = kf.update(zBias, r)
        if (!ok) return VisionUpdateOutcome(false, VisionRejectReason.GATE, d2, zBias)
        val e = (m.rotD1D2.conj() * rotCorr).log()
        f.applyCorrection(Quaternion.exp(e * -cfg.visionAttitudeGain))
        yawUnc.onVisionAccepted(sigmaVis2)
        lastVisionAcceptedNs = maxOf(m.t2Ns, lastTNs)
        return VisionUpdateOutcome(true, null, d2, zBias)
    }

    /** Camera state from the vision thread (posted to the imu thread). */
    fun setVisionState(state: OcclusionState, nowNs: Long) {
        if (state == occlusion) return
        occlusion = state
        occlusionSinceNs = if (state == OcclusionState.OK) null else nowNs
    }

    /** Sets the current heading as yaw 0. */
    fun resetYaw() {
        val f = filter ?: return
        yawRef = AngleConverter.yawRaw(Frames.qWOFromDevice(f.qWD))
        yawUnc.reset()
        lastOutput = output(lastTNs)
    }

    private fun uninitialized(tNs: Long) = OrientationOutput(
        tNs, false, Quaternion.IDENTITY, AttitudeAngles.ZERO, Vec3.ZERO, 0.0, 0.0, Confidence.LOW,
        setOf(OrientationWarning.INITIALIZING), null,
    )

    private fun output(tNs: Long): OrientationOutput {
        val f = filter!!
        val kf = biasKf!!
        val angles = AngleConverter.toAngles(Frames.qWOFromDevice(f.qWD), yawRef, verticalPitchRad)
        val up = f.qWD.conj().rotate(Vec3.EZ)
        val drift = sqrt(kf.verticalVariance(up))
        val w = mutableSetOf<OrientationWarning>()
        if (angles.isVertical) w += OrientationWarning.VERTICAL
        if (initNotStatic && tNs - initTNs < 10_000_000_000L) w += OrientationWarning.INIT_NOT_STATIC
        if (f.lastAccelWeight < 0.5) w += OrientationWarning.ACCEL_DISTURBED
        lastGapNs?.let { if (tNs - it < 1_000_000_000L) w += OrientationWarning.SENSOR_GAP }
        if (occlusion == OcclusionState.BLOCKED) w += OrientationWarning.CAMERA_BLOCKED
        if (occlusion == OcclusionState.LOW_TEXTURE) w += OrientationWarning.LOW_TEXTURE
        if (f.lastRateRadS > cfg.maxRateForVisionRadS) w += OrientationWarning.MOVING_TOO_FAST
        val visionAge = lastVisionAcceptedNs?.let { (tNs - it).coerceAtLeast(0L) * 1e-9 }
        val blockedFor = occlusionSinceNs?.let { (tNs - it).coerceAtLeast(0L) * 1e-9 } ?: 0.0
        val conf = ConfidenceModel.level(visionAge, AngleMath.radPerSecToDegPerMin(drift), w, blockedFor, cfg)
        return OrientationOutput(tNs, true, f.qWD, angles, kf.bias, yawUnc.sigmaRad, drift, conf, w, lastVisionAcceptedNs)
    }
}
