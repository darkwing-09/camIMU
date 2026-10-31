package com.camimu.orient.core.io

import com.camimu.orient.core.config.CalibrationData
import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.config.VisionConfig
import kotlinx.serialization.Serializable

/** meta.json contents (docs/DataModel.md §5). */
@Serializable
data class SessionMeta(
    val appVersion: String = "",
    val coreVersion: String = "",
    val deviceManufacturer: String = "",
    val deviceModel: String = "",
    val androidSdk: Int = 0,
    val startWallClockIso: String = "",
    val mode: String = "",
    val estimatorConfig: EstimatorConfig = EstimatorConfig(),
    val visionConfig: VisionConfig = VisionConfig(),
    val calibration: CalibrationData = CalibrationData(),
    val registeredSensorTypes: List<Int> = emptyList(),
    val sensorNames: List<String> = emptyList(),
    val sensorClock: String = "UNKNOWN",
    val measuredGyroHz: Double = 0.0,
    val measuredAccelHz: Double = 0.0,
    val cameraTimestampSource: String = "",
    val sensorOrientationDeg: Int = 0,
    val rCD: List<Double> = emptyList(),
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val intrinsicsSource: String = "",
    val droppedRows: Long = 0,
    val notes: Map<String, String> = emptyMap(),
)
