package com.camimu.orient.core.config

import kotlinx.serialization.Serializable

/** Estimator parameters. Meaning and units: docs/DataModel.md §2. */
@Serializable
data class EstimatorConfig(
    val imuSamplingPeriodUs: Int = 5000,
    val maxAccelAgeNs: Long = 50_000_000L,
    val staticInitSec: Double = 2.0,
    val initTimeoutSec: Double = 5.0,
    val staticWindowSec: Double = 0.5,
    val staticGyroStdThr: Double = 0.01,
    val staticAccelStdThr: Double = 0.08,
    val zruMaxRateRadS: Double = 0.05,
    val zruMaxVisionRateRadS: Double = 0.0035,
    val initBiasSigma: Double = 0.01,
    val gyroNoiseDensity: Double = 1.5e-4,
    val biasRandomWalk: Double = 2.0e-5,
    val kTilt: Double = 0.5,
    val accelGateMs2: Double = 1.0,
    val maxRateForTiltRadS: Double = 2.0,
    val maxGapSec: Double = 0.05,
    val chi2Gate3: Double = 11.34,
    val sigmaRotRad: Double = 3.0e-4,
    val sigmaTimeOffsetSec: Double = 0.002,
    val visionAttitudeGain: Double = 0.5,
    val maxRateForVisionRadS: Double = 3.0,
    val verticalPitchDeg: Double = 80.0,
    val highVisionAgeSec: Double = 1.5,
    val mediumVisionAgeSec: Double = 5.0,
    val highDriftDegPerMin: Double = 1.0,
    val mediumDriftDegPerMin: Double = 3.0,
    val blockedHoldSec: Double = 3.0,
    val uiPublishHz: Double = 60.0,
    val estLogDecimation: Int = 1,
    val loggerFlushMs: Long = 1000L,
) {
    init {
        require(imuSamplingPeriodUs > 0)
        require(staticInitSec > 0 && initTimeoutSec >= staticInitSec)
        require(staticWindowSec > 0)
        require(gyroNoiseDensity > 0 && biasRandomWalk >= 0)
        require(kTilt >= 0) { "kTilt must be >= 0" }
        require(accelGateMs2 > 0)
        require(visionAttitudeGain > 0 && visionAttitudeGain <= 1) { "visionAttitudeGain must be in (0, 1]" }
        require(sigmaRotRad > 0)
        require(verticalPitchDeg in 45.0..89.9)
        require(uiPublishHz > 0 && estLogDecimation >= 1 && loggerFlushMs > 0)
    }
}
