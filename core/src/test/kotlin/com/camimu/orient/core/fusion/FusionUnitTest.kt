package com.camimu.orient.core.fusion

import com.camimu.orient.core.assertVec
import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.frames.AngleConverter
import com.camimu.orient.core.frames.Frames
import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.math.Mat3
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.Confidence
import com.camimu.orient.core.model.ImuSample
import com.camimu.orient.core.model.OrientationWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/** Portrait upright, camera along +X_W. */
val Q_UPRIGHT: Quaternion = Quaternion.fromMatrix(Mat3.of(0.0, 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0))
const val T0 = 1_000_000_000_000L

class ImuSampleAssemblerTest {
    @Test fun nullBeforeAccelAndStaleFlag() {
        val a = ImuSampleAssembler(50_000_000L)
        assertNull(a.onGyro(T0, Vec3.ZERO, null))
        a.onAccel(T0, Vec3(0.0, 9.81, 0.0))
        assertTrue(a.onGyro(T0 + 5_000_000, Vec3.ZERO, null)!!.accelValid)
        assertFalse(a.onGyro(T0 + 60_000_000, Vec3.ZERO, null)!!.accelValid)
    }
}

class GyroHistoryTest {
    private fun filled(w: Vec3, n: Int = 400, cap: Int = 512): GyroHistory {
        val h = GyroHistory(cap)
        for (i in 0 until n) h.add(T0 + i * 5_000_000L, w)
        return h
    }

    @Test fun constantRateIntegratesExactly() {
        val h = filled(Vec3(0.0, 0.0, 0.5))
        val q = h.integrate(T0 + 250_000_000L, T0 + 1_250_000_000L, Vec3.ZERO)!!
        assertVec(Vec3(0.0, 0.0, 0.5), q.log(), 1e-9)
        val qOff = h.integrate(T0 + 252_345_678L, T0 + 1_001_000_001L, Vec3.ZERO)!!
        assertEquals(0.5 * (1_001_000_001L - 252_345_678L) * 1e-9, qOff.log().z, 1e-9)
    }

    @Test fun biasCancels() {
        val h = filled(Vec3(0.0, 0.0, 0.5))
        assertEquals(0.0, h.integrate(T0, T0 + 1_000_000_000L, Vec3(0.0, 0.0, 0.5))!!.log().norm(), 1e-12)
    }

    @Test fun outOfRangeAndRingWrap() {
        val h = filled(Vec3.EX, n = 600, cap = 512)
        assertNull(h.integrate(T0, T0 + 100_000_000L, Vec3.ZERO)) // overwritten
        assertEquals(T0 + 88 * 5_000_000L, h.oldestTimeNs())
        assertNull(h.integrate(T0 + 1_000_000_000L, T0 + 10_000_000_000L, Vec3.ZERO))
    }
}

class StaticAndInitTest {
    private val cfg = EstimatorConfig()

    private fun staticSamples(accel: Vec3, bias: Vec3, seconds: Double, seed: Long = 1): List<ImuSample> {
        val rnd = Random(seed)
        return (0 until (seconds * 200).toInt()).map {
            ImuSample(T0 + it * 5_000_000L, bias + Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * 0.002, accel)
        }
    }

    private fun init(samples: List<ImuSample>): InitResult {
        val ini = AttitudeInitializer(cfg)
        for (s in samples) ini.add(s)?.let { return it }
        error("not initialized")
    }

    @Test fun recoversBias() {
        val b = Vec3(0.01, -0.02, 0.005)
        val r = init(staticSamples(Vec3(0.0, 9.81, 0.0), b, 3.0))
        assertTrue(r.wasStatic)
        assertVec(b, r.bias, 1e-3)
    }

    @Test fun uprightIsZeroAngles() {
        val r = init(staticSamples(Vec3(0.0, 9.81, 0.0), Vec3.ZERO, 3.0))
        val a = AngleConverter.toAngles(Frames.qWOFromDevice(r.qWD), r.yawRefRad, AngleMath.rad(80.0))
        assertEquals(0.0, a.pitchDeg, 1e-6); assertEquals(0.0, a.rollDeg, 1e-6); assertEquals(0.0, a.yawDeg, 1e-6)
    }

    @Test fun flatOnTableLooksDown() {
        val r = init(staticSamples(Vec3(0.0, 0.0, 9.81), Vec3.ZERO, 3.0))
        val a = AngleConverter.toAngles(Frames.qWOFromDevice(r.qWD), r.yawRefRad, AngleMath.rad(80.0))
        assertEquals(-90.0, a.pitchDeg, 1e-6)
        assertTrue(a.isVertical)
    }

