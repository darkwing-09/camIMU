package com.camimu.orient.calibration

import android.content.Context
import android.util.Log
import com.camimu.orient.core.config.CalibrationData
import com.camimu.orient.core.config.ConfigJson
import java.io.File

/** Loads the per-device calibration file pushed from the laptop (docs/DataModel.md §4). */
class CalibrationStore(context: Context) {
    val file: File = File(context.getExternalFilesDir(null), FILE_NAME)

    data class Loaded(val data: CalibrationData, val status: String)

    fun load(): Loaded {
        if (!file.exists()) return Loaded(CalibrationData(), "no calibration.json (using defaults + FOV estimate)")
        return try {
            val d = ConfigJson.decodeCalibration(file.readText())
            Loaded(d, "loaded ${file.name}: intrinsics=${d.intrinsics?.source ?: "none"}, t_d=${d.timeOffsetNs / 1e6} ms")
        } catch (e: Exception) {
            Log.w(TAG, "bad calibration file", e)
            Loaded(CalibrationData(), "calibration.json invalid (${e.message?.take(80)}); using defaults")
        }
    }

    companion object {
        private const val TAG = "CamIMU.Calib"
        const val FILE_NAME = "calibration.json"
    }
}
