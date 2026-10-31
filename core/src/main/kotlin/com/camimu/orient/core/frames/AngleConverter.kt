package com.camimu.orient.core.frames

import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.math.Mat3
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.AttitudeAngles
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/** q → yaw/pitch/roll and back. Definitions: docs/Architecture.md §4.4. Turning left = +yaw. */
object AngleConverter {
    /** Heading of the optical axis (rad) before subtracting the yaw reference. */
    fun yawRaw(qWO: Quaternion): Double {
        val f = qWO.rotate(Vec3.EZ)
        return atan2(f.y, f.x)
    }

    /** @param verticalPitchRad |pitch| above this flags the yaw/roll singularity. */
    fun toAngles(qWO: Quaternion, yawRefRad: Double, verticalPitchRad: Double): AttitudeAngles {
        val r = qWO.toMatrix()
        val right = r.col(0)
        val down = r.col(1)
        val fwd = r.col(2)
        val yaw = AngleMath.wrapPi(atan2(fwd.y, fwd.x) - yawRefRad)
        val pitch = asin(fwd.z.coerceIn(-1.0, 1.0))
        val roll = atan2(right.z, -down.z)
        return AttitudeAngles(yaw, pitch, roll, abs(pitch) > verticalPitchRad)
    }

    /** Inverse of [toAngles] with yawRef = 0: rWO = Rz(yaw)·Ry(−pitch)·R_WO0·Rz(−roll). */
    fun fromAngles(yawRad: Double, pitchRad: Double, rollRad: Double): Quaternion =
        Quaternion.fromMatrix(Mat3.rotZ(yawRad) * Mat3.rotY(-pitchRad) * Frames.R_WO0 * Mat3.rotZ(-rollRad))
}