    @Test fun tiltedUp20() {
        val t = AngleMath.rad(20.0)
        // Camera pitched up 20°: world up seen in device frame tilts toward +z_D... derive from truth instead.
        val q = AngleConverter.fromAngles(0.0, t, 0.0) * Frames.Q_DO.conj()
        val up = q.conj().rotate(Vec3.EZ) * 9.81
        val r = init(staticSamples(up, Vec3.ZERO, 3.0))
        val a = AngleConverter.toAngles(Frames.qWOFromDevice(r.qWD), r.yawRefRad, AngleMath.rad(80.0))
        assertEquals(20.0, a.pitchDeg, 1e-6)
    }

    @Test fun movingTimesOut() {
        val ini = AttitudeInitializer(cfg)
        var res: InitResult? = null
        var i = 0
        while (res == null && i < 2000) {
            val w = Vec3(0.0, kotlin.math.sin(i * 0.05), 0.0)
            res = ini.add(ImuSample(T0 + i * 5_000_000L, w, Vec3(0.0, 9.81, 0.0)))
            i++
        }
        assertFalse(res!!.wasStatic)
        assertEquals(cfg.initTimeoutSec, (res.tNs - T0) * 1e-9, 0.01)
    }
}

class AttitudeFilterTest {
    private val cfg = EstimatorConfig()
    private val g = 9.81

    private fun upAccel(q: Quaternion) = q.conj().rotate(Vec3.EZ) * g

    @Test fun gravityCannotChangeYaw() {
        val f = AttitudeFilter(cfg, Q_UPRIGHT, g)
        val w = Vec3(0.0, 0.5, 0.0) // about y_D = world up when upright
        var truth = Q_UPRIGHT
        for (i in 0..800) {
            if (i > 0) truth = truth * Quaternion.exp(w * 0.005)
            f.step(ImuSample(T0 + i * 5_000_000L, w, upAccel(truth)), Vec3.ZERO)
        }
        val yaw = AngleConverter.toAngles(Frames.qWOFromDevice(f.qWD), 0.0, 1.4).yawRad
        assertEquals(AngleMath.wrapPi(2.0), yaw, 1e-6)
    }

    @Test fun tiltErrorConverges() {
        val start = Q_UPRIGHT * Quaternion.exp(Vec3(AngleMath.rad(10.0), 0.0, 0.0))
        val f = AttitudeFilter(cfg, start, g)
        val n = (5 / cfg.kTilt / 0.005).toInt()
        for (i in 0..n) f.step(ImuSample(T0 + i * 5_000_000L, Vec3.ZERO, upAccel(Q_UPRIGHT)), Vec3.ZERO)
        val upErr = AngleMath.deg(kotlin.math.acos((f.qWD.rotate(upAccel(Q_UPRIGHT) / g) dot Vec3.EZ).coerceIn(-1.0, 1.0)))
        assertTrue("tilt error $upErr", upErr < 0.5)
    }

    @Test fun accelGateIgnoresLinearAcceleration() {
        val f = AttitudeFilter(cfg, Q_UPRIGHT, g)
        f.step(ImuSample(T0, Vec3.ZERO, Vec3(3.0, 9.81, 0.0) * 1.3), Vec3.ZERO)
        f.step(ImuSample(T0 + 5_000_000, Vec3.ZERO, Vec3(3.0, 9.81, 0.0) * 1.3), Vec3.ZERO)
        assertEquals(0.0, f.lastAccelWeight, 0.0)
        assertEquals(0.0, f.qWD.angleTo(Q_UPRIGHT), 1e-12)
    }

    @Test fun realDtIsUsed() {
        val rnd = Random(5)
        val w = Vec3(0.0, 0.3, 0.0)
        val a = AttitudeFilter(cfg.copy(kTilt = 0.0), Q_UPRIGHT, g)
        var t = T0
        while (t < T0 + 2_000_000_000L) {
            a.step(ImuSample(t, w, upAccel(Q_UPRIGHT)), Vec3.ZERO)
            t += 3_000_000L + rnd.nextInt(4_000_000)
        }
        a.step(ImuSample(T0 + 2_000_000_000L, w, upAccel(Q_UPRIGHT)), Vec3.ZERO)
        assertEquals(0.6, a.qWD.angleTo(Q_UPRIGHT), 1e-6)
    }

