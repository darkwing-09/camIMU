package com.camimu.orient.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.camimu.orient.core.fusion.ImuSampleAssembler
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.ImuSample

/**
 * Gyroscope (uncalibrated preferred) + accelerometer capture on a dedicated "imu" HandlerThread.
 * Only these two sensor types are ever registered (compass-free by construction).
 */
class ImuSource(context: Context, maxAccelAgeNs: Long, private val onSample: (ImuSample) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val thread = HandlerThread("imu", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    val handler = Handler(thread.looper)
    private val assembler = ImuSampleAssembler(maxAccelAgeNs)
    val timeSync = TimeSync()
    val registeredTypes = mutableListOf<Int>()
    val sensorNames = mutableListOf<String>()
    val gyroRate = RateMeter()
    val accelRate = RateMeter()

    /** @return false if the phone lacks a gyroscope or accelerometer. */
    fun start(samplingPeriodUs: Int): Boolean {
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) ?: sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gyro == null || acc == null) {
            Log.e(TAG, "missing sensor: gyro=$gyro accel=$acc")
            return false
        }
        for (s in listOf(gyro, acc)) {
            sm.registerListener(this, s, samplingPeriodUs, handler)
            registeredTypes += s.type
            sensorNames += "${s.stringType} | ${s.name} | ${s.vendor}"
        }
        Log.i(TAG, "registered ${sensorNames.joinToString()}")
        return true
    }

    override fun onSensorChanged(e: SensorEvent) {
        val t = timeSync.toElapsedRealtimeNs(e.timestamp)
        val v = e.values // reused by the framework: copy now
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelRate.tick(t)
                assembler.onAccel(t, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
            }
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                gyroRate.tick(t)
                assembler.onGyro(
                    t, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()),
                    Vec3(v[3].toDouble(), v[4].toDouble(), v[5].toDouble()),
                )?.let(onSample)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroRate.tick(t)
                assembler.onGyro(t, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()), null)?.let(onSample)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    fun quit() {
        sm.unregisterListener(this)
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "CamIMU.Imu"
    }
}
