package com.camimu.orient.replay

import com.camimu.orient.core.CoreInfo
import com.camimu.orient.core.config.CalibrationData
import com.camimu.orient.core.config.ConfigJson
import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.core.frames.AngleConverter
import com.camimu.orient.core.frames.Frames
import com.camimu.orient.core.fusion.OrientationEstimator
import com.camimu.orient.core.io.CsvSchema
import com.camimu.orient.core.io.SessionMeta
import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.math.Mat3
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.ArCoreReading
import com.camimu.orient.core.model.ArTracking
import com.camimu.orient.core.sim.ImuSimulator
import com.camimu.orient.core.sim.VisionMeasurementSimulator
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Writes a complete synthetic session (same files as the app) with known truth, so the Python tools and
 * replay can be exercised without a phone. Camera timestamps lag the IMU clock by [timeOffsetNs].
 */
object Synthesizer {
    fun write(dir: File, seconds: Double = 60.0, timeOffsetNs: Long = 12_000_000L, seed: Long = 3L) {
        dir.mkdirs()
        val q0 = Quaternion.fromMatrix(Mat3.of(0.0, 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0))
        val motion = { t: Double ->
            if (t < 3.0 || t > seconds - 5.0) Vec3.ZERO
            else Vec3(0.6 * sin(2 * PI * 0.31 * t), 0.9 * sin(2 * PI * 0.17 * t + 1.0), 0.5 * sin(2 * PI * 0.23 * t + 2.0))
        }
        val sim = ImuSimulator(200.0, Vec3(0.008, -0.012, 0.006), 1.5e-4, 0.02, seed).simulate(seconds, q0, motion)
        val cfg = EstimatorConfig()
        val est = OrientationEstimator(cfg)
        val vsim = VisionMeasurementSimulator(3e-4, seed)
        val rnd = Random(seed)
        val imu = StringBuilder(CsvSchema.Imu.HEADER).append('\n')
        val estCsv = StringBuilder(CsvSchema.Est.HEADER).append('\n')
        val vis = StringBuilder(CsvSchema.Vision.HEADER).append('\n')
        val frames = StringBuilder(CsvSchema.VisionFrames.HEADER).append('\n')
        val ar = StringBuilder(CsvSchema.ArCore.HEADER).append('\n')
        val ev = StringBuilder(CsvSchema.Events.HEADER).append('\n')
        var seq = 0L
        var anchor = -1
        var lastFrame = -1
        val vert = AngleMath.rad(cfg.verticalPitchDeg)
        var arYawRef: Double? = null
        sim.samples.forEachIndexed { i, s ->
            imu.append(CsvSchema.Imu.format(s)).append('\n')
            val o = est.onImu(s)
            estCsv.append(CsvSchema.Est.format(o)).append('\n')
            if (est.isInitialized) {
                if (anchor < 0) anchor = i
                if (i - anchor >= 200) {
                    val m = vsim.measurement(sim, anchor, i)
                    val logged = m.copy(rotD1D2 = Quaternion.exp(m.rotD1D2.log()), tCam2Ns = m.t2Ns - timeOffsetNs)
                    vis.append(CsvSchema.Vision.format(logged, est.onVision(logged), s.tNs, seq++)).append('\n')
                    anchor = i
                }
            }
            if (i == 800) ev.append(CsvSchema.Events.format(s.tNs, -1, "MARK", "start")).append('\n')
            if (i == sim.samples.size - 200) ev.append(CsvSchema.Events.format(s.tNs, -1, "MARK", "end")).append('\n')
            // Camera frames every 33 ms: frame-pair rotation (truth + noise) with camera timestamps = IMU time − t_d.
            if (i % 7 == 0) {
                if (lastFrame >= 0) {
                    val rv = (sim.truthQWD[lastFrame].conj() * sim.truthQWD[i]).log() +
                        Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * 2e-4
                    frames.append(
                        CsvSchema.VisionFrames.format(
                            sim.samples[lastFrame].tNs - timeOffsetNs, s.tNs - timeOffsetNs, s.tNs, 150, 140, 3, 120, rv,
                            true, 40.0, "OK", 6.0,
                        ),
                    ).append('\n')
                }
                lastFrame = i
                val qWO = Frames.qWOFromDevice(sim.truthQWD[i]) * Quaternion.exp(Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * 0.003)
                val ref = arYawRef ?: AngleConverter.yawRaw(qWO).also { arYawRef = it }
                ar.append(
                    CsvSchema.ArCore.format(
                        ArCoreReading(s.tNs, s.tNs, qWO, ArTracking.TRACKING, "NONE", AngleConverter.toAngles(qWO, ref, vert)),
                    ),
                ).append('\n')
            }
        }
        File(dir, "imu.csv").writeText(imu.toString())
        File(dir, "est.csv").writeText(estCsv.toString())
        File(dir, "vision.csv").writeText(vis.toString())
        File(dir, "vision_frames.csv").writeText(frames.toString())
        File(dir, "arcore.csv").writeText(ar.toString())
        File(dir, "events.csv").writeText(ev.toString())
        val meta = SessionMeta(
            appVersion = "synthetic", coreVersion = CoreInfo.VERSION, deviceModel = "SIMULATOR", mode = "SYNTHETIC",
            estimatorConfig = cfg, visionConfig = VisionConfig(), calibration = CalibrationData(),
            registeredSensorTypes = listOf(16, 1), sensorClock = "ELAPSED_REALTIME", measuredGyroHz = 200.0, measuredAccelHz = 200.0,
            sensorOrientationDeg = 90, rCD = Frames.rCDForSensorOrientation(90).toRowMajor().toList(),
            notes = mapOf("truthTimeOffsetNs" to timeOffsetNs.toString()),
        )
        File(dir, "meta.json").writeText(ConfigJson.encode(meta))
    }
}
