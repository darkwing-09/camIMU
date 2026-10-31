package com.camimu.orient.core.io

import com.camimu.orient.core.config.ConfigJson
import com.camimu.orient.core.math.Quaternion
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.ImuSample
import com.camimu.orient.core.model.OcclusionState
import com.camimu.orient.core.model.VisionMeasurement
import java.io.File

/** Something to apply to the estimator right after the IMU sample with t_ns == [tAppliedNs]. */
sealed class ReplayAction(val tAppliedNs: Long, val seq: Long) {
    class Vision(tApplied: Long, seq: Long, val m: VisionMeasurement) : ReplayAction(tApplied, seq)
    class ResetYaw(tApplied: Long, seq: Long) : ReplayAction(tApplied, seq)
    class Occlusion(tApplied: Long, seq: Long, val state: OcclusionState) : ReplayAction(tApplied, seq)
}

/** Reads a recorded session directory (imu.csv, vision.csv, events.csv, meta.json). */
class SessionReader(private val dir: File) {
    fun meta(): SessionMeta? {
        val f = File(dir, "meta.json")
        if (!f.exists()) return null
        return ConfigJson.decodeMeta(f.readText())
    }

    fun imu(): List<ImuSample> = rows("imu.csv").map { r ->
        val os = if (r["os_bgx"].isNullOrEmpty()) null else Vec3(d(r, "os_bgx"), d(r, "os_bgy"), d(r, "os_bgz"))
        ImuSample(
            l(r, "t_ns"), Vec3(d(r, "gx"), d(r, "gy"), d(r, "gz")), Vec3(d(r, "ax"), d(r, "ay"), d(r, "az")),
            r["accel_valid"] == "1", l(r, "accel_age_ns"), os,
        )
    }

    /** Estimator-affecting actions (vision measurements, yaw resets, occlusion changes). */
    fun actions(includeVision: Boolean = true): List<ReplayAction> {
        val out = ArrayList<ReplayAction>()
        for (r in rows("events.csv")) {
            val t = l(r, "t_ns")
            val seq = l(r, "seq")
            when (r["event"]) {
                "RESET_YAW" -> out += ReplayAction.ResetYaw(t, seq)
                "OCCLUSION_STATE" -> if (includeVision) out += ReplayAction.Occlusion(t, seq, OcclusionState.valueOf(r["detail"]!!))
            }
        }
        if (includeVision) for (r in rows("vision.csv")) {
            val m = VisionMeasurement(
                l(r, "t1_ns"), l(r, "t2_ns"), Quaternion.exp(Vec3(d(r, "rvx"), d(r, "rvy"), d(r, "rvz"))),
                d(r, "quality"), r["n_inliers"]!!.toInt(), d(r, "inlier_ratio"), d(r, "spread"), d(r, "rms_err_rad"),
                l(r, "t_cam2_ns"),
            )
            out += ReplayAction.Vision(l(r, "t_applied_ns"), l(r, "seq"), m)
        }
        return out.sortedWith(compareBy({ it.tAppliedNs }, { it.seq }))
    }

    private fun rows(name: String): List<Map<String, String>> {
        val f = File(dir, name)
        if (!f.exists()) return emptyList()
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val header = CsvSchema.split(lines[0])
        return lines.drop(1).map { line ->
            val v = CsvSchema.split(line)
            header.indices.associate { header[it] to (v.getOrNull(it) ?: "") }
        }
    }

    private fun d(r: Map<String, String>, k: String) = r[k]!!.toDouble()
    private fun l(r: Map<String, String>, k: String) = r[k]!!.toLong()
}
