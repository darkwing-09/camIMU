package com.camimu.orient.camera

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

/** Static camera properties needed by the estimator (read via Camera2 interop). */
data class CameraInfoData(
    val sensorOrientationDeg: Int,
    val timestampRealtime: Boolean,
    val focalLengthMm: Double?,
    val sensorWidthMm: Double?,
    val oisAvailable: Boolean,
    val eisAvailable: Boolean,
) {
    val timestampSourceName get() = if (timestampRealtime) "REALTIME" else "UNKNOWN"
}

object CameraInfoReader {
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun read(info: CameraInfo): CameraInfoData {
        val c = Camera2CameraInfo.from(info)
        val orientation = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val ts = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val size = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val ois = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val eis = c.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        return CameraInfoData(
            sensorOrientationDeg = orientation,
            timestampRealtime = ts == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            focalLengthMm = focal?.toDouble(),
            sensorWidthMm = size?.width?.toDouble(),
            oisAvailable = ois?.any { it != CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF } == true,
            eisAvailable = eis?.any { it != CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF } == true,
        )
    }
}
