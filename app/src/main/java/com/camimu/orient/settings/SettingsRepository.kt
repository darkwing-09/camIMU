package com.camimu.orient.settings

import android.content.Context
import android.util.Log
import com.camimu.orient.core.config.ConfigJson
import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.config.VisionConfig

/** Persists estimator/vision parameters as JSON in SharedPreferences. Applied at the next session start. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("camimu_settings", Context.MODE_PRIVATE)

    fun estimator(): EstimatorConfig = read(KEY_EST, EstimatorConfig()) { ConfigJson.decodeEstimator(it) }
    fun vision(): VisionConfig = read(KEY_VIS, VisionConfig()) { ConfigJson.decodeVision(it) }

    fun save(e: EstimatorConfig, v: VisionConfig) {
        prefs.edit().putString(KEY_EST, ConfigJson.encode(e)).putString(KEY_VIS, ConfigJson.encode(v)).apply()
    }

    fun resetToDefaults() = prefs.edit().clear().apply()

    private fun <T> read(key: String, default: T, decode: (String) -> T): T {
        val s = prefs.getString(key, null) ?: return default
        return try {
            decode(s)
        } catch (e: Exception) {
            Log.w("CamIMU.Settings", "invalid stored $key, using defaults", e)
            default
        }
    }

    companion object {
        private const val KEY_EST = "estimator"
        private const val KEY_VIS = "vision"
    }
}
