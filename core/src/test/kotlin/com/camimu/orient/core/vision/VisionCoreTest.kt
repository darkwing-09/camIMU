package com.camimu.orient.core.vision

import com.camimu.orient.core.assertQuatSameRotation
import com.camimu.orient.core.config.CameraIntrinsics
import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.core.frames.Frames
import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.OcclusionState
import com.camimu.orient.core.model.Px
import com.camimu.orient.core.randomQuat
import com.camimu.orient.core.sim.BearingSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

private val K = CameraIntrinsics(640, 480, 500.0, 500.0, 320.0, 240.0)

class CameraModelTest {
    @Test fun projectBearingIdentity() {
        val cm = CameraModel(K)
        for (u in listOf(0.0, 100.5, 320.0, 639.0)) for (v in listOf(0.0, 240.0, 479.0)) {
            val p = cm.project(cm.bearing(u, v))!!
            assertEquals(u, p.u, 1e-9); assertEquals(v, p.v, 1e-9)
        }
        val c = cm.bearing(320.0, 240.0)
        assertEquals(1.0, c.z, 1e-15)
    }

    @Test fun distortionRoundTrip() {
        val cm = CameraModel(K.copy(k1 = -0.2, k2 = 0.05, p1 = 0.001, p2 = -0.001))
        for (u in listOf(5.0, 160.0, 320.0, 600.0)) for (v in listOf(5.0, 240.0, 470.0)) {
            val p = cm.projectDistorted(cm.bearing(u, v))!!
            assertEquals(u, p.u, 0.01); assertEquals(v, p.v, 0.01)
        }
    }

    @Test fun behindCameraIsNull() = assertNull(CameraModel(K).project(Vec3(0.0, 0.0, -1.0)))
}

class WahbaSolverTest {
    private val bs = BearingSimulator(4)

    @Test fun exactRecovery() {
        val rnd = Random(2)
        repeat(20) {
            val q = Quaternion.exp(Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalized() * (rnd.nextDouble() * 0.5))
            val (b1, b2) = bs.pairs(50, q, 500.0, 0.0)
            assertQuatSameRotation(q, WahbaSolver.solve(b1, b2)!!, 1e-9)
        }
    }

    @Test fun twoPointsExact() {
        val q = randomQuat(Random(8))
        val b1 = listOf(Vec3(0.1, 0.0, 1.0).normalized(), Vec3(-0.2, 0.3, 1.0).normalized())
        assertQuatSameRotation(q, WahbaSolver.solve(b1, b1.map { q.rotate(it) })!!, 1e-9)
    }

    @Test fun noisyAccuracy() {
        val q = Quaternion.exp(Vec3(0.0, 0.2, 0.05))
        val (b1, b2) = bs.pairs(100, q, 500.0, 0.5)
        assertTrue(AngleMath.deg(q.angleTo(WahbaSolver.solve(b1, b2)!!)) < 0.05)
    }

    @Test fun degenerateIsNull() {
        val b = List(10) { Vec3.EZ }
        assertNull(WahbaSolver.solve(b, b))
        assertNull(WahbaSolver.solve(listOf(Vec3.EZ), listOf(Vec3.EZ)))
    }
}

class RotationRansacTest {
    private val cfg = VisionConfig()
    private val thr = cfg.ransacThresholdPx / 500.0

    @Test fun randomOutliers() {
        val q = Quaternion.exp(Vec3(0.02, -0.1, 0.03))
        val (b1, b2) = BearingSimulator(1).pairs(150, q, 500.0, 0.5, outlierFrac = 0.3)
        val r = RotationRansac(cfg).estimate(b1, b2, thr)
        assertTrue(AngleMath.deg(q.angleTo(r.q21!!)) < 0.05)
        assertTrue((0 until 45).count { r.inlierMask[it] } <= 2)
    }

    @Test fun movingCrowdMinority() {
        val q = Quaternion.exp(Vec3(0.0, -0.08, 0.0))
        val crowd = Quaternion.exp(Vec3(0.0, 0.05, 0.02))
        val (b1, b2) = BearingSimulator(2).pairs(150, q, 500.0, 0.5, movingFrac = 0.35, movingQ21 = crowd)
        val r = RotationRansac(cfg).estimate(b1, b2, thr)
        assertTrue(AngleMath.deg(q.angleTo(r.q21!!)) < 0.05)
    }

    @Test fun movingMajorityIsInvalid() {
        val q = Quaternion.exp(Vec3(0.0, -0.08, 0.0))
        val crowd = Quaternion.exp(Vec3(0.0, 0.05, 0.02))
        val (b1, b2) = BearingSimulator(3).pairs(150, q, 500.0, 0.5, movingFrac = 0.6, movingQ21 = crowd)
        val r = RotationRansac(cfg).estimate(b1, b2, thr)
        // The static 40 % is the minority; static points it was fit to are at most 40 % → ratio below 0.4 for the static solution.
        val staticInliers = (90 until 150).count { r.inlierMask[it] }
        val crowdInliers = (0 until 90).count { r.inlierMask[it] }
        assertTrue(crowdInliers > staticInliers) // RANSAC follows the majority (crowd)...
        // ...so validity must come from the majority rule on the *static* share, which the gyro gate enforces upstream.
        val q2 = VisionQuality.evaluate(staticInliers, 150, IntArray(staticInliers) { it % 48 }, cfg)
        assertFalse(q2.valid)
    }

