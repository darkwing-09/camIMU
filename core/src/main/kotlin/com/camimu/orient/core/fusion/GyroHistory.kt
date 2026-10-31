package com.camimu.orient.core.fusion

import com.camimu.orient.core.math.Mat3
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3

/**
 * Thread-safe ring buffer of raw gyro samples (written by the imu thread, read by the vision thread).
 * ω(t) is treated as piecewise linear between samples.
 */
class GyroHistory(private val capacity: Int = 512) {
    private val times = LongArray(capacity)
    private val omegas = DoubleArray(capacity * 3)
    private var next = 0
    private var count = 0

    init {
        require(capacity >= 2)
    }

    @Synchronized
    fun add(tNs: Long, omegaRaw: Vec3) {
        if (count > 0 && tNs <= timeAt(count - 1)) return // out of order / duplicate
        times[next] = tNs
        omegas[next * 3] = omegaRaw.x
        omegas[next * 3 + 1] = omegaRaw.y
        omegas[next * 3 + 2] = omegaRaw.z
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized
    fun latestTimeNs(): Long? = if (count == 0) null else timeAt(count - 1)

    @Synchronized
    fun oldestTimeNs(): Long? = if (count == 0) null else timeAt(0)

    @Synchronized
    fun clear() {
        count = 0
        next = 0
    }

    /**
     * Rotation qD1D2 (device at [t2Ns] → device at [t1Ns]) from integrating (ω_raw − [bias]) over [t1, t2].
     * Null if the interval is not covered by the buffer.
     */
    @Synchronized
    fun integrate(t1Ns: Long, t2Ns: Long, bias: Vec3): Quaternion? = integrateImpl(t1Ns, t2Ns, bias, null)

    /** Result of [integrateWithBiasJacobian]. */
    class WithJacobian(val rotD1D2: Quaternion, val biasJacobian: Mat3)

    /**
     * Like [integrate], plus the bias Jacobian H = Σ_k R(D_k→D_2)ᵀ·dt_k, so that integrating ω_raw instead of
     * ω_true gives rot ≈ rotTrue · Exp(H·b) to first order in b (exact for rotation inside the window).
     */
    @Synchronized
    fun integrateWithBiasJacobian(t1Ns: Long, t2Ns: Long, bias: Vec3): WithJacobian? {
        val segs = ArrayList<Pair<Quaternion, Double>>()
        val q = integrateImpl(t1Ns, t2Ns, bias, segs) ?: return null
        var tail = Quaternion.IDENTITY // rotation from the end of the current segment to t2
        var h = Mat3.diag(0.0, 0.0, 0.0)
        for (k in segs.indices.reversed()) {
            h = h + tail.toMatrix().transpose() * segs[k].second
            tail = segs[k].first * tail
        }
        return WithJacobian(q, h)
    }

    private fun integrateImpl(t1Ns: Long, t2Ns: Long, bias: Vec3, segments: MutableList<Pair<Quaternion, Double>>?): Quaternion? {
        require(t1Ns <= t2Ns) { "t1 must be <= t2" }
        if (count < 2 || t1Ns < timeAt(0) || t2Ns > timeAt(count - 1)) return null
        if (t1Ns == t2Ns) return Quaternion.IDENTITY
        var i = upperBound(t1Ns) // first logical index with time > t1
        var ta = t1Ns
        var wa = omegaAtTime(t1Ns)
        var q = Quaternion.IDENTITY
        while (true) {
            val tb: Long
            val wb: Vec3
            if (i < count && timeAt(i) < t2Ns) {
                tb = timeAt(i); wb = omegaAt(i)
            } else {
                tb = t2Ns; wb = omegaAtTime(t2Ns)
            }
            val dt = (tb - ta) * 1e-9
            val step = Quaternion.exp(((wa + wb) * 0.5 - bias) * dt)
            segments?.add(step to dt)
            q = q * step
            if (tb == t2Ns) break
            ta = tb; wa = wb; i++
        }
        return q.normalized()
    }

    private fun phys(k: Int) = ((next - count + k) % capacity + capacity) % capacity
    private fun timeAt(k: Int) = times[phys(k)]
    private fun omegaAt(k: Int): Vec3 {
        val p = phys(k) * 3
        return Vec3(omegas[p], omegas[p + 1], omegas[p + 2])
    }

    /** First logical index whose time is > t (count if none). */
    private fun upperBound(t: Long): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timeAt(mid) <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Linear interpolation of ω at time t (requires coverage). */
    private fun omegaAtTime(t: Long): Vec3 {
        val j = upperBound(t)
        if (j == 0) return omegaAt(0)
        if (j >= count) return omegaAt(count - 1)
        val t0 = timeAt(j - 1); val t1 = timeAt(j)
        val a = (t - t0).toDouble() / (t1 - t0).toDouble()
        val w0 = omegaAt(j - 1); val w1 = omegaAt(j)
        return w0 + (w1 - w0) * a
    }
}
