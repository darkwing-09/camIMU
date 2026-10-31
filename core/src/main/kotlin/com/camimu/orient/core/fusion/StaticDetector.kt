package com.camimu.orient.core.fusion

import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.ImuSample
import kotlin.math.sqrt

/** Sliding-window stillness detector on per-axis gyro and accel standard deviations. */
class StaticDetector(
    private val windowSec: Double,
    private val gyroStdThr: Double,
    private val accelStdThr: Double,
) {
    private val window = ArrayDeque<ImuSample>()

    fun add(s: ImuSample) {
        window.addLast(s)
        val windowNs = (windowSec * 1e9).toLong()
        while (window.size > 2 && s.tNs - window.first().tNs > windowNs) window.removeFirst()
    }

    fun windowDurationSec(): Double = if (window.size < 2) 0.0 else (window.last().tNs - window.first().tNs) * 1e-9

    fun isStatic(): Boolean {
        if (window.size < 5 || windowDurationSec() < windowSec * 0.95) return false
        val g = std { it.gyroRaw }
        val a = std { it.accel }
        return maxOf(g.x, g.y, g.z) < gyroStdThr && maxOf(a.x, a.y, a.z) < accelStdThr
    }

    fun meanGyro(): Vec3 = mean { it.gyroRaw }
    fun meanAccel(): Vec3 = mean { it.accel }
    fun reset() = window.clear()

    private inline fun mean(sel: (ImuSample) -> Vec3): Vec3 {
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (s in window) { val v = sel(s); sx += v.x; sy += v.y; sz += v.z }
        val n = window.size.coerceAtLeast(1).toDouble()
        return Vec3(sx / n, sy / n, sz / n)
    }

    private inline fun std(sel: (ImuSample) -> Vec3): Vec3 {
        val m = mean(sel)
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (s in window) {
            val d = sel(s) - m
            sx += d.x * d.x; sy += d.y * d.y; sz += d.z * d.z
        }
        val n = window.size.coerceAtLeast(1).toDouble()
        return Vec3(sqrt(sx / n), sqrt(sy / n), sqrt(sz / n))
    }
}
