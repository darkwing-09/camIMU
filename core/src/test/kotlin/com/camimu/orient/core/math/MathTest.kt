package com.camimu.orient.core.math

import com.camimu.orient.core.assertQuatSameRotation
import com.camimu.orient.core.assertVec
import com.camimu.orient.core.randomQuat
import com.camimu.orient.core.randomVec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI

class Vec3Mat3AngleTest {
    @Test fun crossOfBasis() = assertVec(Vec3.EZ, Vec3.EX cross Vec3.EY, 0.0)

    @Test(expected = IllegalArgumentException::class)
    fun normalizeZeroThrows() { Vec3.ZERO.normalized() }

    @Test fun rotZTakesXToY() = assertVec(Vec3.EY, Mat3.rotZ(PI / 2) * Vec3.EX, 1e-15)

    @Test fun rotationsHaveUnitDeterminantAndOrthonormal() {
        val r = Mat3.rotX(0.3) * Mat3.rotY(-1.2) * Mat3.rotZ(2.0)
        assertEquals(1.0, r.det(), 1e-12)
        assertTrue((r.transpose() * r).approxEquals(Mat3.IDENTITY, 1e-12))
    }

    @Test fun fromColsAndCol() {
        val m = Mat3.fromCols(Vec3(1.0, 2.0, 3.0), Vec3(4.0, 5.0, 6.0), Vec3(7.0, 8.0, 9.0))
        assertVec(Vec3(4.0, 5.0, 6.0), m.col(1), 0.0)
        assertVec(Vec3(1.0, 4.0, 7.0), m.row(0), 0.0)
    }

    @Test fun wrapPiEdges() {
        assertEquals(PI, AngleMath.wrapPi(PI), 1e-15)
        assertEquals(PI, AngleMath.wrapPi(-PI), 1e-15)
        assertEquals(PI, AngleMath.wrapPi(3 * PI), 1e-12)
        assertEquals(-0.1, AngleMath.wrapPi(-0.1), 1e-15)
        assertEquals(7.0 - 2 * PI, AngleMath.wrapPi(7.0), 1e-12)
    }

    @Test fun angleDiffAcrossWrap() =
        assertEquals(AngleMath.rad(-2.0), AngleMath.angleDiff(AngleMath.rad(179.0), AngleMath.rad(-179.0)), 1e-12)
}

class QuaternionTest {
    private val rnd = Random(1)

    @Test fun expRotatesXToY() = assertVec(Vec3.EY, Quaternion.exp(Vec3.EZ * (PI / 2)).rotate(Vec3.EX), 1e-15)

    @Test fun matrixMatchesRotate() {
        repeat(100) {
            val q = randomQuat(rnd); val v = randomVec(rnd)
            assertVec(q.rotate(v), q.toMatrix() * v, 1e-12)
        }
    }

    @Test fun fromMatrixRoundTrip() {
        val qs = (0 until 100).map { randomQuat(rnd) } + listOf(
            Quaternion.fromAxisAngle(Vec3.EX, PI), Quaternion.fromAxisAngle(Vec3.EY, PI),
            Quaternion.fromAxisAngle(Vec3.EZ, PI), Quaternion.fromAxisAngle(Vec3(1.0, 1.0, 0.0), PI),
        )
        for (q in qs) assertQuatSameRotation(q, Quaternion.fromMatrix(q.toMatrix()), 1e-9)
    }

    @Test fun expLogRoundTrip() {
        repeat(100) {
            val q = randomQuat(rnd)
            assertQuatSameRotation(q, Quaternion.exp(q.log()), 1e-12)
            val phi = randomVec(rnd, 0.8)
            assertVec(phi, Quaternion.exp(phi).log(), 1e-12)
        }
        val tiny = Vec3(1e-10, -2e-10, 3e-10)
        assertVec(tiny, Quaternion.exp(tiny).log(), 1e-20)
    }

    @Test fun compositionOrder() {
        repeat(20) {
            val a = randomQuat(rnd); val b = randomQuat(rnd); val v = randomVec(rnd)
            assertVec(a.rotate(b.rotate(v)), (a * b).rotate(v), 1e-12)
        }
    }

    @Test fun integrateConstantRate() {
        var q = Quaternion.IDENTITY
        val w = Vec3(0.0, 0.0, 1.0)
        repeat(200) { q = (q * Quaternion.exp(w * 0.005)).normalized() }
        assertVec(Vec3(0.0, 0.0, 1.0), q.log(), 1e-9)
    }
}

class SymmetricEigenTest {
    @Test fun reconstructsRandomSymmetric() {
        val rnd = Random(3)
        repeat(20) {
            val a = Array(4) { DoubleArray(4) }
            for (i in 0 until 4) for (j in i until 4) { val x = rnd.nextGaussian(); a[i][j] = x; a[j][i] = x }
            val r = SymmetricEigen.jacobi(a)
            for (i in 0 until 4) for (j in 0 until 4) {
                var s = 0.0; var o = 0.0
                for (k in 0 until 4) {
                    s += r.vectors[i][k] * r.values[k] * r.vectors[j][k]
                    o += r.vectors[k][i] * r.vectors[k][j]
                }
                assertEquals(a[i][j], s, 1e-9)
                assertEquals(if (i == j) 1.0 else 0.0, o, 1e-9)
            }
        }
    }
}
