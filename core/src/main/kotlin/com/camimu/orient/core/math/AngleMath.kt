package com.camimu.orient.core.math

import kotlin.math.PI

/** Angle helpers. Everything internal is radians; degrees only at UI/CSV edges. */
object AngleMath {
    const val DEG = PI / 180.0
    const val TWO_PI = 2.0 * PI

    fun deg(rad: Double) = rad / DEG
    fun rad(deg: Double) = deg * DEG

    /** Wraps [a] (rad) to (−π, π]. */
    fun wrapPi(a: Double): Double {
        var r = (a + PI) % TWO_PI
        if (r <= 0.0) r += TWO_PI
        return r - PI
    }

    /** Smallest signed difference a − b (rad), wrapped to (−π, π]. */
    fun angleDiff(a: Double, b: Double) = wrapPi(a - b)

    /** rad/s → degrees per minute. */
    fun radPerSecToDegPerMin(radS: Double) = deg(radS) * 60.0
}
