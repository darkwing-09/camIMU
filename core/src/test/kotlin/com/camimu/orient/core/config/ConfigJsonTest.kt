package com.camimu.orient.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigJsonTest {
    @Test fun roundTrip() {
        assertEquals(EstimatorConfig(), ConfigJson.decodeEstimator(ConfigJson.encode(EstimatorConfig())))
        assertEquals(VisionConfig(), ConfigJson.decodeVision(ConfigJson.encode(VisionConfig())))
        val c = CalibrationData(intrinsics = CameraIntrinsics(640, 480, 500.0, 500.0, 320.0, 240.0, k1 = -0.1))
        assertEquals(c, ConfigJson.decodeCalibration(ConfigJson.encode(c)))
    }

    @Test fun emptyAndUnknownKeys() {
        assertEquals(EstimatorConfig(), ConfigJson.decodeEstimator("{}"))
        assertEquals(0.2, ConfigJson.decodeEstimator("""{"kTilt":0.2,"bogus":1}""").kTilt, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidThrows() { ConfigJson.decodeEstimator("""{"visionAttitudeGain":2.0}""") }

    @Test fun intrinsicsScale() {
        val k = CameraIntrinsics(1280, 960, 1000.0, 1000.0, 640.0, 480.0)
        assertEquals(500.0, k.scaledTo(640, 480)!!.fx, 1e-12)
        assertEquals(null, k.scaledTo(640, 360))
    }
}
