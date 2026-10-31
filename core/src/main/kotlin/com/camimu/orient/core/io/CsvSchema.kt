package com.camimu.orient.core.io

import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.ArCoreReading
import com.camimu.orient.core.model.ImuSample
import com.camimu.orient.core.model.OrientationOutput
import com.camimu.orient.core.model.VisionMeasurement
import com.camimu.orient.core.model.VisionUpdateOutcome

/**
 * CSV headers and row formatters (docs/DataModel.md §5). Doubles use Kotlin's locale-independent
 * shortest round-trip `toString()`, so raw values replay bit-exactly.
 */
object CsvSchema {
    object Imu {
        const val HEADER = "t_ns,gx,gy,gz,ax,ay,az,accel_valid,accel_age_ns,os_bgx,os_bgy,os_bgz"
        fun format(s: ImuSample): String {
            val b = s.osBias
            return "${s.tNs},${s.gyroRaw.x},${s.gyroRaw.y},${s.gyroRaw.z},${s.accel.x},${s.accel.y},${s.accel.z}," +
                "${if (s.accelValid) 1 else 0},${s.accelAgeNs},${b?.x ?: ""},${b?.y ?: ""},${b?.z ?: ""}"
        }
    }

    object Est {
        const val HEADER = "t_ns,initialized,qw,qx,qy,qz,yaw_deg,pitch_deg,roll_deg,is_vertical,bgx,bgy,bgz," +
            "sigma_yaw_deg,drift_sigma_deg_min,confidence,warnings"
        fun format(o: OrientationOutput): String {
            val q = o.qWD
            val a = o.angles
            return "${o.tNs},${b(o.initialized)},${q.w},${q.x},${q.y},${q.z},${a.yawDeg},${a.pitchDeg},${a.rollDeg}," +
                "${b(a.isVertical)},${o.gyroBias.x},${o.gyroBias.y},${o.gyroBias.z},${AngleMath.deg(o.sigmaYawRad)}," +
                "${AngleMath.radPerSecToDegPerMin(o.driftSigmaRadS)},${o.confidence},${o.warnings.joinToString("|") { it.name }}"
        }
    }

    object Vision {
        const val HEADER = "t1_ns,t2_ns,t_cam2_ns,t_applied_ns,seq,rvx,rvy,rvz,quality,n_inliers,inlier_ratio,spread," +
            "rms_err_rad,accepted,reject_reason,mahal2,zbx,zby,zbz"
        fun format(m: VisionMeasurement, o: VisionUpdateOutcome, tAppliedNs: Long, seq: Long): String {
            val rv = m.rotD1D2.log()
            val z = o.zBias
            return "${m.t1Ns},${m.t2Ns},${m.tCam2Ns},$tAppliedNs,$seq,${rv.x},${rv.y},${rv.z},${m.quality},${m.nInliers}," +
                "${m.inlierRatio},${m.spread},${m.rmsErrRad},${b(o.accepted)},${o.reason ?: ""},${o.mahalanobis2}," +
                "${z?.x ?: ""},${z?.y ?: ""},${z?.z ?: ""}"
        }
    }

    object VisionFrames {
        const val HEADER = "t_cam_prev_ns,t_cam_ns,t_ns,n_tracked,n_fb_ok,n_gated_out,n_inliers,rvx,rvy,rvz,valid," +
            "image_std,occlusion_state,proc_ms,ess_diff_deg"
        fun format(
            tCamPrevNs: Long, tCamNs: Long, tNs: Long, nTracked: Int, nFbOk: Int, nGatedOut: Int, nInliers: Int,
            rv: Vec3?, valid: Boolean, imageStd: Double, occlusion: String, procMs: Double,
        ): String = "$tCamPrevNs,$tCamNs,$tNs,$nTracked,$nFbOk,$nGatedOut,$nInliers,${rv?.x ?: ""},${rv?.y ?: ""}," +
            "${rv?.z ?: ""},${b(valid)},$imageStd,$occlusion,$procMs,"
    }

    object ArCore {
        const val HEADER = "t_ns,t_arcore_ns,qw,qx,qy,qz,yaw_deg,pitch_deg,roll_deg,tracking_state,failure_reason"
        fun format(r: ArCoreReading): String {
            val q = r.qWO
            return "${r.tNs},${r.tArCoreNs},${q.w},${q.x},${q.y},${q.z},${r.angles.yawDeg},${r.angles.pitchDeg}," +
                "${r.angles.rollDeg},${r.tracking},${r.failureReason.replace(',', ';')}"
        }
    }

    object Events {
        const val HEADER = "t_ns,seq,event,detail"
        /** [seq] orders estimator-affecting rows across events.csv and vision.csv (−1 = informational). */
        fun format(tNs: Long, seq: Long, event: String, detail: String) =
            "$tNs,$seq,$event,${detail.replace(',', ';').replace('\n', ' ')}"
    }

    private fun b(v: Boolean) = if (v) "1" else "0"

    /** Splits one CSV line (no quoting is ever produced by this schema). */
    fun split(line: String): List<String> = line.split(',')
}
