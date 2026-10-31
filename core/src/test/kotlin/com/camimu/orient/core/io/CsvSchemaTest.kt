package com.camimu.orient.core.io

import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.AttitudeAngles
import com.camimu.orient.core.model.Confidence
import com.camimu.orient.core.model.ImuSample
import com.camimu.orient.core.model.OrientationOutput
import com.camimu.orient.core.model.OrientationWarning
import com.camimu.orient.core.model.VisionMeasurement
import com.camimu.orient.core.model.VisionUpdateOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvSchemaTest {
    private fun n(s: String) = CsvSchema.split(s).size

    @Test fun fieldCountsMatchHeaders() {
        assertEquals(n(CsvSchema.Imu.HEADER), n(CsvSchema.Imu.format(ImuSample(1, Vec3(0.1, 0.2, 0.3), Vec3(1.0, 2.0, 3.0)))))
        val o = OrientationOutput(1, true, Quaternion.IDENTITY, AttitudeAngles.ZERO, Vec3.ZERO, 0.0, 0.0, Confidence.HIGH,
            setOf(OrientationWarning.VERTICAL, OrientationWarning.SENSOR_GAP), null)
        val est = CsvSchema.Est.format(o)
        assertEquals(n(CsvSchema.Est.HEADER), n(est))
        assertTrue(est.endsWith("VERTICAL|SENSOR_GAP"))
        val m = VisionMeasurement(1, 2, Quaternion.IDENTITY, 0.5, 10, 0.5, 0.5, 0.001, 2)
        assertEquals(n(CsvSchema.Vision.HEADER), n(CsvSchema.Vision.format(m, VisionUpdateOutcome(true, null), 3, 0)))
        assertEquals(n(CsvSchema.VisionFrames.HEADER),
            n(CsvSchema.VisionFrames.format(1, 2, 3, 4, 5, 6, 7, null, true, 9.0, "OK", 1.5)))
        assertEquals(n(CsvSchema.Events.HEADER), n(CsvSchema.Events.format(1, -1, "MODE", "a,b")))
    }

    @Test fun decimalPointAndRoundTrip() {
        val v = 0.123456789012345
        val line = CsvSchema.Imu.format(ImuSample(5, Vec3(v, -v, 1e-20), Vec3.ZERO))
        assertFalse(line.contains(";"))
        assertEquals(v, CsvSchema.split(line)[1].toDouble(), 0.0)
    }
}
