package com.camimu.orient.replay

import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.fusion.OrientationEstimator
import com.camimu.orient.core.io.CsvSchema
import com.camimu.orient.core.io.SessionReader
import com.camimu.orient.core.math.Mat3
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.VisionUpdateOutcome
import com.camimu.orient.core.sim.ImuSimulator
import com.camimu.orient.core.sim.VisionMeasurementSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReplayDeterminismTest {
    @Test fun replayEqualsDirectRun() {
        val q0 = Quaternion.fromMatrix(Mat3.of(0.0, 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0))
        val sim = ImuSimulator(200.0, Vec3(0.01, 0.0, -0.01), 1.5e-4, 0.02)
            .simulate(20.0, q0, { t -> if (t < 2) Vec3.ZERO else Vec3(0.0, 0.2 * kotlin.math.cos(t), 0.0) })
        val vsim = VisionMeasurementSimulator(3e-4)
        val cfg = EstimatorConfig()
        val dir = Files.createTempDirectory("replay").toFile()
        val imu = StringBuilder(CsvSchema.Imu.HEADER + "\n")
        val vis = StringBuilder(CsvSchema.Vision.HEADER + "\n")
        val ev = StringBuilder(CsvSchema.Events.HEADER + "\n")

        // "Device" run: log exactly what the app logs.
        val direct = OrientationEstimator(cfg)
        var seq = 0L
        val directOut = sim.samples.mapIndexed { i, s ->
            imu.append(CsvSchema.Imu.format(s)).append('\n')
            val o = direct.onImu(s)
            if (i > 400 && i % 200 == 0) {
                // Round-trip the measurement through its CSV representation, as the device log would.
                val m = vsim.measurement(sim, i - 200, i)
                val logged = m.copy(rotD1D2 = Quaternion.exp(m.rotD1D2.log()))
                vis.append(CsvSchema.Vision.format(logged, direct.onVision(logged), s.tNs, seq++)).append('\n')
            }
            if (i == 2000) { direct.resetYaw(); ev.append(CsvSchema.Events.format(s.tNs, seq++, "RESET_YAW", "")).append('\n') }
            o
        }
        dir.resolve("imu.csv").writeText(imu.toString())
        dir.resolve("vision.csv").writeText(vis.toString())
        dir.resolve("events.csv").writeText(ev.toString())

        val reader = SessionReader(dir)
        val r1 = Replayer.run(reader.imu(), reader.actions(), cfg)
        val r2 = Replayer.run(reader.imu(), reader.actions(), cfg)
        assertTrue(r1.visionTotal >= 15)
        assertEquals(r1.outputs, r2.outputs)
        for (i in directOut.indices) {
            assertEquals(directOut[i].angles.yawRad, r1.outputs[i].angles.yawRad, 1e-9)
            assertEquals(directOut[i].angles.pitchRad, r1.outputs[i].angles.pitchRad, 1e-9)
        }
        // keep the import used
        VisionUpdateOutcome(true, null)
    }
}
