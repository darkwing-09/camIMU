package com.camimu.orient.replay

import com.camimu.orient.core.CoreInfo
import com.camimu.orient.core.config.ConfigJson
import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.io.CsvSchema
import com.camimu.orient.core.io.SessionReader
import com.camimu.orient.core.math.AngleMath
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: replay --session <dir> [--config <estimator.json>] [--out <dir>] [--no-vision]
       replay --synthesize <dir>     (writes a synthetic session with known truth, t_d = 12 ms)
  Re-runs the orientation estimator on a recorded session and writes <out>/est_replay.csv.
  Default config: the estimatorConfig stored in <session>/meta.json. Default out: <session>/replay."""

fun main(args: Array<String>) {
    val opts = parseArgs(args)
    opts["synthesize"]?.let {
        Synthesizer.write(File(it))
        println("wrote synthetic session to $it")
        return
    }
    if ("help" in opts || "session" !in opts) {
        println(USAGE)
        exitProcess(if ("help" in opts) 0 else 2)
    }
    val dir = File(opts.getValue("session"))
    require(dir.isDirectory) { "not a directory: $dir" }
    val reader = SessionReader(dir)
    val cfg: EstimatorConfig = opts["config"]?.let { ConfigJson.decodeEstimator(File(it).readText()) }
        ?: reader.meta()?.estimatorConfig ?: EstimatorConfig()
    val samples = reader.imu()
    require(samples.isNotEmpty()) { "no imu.csv rows in $dir" }
    val res = Replayer.run(samples, reader.actions(includeVision = "no-vision" !in opts), cfg)
    val out = File(opts["out"] ?: File(dir, "replay").path).apply { mkdirs() }
    File(out, "est_replay.csv").bufferedWriter().use { w ->
        w.write(CsvSchema.Est.HEADER); w.newLine()
        res.outputs.forEachIndexed { i, o -> if (i % cfg.estLogDecimation == 0) { w.write(CsvSchema.Est.format(o)); w.newLine() } }
    }
    File(out, "config_used.json").writeText(ConfigJson.encode(cfg))
    val last = res.outputs.last()
    val dur = (samples.last().tNs - samples.first().tNs) * 1e-9
    println("replay (core ${CoreInfo.VERSION}): ${samples.size} samples, %.1f s".format(dur))
    println("vision windows: ${res.visionAccepted}/${res.visionTotal} accepted")
    println("final yaw/pitch/roll: %.3f / %.3f / %.3f deg".format(last.angles.yawDeg, last.angles.pitchDeg, last.angles.rollDeg))
    println("final bias (deg/s): %.4f %.4f %.4f".format(AngleMath.deg(last.gyroBias.x), AngleMath.deg(last.gyroBias.y), AngleMath.deg(last.gyroBias.z)))
    println("wrote ${File(out, "est_replay.csv")}")
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val m = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--session", "--config", "--out", "--synthesize" -> { m[a.removePrefix("--")] = args.getOrElse(i + 1) { "" }; i++ }
            "--no-vision" -> m["no-vision"] = "1"
            "-h", "--help" -> m["help"] = "1"
            else -> { System.err.println("unknown argument: $a"); m["help"] = "1" }
        }
        i++
    }
    return m
}
