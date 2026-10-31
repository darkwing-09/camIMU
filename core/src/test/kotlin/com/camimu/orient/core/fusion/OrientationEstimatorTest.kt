package com.camimu.orient.core.fusion

import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.frames.AngleConverter
import com.camimu.orient.core.frames.Frames
import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.AttitudeAngles
import com.camimu.orient.core.model.Confidence
import com.camimu.orient.core.model.OcclusionState
import com.camimu.orient.core.model.OrientationOutput
import com.camimu.orient.core.model.VisionMeasurement
import com.camimu.orient.core.model.VisionRejectReason
import com.camimu.orient.core.sim.ImuSimulator
import com.camimu.orient.core.sim.SimResult
import com.camimu.orient.core.sim.VisionMeasurementSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val VERT = AngleMath.rad(80.0)
private fun truthAngles(q: Quaternion): AttitudeAngles = AngleConverter.toAngles(Frames.qWOFromDevice(q), 0.0, VERT)
private fun yawErrDeg(o: OrientationOutput, q: Quaternion) = abs(AngleMath.deg(AngleMath.angleDiff(o.angles.yawRad, truthAngles(q).yawRad)))

/** Synthetic end-to-end scenarios SY-1..SY-7 (docs/TestPlan.md §2). */
class OrientationEstimatorTest {
    private val noise = 1.5e-4

    private fun staticThen(t0: Double, w: (Double) -> Vec3): (Double) -> Vec3 = { t -> if (t < t0) Vec3.ZERO else w(t) }

    private fun run(
        sim: SimResult, cfg: EstimatorConfig = EstimatorConfig(),
        onStep: (Int, OrientationEstimator, OrientationOutput) -> Unit = { _, _, _ -> },
    ): Pair<OrientationEstimator, List<OrientationOutput>> {
        val est = OrientationEstimator(cfg, GyroHistory(1024))
        val outs = sim.samples.mapIndexed { i, s -> est.onImu(s).also { onStep(i, est, it) } }
        return est to outs
    }

    @Test fun sy1StaticBiasLearnedByZru() {
        val sim = ImuSimulator(200.0, Vec3(0.01, -0.005, 0.02), noise, 0.02).simulate(62.0, Q_UPRIGHT, { Vec3.ZERO })
        val (est, outs) = run(sim)
        val last = outs.last()
        assertTrue(est.zruUpdates > 50)
        assertTrue("yaw ${last.angles.yawDeg}", abs(last.angles.yawDeg) < 0.2)
        assertEquals(0.0, last.angles.pitchDeg, 0.1)
        assertEquals(0.0, last.angles.rollDeg, 0.1)
    }

    @Test fun sy2YawTurn() {
        val sim = ImuSimulator(200.0, Vec3(0.01, -0.005, 0.02), noise, 0.02)
            .simulate(10.0, Q_UPRIGHT, staticThen(2.0) { t -> if (t < 8.0) Vec3(0.0, 0.5, 0.0) else Vec3.ZERO })
        val (_, outs) = run(sim)
        val err = yawErrDeg(outs.last(), sim.truthQWD.last())
        assertTrue("yaw err $err", err < 1.0)
        assertEquals(AngleMath.deg(AngleMath.wrapPi(3.0)), outs.last().angles.yawDeg, 1.0)
    }

    @Test fun sy3TiltSine() {
        val w = 2 * PI * 0.5
        val amp = AngleMath.rad(20.0)
        val sim = ImuSimulator(200.0, Vec3(0.005, 0.0, -0.004), noise, 0.02)
            .simulate(32.0, Q_UPRIGHT, staticThen(2.0) { t -> Vec3(amp * w * cos(w * (t - 2.0)), 0.0, 0.0) })
        val (_, outs) = run(sim)
        assertTrue(rmsePitchRoll(outs, sim, 600) < 0.5)
    }