    @Test fun duplicateTimestampDropped() {
        val f = AttitudeFilter(cfg, Q_UPRIGHT, g)
        val s = ImuSample(T0, Vec3.EX, upAccel(Q_UPRIGHT))
        f.step(s, Vec3.ZERO)
        assertEquals(0.0, f.step(s, Vec3.ZERO), 0.0)
        assertTrue(f.qWD.isFinite())
    }
}

class BiasKalmanAndConfidenceTest {
    @Test fun convergesAndGates() {
        val rnd = Random(9)
        val truth = Vec3(0.01, -0.02, 0.003)
        val kf = BiasKalman(Vec3.ZERO, 1e-3, 1e-5, 11.34)
        var lastP = kf.variance.x
        repeat(100) {
            kf.predict(0.01)
            kf.update(truth + Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * 0.005, 0.005 * 0.005)
            assertTrue(kf.variance.x <= lastP + 1e-12); lastP = kf.variance.x
        }
        assertVec(truth, kf.bias, 3 * sqrt(kf.variance.x) + 1e-4)
        val before = kf.bias
        assertFalse(kf.update(truth + Vec3(1.0, 0.0, 0.0), 0.005 * 0.005).second)
        assertEquals(before, kf.bias)
    }

    @Test fun predictGrowsLinearly() {
        val kf = BiasKalman(Vec3.ZERO, 0.0, 0.1, 11.34)
        kf.predict(2.0)
        assertEquals(0.02, kf.variance.z, 1e-15)
    }

    @Test fun yawUncertaintyFormulas() {
        val y = YawUncertainty(1e-3)
        repeat(10000) { y.step(0.01, 0.0) }
        assertEquals(1e-3 * 10.0, y.sigmaRad, 1e-9)
        val y2 = YawUncertainty(1e-3)
        repeat(1000) { y2.step(0.01, 1e-6) }
        assertEquals(sqrt(1e-6 * 10 + 1e-6 * 100), y2.sigmaRad, 1e-4)
    }

    @Test fun confidenceTruthTable() {
        val c = EstimatorConfig()
        val none = emptySet<OrientationWarning>()
        assertEquals(Confidence.HIGH, ConfidenceModel.level(0.5, 0.5, none, 0.0, c))
        assertNotEquals(Confidence.HIGH, ConfidenceModel.level(null, 0.1, none, 0.0, c))
        assertEquals(Confidence.MEDIUM, ConfidenceModel.level(null, 2.0, none, 0.0, c))
        assertEquals(Confidence.LOW, ConfidenceModel.level(null, 5.0, none, 0.0, c))
        assertEquals(Confidence.MEDIUM, ConfidenceModel.level(3.0, 5.0, none, 0.0, c))
        assertEquals(Confidence.LOW, ConfidenceModel.level(0.5, 0.5, setOf(OrientationWarning.INITIALIZING), 0.0, c))
        assertEquals(Confidence.LOW, ConfidenceModel.level(0.5, 0.5, setOf(OrientationWarning.CAMERA_BLOCKED), 4.0, c))
        assertEquals(Confidence.MEDIUM, ConfidenceModel.level(0.5, 0.5, setOf(OrientationWarning.CAMERA_BLOCKED), 1.0, c))
        assertEquals(Confidence.LOW, ConfidenceModel.level(0.5, 0.5, setOf(OrientationWarning.VERTICAL), 0.0, c))
    }

    @Test fun zeroAbsHelper() = assertTrue(abs(0.0) == 0.0)
}

class BiasJacobianTest {
    @Test fun jacobianMapsBiasDuringRotation() {
        val h = GyroHistory(1024)
        val b = Vec3(0.01, -0.02, 0.015)
        // Large rotation inside the window: 1.2 rad/s about a tilted axis for 1 s.
        val w = Vec3(0.3, 1.0, 0.5).normalized() * 1.2
        for (i in 0..400) h.add(T0 + i * 5_000_000L, w + b)
        val t1 = T0 + 200_000_000L; val t2 = T0 + 1_200_000_000L
        val raw = h.integrateWithBiasJacobian(t1, t2, Vec3.ZERO)!!
        val truth = h.integrate(t1, t2, b)!!
        val e = (truth.conj() * raw.rotD1D2).log()
        val recovered = raw.biasJacobian.inverse()!! * e
        assertVec(b, recovered, 2e-4)
        // The naive e/Δt is clearly wrong here.
        assertTrue((e / 1.0 - b).norm() > 2e-3)
    }

    @Test fun inverseOfRotationIsTranspose() {
        val r = Mat3.rotX(0.4) * Mat3.rotZ(-1.1)
        assertTrue(r.inverse()!!.approxEquals(r.transpose(), 1e-12))
    }
}
