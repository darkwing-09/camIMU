package com.camimu.orient.core.config

import com.camimu.orient.core.io.SessionMeta
import kotlinx.serialization.json.Json

/** JSON (de)serialization shared by the app, logs and replay. */
object ConfigJson {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(c: EstimatorConfig): String = json.encodeToString(EstimatorConfig.serializer(), c)
    fun encode(c: VisionConfig): String = json.encodeToString(VisionConfig.serializer(), c)
    fun encode(c: CalibrationData): String = json.encodeToString(CalibrationData.serializer(), c)
    fun decodeEstimator(s: String): EstimatorConfig = json.decodeFromString(EstimatorConfig.serializer(), s)
    fun decodeVision(s: String): VisionConfig = json.decodeFromString(VisionConfig.serializer(), s)
    fun encode(m: SessionMeta): String = json.encodeToString(SessionMeta.serializer(), m)
    fun decodeMeta(s: String): SessionMeta = json.decodeFromString(SessionMeta.serializer(), s)
    fun decodeCalibration(s: String): CalibrationData = json.decodeFromString(CalibrationData.serializer(), s)
}
