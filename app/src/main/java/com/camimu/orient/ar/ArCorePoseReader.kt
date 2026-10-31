package com.camimu.orient.ar

import android.os.SystemClock
import com.camimu.orient.core.frames.AngleConverter
import com.camimu.orient.core.frames.Frames
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.model.ArCoreReading
import com.camimu.orient.core.model.ArTracking
import com.camimu.orient.core.model.AttitudeAngles
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState

/** ARCore displayOrientedPose → our frames and angles. GL thread. */
class ArCorePoseReader(private val verticalPitchRad: Double) {
    @Volatile private var resetRequested = true
    private var yawRefRad = 0.0
    private var lastQ = Quaternion.IDENTITY
    private var lastAngles = AttitudeAngles.ZERO
    private val xyzw = FloatArray(4)

    fun requestResetYaw() {
        resetRequested = true
    }

    fun read(frame: Frame): ArCoreReading {
        val cam = frame.camera
        val tracking = when (cam.trackingState) {
            TrackingState.TRACKING -> ArTracking.TRACKING
            TrackingState.PAUSED -> ArTracking.PAUSED
            else -> ArTracking.STOPPED
        }
        if (tracking == ArTracking.TRACKING) {
            cam.displayOrientedPose.getRotationQuaternion(xyzw, 0)
            lastQ = Frames.qWOFromArCore(xyzw)
            if (resetRequested) {
                yawRefRad = AngleConverter.yawRaw(lastQ)
                resetRequested = false
            }
            lastAngles = AngleConverter.toAngles(lastQ, yawRefRad, verticalPitchRad)
        }
        return ArCoreReading(
            SystemClock.elapsedRealtimeNanos(), frame.timestamp, lastQ, tracking, cam.trackingFailureReason.name, lastAngles,
        )
    }
}