    @Test fun deterministicAndEmpty() {
        val (b1, b2) = BearingSimulator(5).pairs(100, Quaternion.exp(Vec3(0.0, 0.1, 0.0)), 500.0, 0.5, outlierFrac = 0.2)
        assertEquals(RotationRansac(cfg, 7).estimate(b1, b2, thr).q21, RotationRansac(cfg, 7).estimate(b1, b2, thr).q21)
        assertNull(RotationRansac(cfg).estimate(emptyList(), emptyList(), thr).q21)
        assertNull(RotationRansac(cfg).estimate(listOf(Vec3.EZ), listOf(Vec3.EZ), thr).q21)
    }
}

class VisionQualityTest {
    private val cfg = VisionConfig()
    @Test fun goodAndBad() {
        assertTrue(VisionQuality.evaluate(100, 120, IntArray(100) { it % 40 }, cfg).valid)
        assertFalse(VisionQuality.evaluate(10, 12, IntArray(10) { it }, cfg).valid) // too few
        assertFalse(VisionQuality.evaluate(100, 300, IntArray(100) { it % 40 }, cfg).valid) // ratio < 0.4
        assertFalse(VisionQuality.evaluate(100, 120, IntArray(100) { 0 }, cfg).valid) // no spread
    }
}

class BucketerAndPredictorTest {
    @Test fun perCellCapIncludesExisting() {
        val b = GridBucketer(640, 480, 8, 6)
        val cands = List(1000) { Px(5.0 + it % 30, 5.0 + it / 40) }
        val existing = listOf(Px(10.0, 10.0))
        val sel = b.select(cands, existing, 4, 1000)
        assertEquals(3, sel.count { b.cellOf(it) == 0 })
        assertTrue(sel.all { b.cellOf(it) == 0 || b.cellOf(it) == 1 })
    }

    @Test fun identityPredictsSamePixel() {
        val p = GyroFlowPredictor(CameraModel(K), Frames.rCDForSensorOrientation(90))
        val out = p.predict(Px(100.0, 200.0), Quaternion.IDENTITY)!!
        assertEquals(100.0, out.u, 1e-9); assertEquals(200.0, out.v, 1e-9)
    }

    @Test fun predictionMatchesSimulatedCamera() {
        val rCD = Frames.rCDForSensorOrientation(90)
        val cm = CameraModel(K)
        val pred = GyroFlowPredictor(cm, rCD)
        val rotD1D2 = Quaternion.exp(Vec3(0.01, AngleMath.rad(2.0), -0.005))
        // Oracle: static world point direction in C1, its direction in C2 = rC2C1 b1.
        val rC1C2 = rCD * rotD1D2.toMatrix() * rCD.transpose()
        val p1 = Px(400.0, 100.0)
        val b2 = rC1C2.transpose() * cm.bearing(p1)
        val oracle = cm.project(b2)!!
        val out = pred.predict(p1, rotD1D2)!!
        assertEquals(oracle.u, out.u, 1e-9); assertEquals(oracle.v, out.v, 1e-9)
        // and the predicted pixel actually moved
        assertTrue(kotlin.math.abs(out.u - p1.u) + kotlin.math.abs(out.v - p1.v) > 5.0)
    }
}

class AnchorWindowTest {
    private val cfg = VisionConfig()

    @Test fun closesAtMaxAndEarly() {
        val w = AnchorWindow(cfg)
        assertEquals(AnchorWindow.Decision.RESET, w.onFrame(0, 100))
        w.start(0, 100)
        assertEquals(AnchorWindow.Decision.CONTINUE, w.onFrame(500_000_000, 90))
        assertEquals(AnchorWindow.Decision.CLOSE, w.onFrame(1_000_000_000, 90))
        w.start(0, 100)
        assertEquals(AnchorWindow.Decision.CLOSE, w.onFrame(300_000_000, 40))
        w.start(0, 100)
        assertEquals(AnchorWindow.Decision.RESET, w.onFrame(100_000_000, 40))
    }

    @Test fun measurementMapsBackToDeviceRotation() {
        val rCD = Frames.rCDForSensorOrientation(90)
        val rotD1D2 = Quaternion.exp(Vec3(0.02, -0.1, 0.05))
        val q21 = Quaternion.fromMatrix((rCD * rotD1D2.toMatrix() * rCD.transpose()).transpose())
        val m = AnchorWindow.buildMeasurement(0, 1, 1, q21, rCD, QualityResult(1.0, true, 1.0, 1.0, null), 50, 0.0)
        assertQuatSameRotation(rotD1D2, m.rotD1D2, 1e-12)
    }
}

class OcclusionDetectorTest {
    private val cfg = VisionConfig()
    private val s = 1_000_000_000L

    @Test fun debounceBlockedAndRecovery() {
        val d = OcclusionDetector(cfg)
        assertEquals(OcclusionState.OK, d.update(0, 100, 40.0))
        assertEquals(OcclusionState.OK, d.update(s / 10, 100, 2.0))
        assertEquals(OcclusionState.BLOCKED, d.update(s, 100, 2.0))
        assertEquals(OcclusionState.BLOCKED, d.update(s + s / 10, 100, 40.0))
        assertEquals(OcclusionState.OK, d.update(2 * s, 100, 40.0))
    }

    @Test fun lowTexture() {
        val d = OcclusionDetector(cfg)
        d.update(0, 10, 30.0)
        assertEquals(OcclusionState.LOW_TEXTURE, d.update(s, 10, 30.0))
    }
}

class OverlayMappingTest {
    @Test fun rotation90MapsCornersAndCentre() {
        val m = OverlayMapping(90, 640, 480, 480.0, 640.0)
        val c = m.toView(Px(320.0, 240.0))
        assertEquals(240.0, c.u, 1e-9); assertEquals(320.0, c.v, 1e-9)
        // raw top-left (0,0) appears at the upright top-right
        val tl = m.toView(Px(0.0, 0.0))
        assertEquals(480.0, tl.u, 1e-9); assertEquals(0.0, tl.v, 1e-9)
    }
}
