package com.camimu.orient.core.frames

import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.math.AngleMath.rad
import com.camimu.orient.core.math.Mat3
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.AttitudeAngles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleConverterTest {
    private val vert = rad(80.0)

    private fun angles(q: Quaternion, ref: Double = 0.0) = AngleConverter.toAngles(q, ref, vert)
    private fun assertAngles(y: Double, p: Double, r: Double, a: AttitudeAngles, tol: Double = 1e-6) {
        assertEquals("yaw", 0.0, AngleMath.deg(AngleMath.angleDiff(rad(y), a.yawRad)), tol)
        assertEquals("pitch", p, a.pitchDeg, tol)
        assertEquals("roll", 0.0, AngleMath.deg(AngleMath.angleDiff(rad(r), a.rollRad)), tol)
    }

    @Test fun referencePoseIsZero() = assertAngles(0.0, 0.0, 0.0, angles(Quaternion.fromMatrix(Frames.R_WO0)))

    @Test fun roundTripGrid() {
        for (y in listOf(-170.0, -90.0, 0.0, 45.0, 179.0))
            for (p in listOf(-60.0, -10.0, 0.0, 30.0, 79.0))
                for (r in listOf(-170.0, -45.0, 0.0, 20.0, 175.0))
                    assertAngles(y, p, r, angles(AngleConverter.fromAngles(rad(y), rad(p), rad(r))))
    }

    @Test fun leftTurnIncreasesYaw() {
        val q = Quaternion.fromMatrix(Mat3.rotZ(rad(90.0)) * Frames.R_WO0)
        assertAngles(90.0, 0.0, 0.0, angles(q))
    }

    @Test fun pitchUpAndRightSideUpArePositive() {
        val up = AngleConverter.fromAngles(0.0, rad(30.0), 0.0)
        assertTrue(up.rotate(Vec3.EZ).z > 0)
        assertEquals(30.0, angles(up).pitchDeg, 1e-9)
        val rolled = AngleConverter.fromAngles(0.0, 0.0, rad(20.0))
        assertTrue("right axis must rise", rolled.rotate(Vec3.EX).z > 0)
        assertEquals(20.0, angles(rolled).rollDeg, 1e-9)
    }

    @Test fun wrapAndYawRef() {
        assertEquals(-179.0, angles(AngleConverter.fromAngles(rad(181.0), 0.0, 0.0)).yawDeg, 1e-9)
        assertEquals(-10.0, angles(Quaternion.fromMatrix(Frames.R_WO0), rad(10.0)).yawDeg, 1e-9)
    }

    @Test fun verticalFlagAndNoNaN() {
        assertTrue(angles(AngleConverter.fromAngles(0.0, rad(85.0), 0.0)).isVertical)
        assertFalse(angles(AngleConverter.fromAngles(0.0, rad(70.0), 0.0)).isVertical)
        val straightUp = Quaternion.fromMatrix(Mat3.rotY(rad(-90.0)) * Frames.R_WO0)
        val a = angles(straightUp)
        assertTrue(a.isVertical && !a.yawRad.isNaN() && !a.rollRad.isNaN() && !a.pitchRad.isNaN())
    }

    @Test fun deviceUprightPortraitIsZero() {
        val rWD = Mat3.of(0.0, 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        assertAngles(0.0, 0.0, 0.0, angles(Frames.qWOFromDevice(Quaternion.fromMatrix(rWD))))
    }

    @Test fun rcdMatricesAreRotations() {
        for (d in listOf(0, 90, 180, 270)) {
            val r = Frames.rCDForSensorOrientation(d)
            assertEquals(1.0, r.det(), 1e-15)
            assertTrue((r * r.transpose()).approxEquals(Mat3.IDENTITY, 1e-15))
        }
    }

    @Test fun arCoreIdentityCameraLooksAlongPlusY() {
        val a = angles(Frames.qWOFromArCore(floatArrayOf(0f, 0f, 0f, 1f)))
        assertAngles(90.0, 0.0, 0.0, a)
    }
}
