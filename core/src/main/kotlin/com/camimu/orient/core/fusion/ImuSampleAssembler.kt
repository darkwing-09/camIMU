package com.camimu.orient.core.fusion

import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.ImuSample

/** Merges separate gyro and accel events: one [ImuSample] per gyro event with the latest accel. */
class ImuSampleAssembler(private val maxAccelAgeNs: Long = 50_000_000L) {
    private var accel: Vec3? = null
    private var accelTNs = 0L

    fun onAccel(tNs: Long, accel: Vec3) {
        this.accel = accel
        accelTNs = tNs
    }

    /** @return null until the first accel event has arrived. */
    fun onGyro(tNs: Long, gyroRaw: Vec3, osBias: Vec3?): ImuSample? {
        val a = accel ?: return null
        val age = tNs - accelTNs
        return ImuSample(tNs, gyroRaw, a, accelValid = age in -maxAccelAgeNs..maxAccelAgeNs, accelAgeNs = age, osBias = osBias)
    }
}
