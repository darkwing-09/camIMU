package com.camimu.orient.core

import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Random

fun assertVec(expected: Vec3, actual: Vec3, tol: Double) {
    assertEquals("x", expected.x, actual.x, tol)
    assertEquals("y", expected.y, actual.y, tol)
    assertEquals("z", expected.z, actual.z, tol)
}

fun assertQuatSameRotation(expected: Quaternion, actual: Quaternion, tolRad: Double) {
    val a = expected.angleTo(actual)
    assertTrue("rotation differs by $a rad", a <= tolRad)
}

fun randomQuat(rnd: Random): Quaternion =
    Quaternion(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalized()

fun randomVec(rnd: Random, scale: Double = 1.0) = Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * scale