    @Test fun sy4WalkingAcceleration() {
        val sim = ImuSimulator(200.0, Vec3(0.005, 0.0, -0.004), noise, 0.05).simulate(
            32.0, Q_UPRIGHT, { Vec3.ZERO },
            { t -> if (t < 2.0) Vec3.ZERO else Vec3(2.0 * sin(2 * PI * 2.0 * t), 0.0, 1.0 * sin(2 * PI * 4.0 * t)) },
        )
        val (_, outs) = run(sim)
        assertTrue(rmsePitchRoll(outs, sim, 600) < 2.0)
    }

    @Test fun resetYawZeroesOutput() {
        val sim = ImuSimulator(200.0, Vec3.ZERO, noise, 0.02)
            .simulate(6.0, Q_UPRIGHT, staticThen(2.0) { t -> if (t < 4.0) Vec3(0.0, 0.3, 0.0) else Vec3.ZERO })
        val est = OrientationEstimator(EstimatorConfig())
        var after: OrientationOutput? = null
        sim.samples.forEachIndexed { i, s ->
            val o = est.onImu(s)
            if (i == 1000) { assertTrue(abs(o.angles.yawDeg) > 10.0); est.resetYaw() }
            if (i == 1001) after = o
        }
        assertEquals(0.0, after!!.angles.yawDeg, 0.01)
        assertEquals(0.0, after!!.sigmaYawRad, 1e-4)
    }

    // ---------- Vision (SY-5 .. SY-7) ----------

    private fun walkBias(seconds: Int, sigmaBw: Double, seed: Long): (Double) -> Vec3 {
        val rnd = Random(seed)
        val knots = ArrayList<Vec3>()
        var b = Vec3(0.01, -0.01, 0.015)
        repeat(seconds + 2) { knots += b; b += Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * sigmaBw }
        return { t -> val i = t.toInt().coerceIn(0, seconds); knots[i] + (knots[i + 1] - knots[i]) * (t - i) }
    }

    private fun slowYawSim(seconds: Double, sigmaBw: Double, seed: Long = 21): SimResult {
        val amp = AngleMath.rad(40.0)
        val w = 2 * PI * 0.05
        return ImuSimulator(200.0, walkBias(seconds.toInt(), sigmaBw, seed), noise, 0.02, seed)
            .simulate(seconds, Q_UPRIGHT, staticThen(2.0) { t -> Vec3(0.0, amp * w * cos(w * (t - 2.0)), 0.0) })
    }

    private fun runWithVision(
        sim: SimResult, cfg: EstimatorConfig, vsim: VisionMeasurementSimulator?,
        visionOff: (Double) -> Boolean = { false },
        onOutcome: (com.camimu.orient.core.model.VisionUpdateOutcome) -> Unit = {},
        onStep: (Int, OrientationEstimator, OrientationOutput) -> Unit = { _, _, _ -> },
    ): List<OrientationOutput> {
        val est = OrientationEstimator(cfg, GyroHistory(1024))
        var anchor = -1
        return sim.samples.mapIndexed { i, s ->
            val o = est.onImu(s)
            val t = (s.tNs - sim.samples[0].tNs) * 1e-9
            if (vsim != null && est.isInitialized) {
                val off = visionOff(t)
                est.setVisionState(if (off) OcclusionState.BLOCKED else OcclusionState.OK, s.tNs)
                if (off) anchor = -1
                else if (anchor < 0) anchor = i
                else if (i - anchor >= 200) { onOutcome(est.onVision(vsim.measurement(sim, anchor, i))); anchor = i }
            }
            onStep(i, est, o)
            o
        }
    }

    @Test fun sy5VisionBoundsYawDrift() {
        val sigmaBw = 1e-4
        val sim = slowYawSim(302.0, sigmaBw)
        val cfg = EstimatorConfig(biasRandomWalk = sigmaBw, zruMaxRateRadS = 1e-9)
        val imuOnly = runWithVision(sim, cfg, null)
        val fused = runWithVision(sim, cfg, VisionMeasurementSimulator(3e-4))
        val errImu = yawErrDeg(imuOnly.last(), sim.truthQWD.last())
        val errFused = yawErrDeg(fused.last(), sim.truthQWD.last())
        assertTrue("imu-only err $errImu should be > 3°", errImu > 3.0)
        assertTrue("fused err $errFused should be < 1°", errFused < 1.0)
    }

    @Test fun sy6OutlierWindowsRejected() {
        val sigmaBw = 1e-4
        val sim = slowYawSim(302.0, sigmaBw, seed = 33)
        val cfg = EstimatorConfig(biasRandomWalk = sigmaBw, zruMaxRateRadS = 1e-9)
        var gated = 0
        val outs = runWithVision(sim, cfg, VisionMeasurementSimulator(3e-4, seed = 5, outlierFrac = 0.1), onOutcome = {
            if (it.reason == VisionRejectReason.GATE) gated++
        })
        assertTrue("gated $gated", gated >= 15)
        val err = yawErrDeg(outs.last(), sim.truthQWD.last())
        assertTrue("err $err", err < 1.5)
    }

    @Test fun lowQualityMovesBiasLess() {
        val sim = ImuSimulator(200.0, Vec3(0.0, 0.02, 0.0), noise, 0.02).simulate(4.0, Q_UPRIGHT, { Vec3.ZERO })
        fun biasShift(quality: Double): Double {
            val est = OrientationEstimator(EstimatorConfig(zruMaxRateRadS = 1e-9))
            sim.samples.take(500).forEach { est.onImu(it) }
            val b0 = est.bias
            val m = VisionMeasurement(sim.samples[300].tNs, sim.samples[499].tNs, Quaternion.IDENTITY, quality, 100, 0.9, 0.6, 1e-4, 0)
            est.onVision(m)
            return (est.bias - b0).norm()
        }
        assertTrue(biasShift(0.2) < biasShift(1.0))
    }

    @Test fun tooFastRejected() {
        val sim = ImuSimulator(200.0, Vec3.ZERO, noise, 0.02).simulate(4.0, Q_UPRIGHT, staticThen(2.5) { Vec3(0.0, 4.0, 0.0) })
        val est = OrientationEstimator(EstimatorConfig())
        sim.samples.forEach { est.onImu(it) }
        val n = sim.samples.size
        val m = VisionMeasurement(sim.samples[n - 150].tNs, sim.samples[n - 1].tNs, Quaternion.IDENTITY, 1.0, 100, 0.9, 0.6, 1e-4, 0)
        assertEquals(VisionRejectReason.TOO_FAST, est.onVision(m).reason)
    }

    @Test fun sy7CameraGapThenRecovery() {
        val sim = slowYawSim(122.0, 2e-5, seed = 44)
        val confAt = HashMap<Int, Confidence>()
        val sigmaAt = HashMap<Int, Double>()
        runWithVision(sim, EstimatorConfig(zruMaxRateRadS = 1e-9), VisionMeasurementSimulator(3e-4), visionOff = { t -> t in 60.0..90.0 },
            onStep = { i, _, o -> if (i % 200 == 0) { confAt[i / 200] = o.confidence; sigmaAt[i / 200] = o.sigmaYawRad } })
        assertEquals(Confidence.HIGH, confAt[55])
        assertEquals(Confidence.LOW, confAt[70])
        assertTrue(sigmaAt[89]!! > sigmaAt[61]!!)
        assertEquals(Confidence.HIGH, confAt[93])
    }

    private fun rmsePitchRoll(outs: List<OrientationOutput>, sim: SimResult, skip: Int): Double {
        var s = 0.0; var n = 0
        for (i in skip until outs.size) {
            val t = truthAngles(sim.truthQWD[i])
            val dp = outs[i].angles.pitchDeg - t.pitchDeg
            val dr = AngleMath.deg(AngleMath.angleDiff(outs[i].angles.rollRad, t.rollRad))
            s += dp * dp + dr * dr; n += 2
        }
        return sqrt(s / n)
    }

    @Test fun uninitializedOutputFlagged() {
        val est = OrientationEstimator(EstimatorConfig())
        val o = est.onImu(com.camimu.orient.core.model.ImuSample(T0, Vec3.ZERO, Vec3(0.0, 9.81, 0.0)))
        assertFalse(o.initialized)
        assertEquals(Confidence.LOW, o.confidence)
    }
}
