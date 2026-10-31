package com.camimu.orient.vision

import android.os.SystemClock
import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.core.fusion.GyroHistory
import com.camimu.orient.core.io.CsvSchema
import com.camimu.orient.core.math.Mat3
import com.camimu.orient.core.math.Vec3
import com.camimu.orient.core.model.GrayFrame
import com.camimu.orient.core.model.OcclusionState
import com.camimu.orient.core.model.Px
import com.camimu.orient.core.model.Track
import com.camimu.orient.core.model.VisionMeasurement
import com.camimu.orient.core.vision.AnchorWindow
import com.camimu.orient.core.vision.CameraModel
import com.camimu.orient.core.vision.GridBucketer
import com.camimu.orient.core.vision.GyroFlowPredictor
import com.camimu.orient.core.vision.OcclusionDetector
import com.camimu.orient.core.vision.RotationRansac
import com.camimu.orient.core.vision.VisionQuality
import kotlinx.coroutines.flow.MutableStateFlow
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import kotlin.math.hypot

/** Debug snapshot for the feature overlay (raw image coordinates). */
class FrameDebug(val width: Int, val height: Int, val inliers: List<Px>, val outliers: List<Px>, val gated: List<Px>)

/** Live vision statistics for the HUD debug panel. */
data class VisionStats(
    val fps: Double = 0.0, val procMs: Double = 0.0, val tracks: Int = 0, val inliers: Int = 0,
    val occlusion: OcclusionState = OcclusionState.OK, val windowsSent: Int = 0,
)

/** Receives pipeline outputs (called on the vision thread; implementations post to the imu thread). */
interface VisionSink {
    fun onMeasurement(m: VisionMeasurement)
    fun onOcclusion(state: OcclusionState)
    fun onFrameRow(line: String)
}

/**
 * Per-frame vision: track → gyro-gate → frame-pair RANSAC → anchor-window measurement (Architecture §6).
 * Runs only on the vision thread.
 */
class VisionPipeline(
    private val cfg: VisionConfig,
    camera: CameraModel,
    private val rCD: Mat3,
    private val width: Int,
    private val height: Int,
    private val gyroHistory: GyroHistory,
    private val biasProvider: () -> Vec3,
    private val sink: VisionSink,
) {
    private val cam = camera
    private val tracker = FeatureTracker(cfg, width, height)
    private val predictor = GyroFlowPredictor(camera, rCD)
    private val ransacPair = RotationRansac(cfg, 1L)
    private val ransacWindow = RotationRansac(cfg, 2L)
    private val bucketer = GridBucketer(width, height, cfg.gridCols, cfg.gridRows)
    private val window = AnchorWindow(cfg)
    private val occlusion = OcclusionDetector(cfg)
    private val gray = Mat(height, width, CvType.CV_8UC1)
    private val meanMat = MatOfDouble()
    private val stdMat = MatOfDouble()
    private val thresholdRad = cfg.ransacThresholdPx / camera.k.fx
    private var lastTNs: Long? = null
    private var lastTCamNs = 0L
    private var lastState = OcclusionState.OK
    private var gatedSinceAnchor = 0
    private var frameCount = 0
    private var windowsSent = 0
    private var fpsT0 = 0L
    private var fpsCount = 0
    private var fps = 0.0

    val debug = MutableStateFlow<FrameDebug?>(null)
    val stats = MutableStateFlow(VisionStats())

    fun onFrame(f: GrayFrame) {
        if (f.width != width || f.height != height) return
        val t0 = SystemClock.elapsedRealtimeNanos()
        gray.put(0, 0, f.data)
        Core.meanStdDev(gray, meanMat, stdMat)
        val imageStd = stdMat.toArray().firstOrNull() ?: 0.0

        val prevT = lastTNs
        val qGyro = if (prevT != null && f.tNs > prevT) gyroHistory.integrate(prevT, f.tNs, biasProvider()) else null
        val rC2C1 = qGyro?.let { predictor.cameraRotation(it).transpose() }
        val tr = tracker.process(gray, rC2C1?.let { r -> { p: Px -> predictor.predictWith(p, r) } })

        // Gyro-guided gating of tracks that moved this frame (obstacle layer 1).
        val all = tracker.tracks()
        val moved = all.filter { it.age > 0 }
        val gated = HashSet<Long>()
        if (rC2C1 != null && qGyro != null) {
            val gate = predictor.gatePx(qGyro, cfg)
            for (t in moved) {
                val p = predictor.predictWith(t.prev, rC2C1)
                if (p == null || hypot(p.u - t.cur.u, p.v - t.cur.v) > gate) gated += t.id
            }
            tracker.remove(gated)
            gatedSinceAnchor += moved.count { it.id in gated && it.anchor != null }
        }
        val kept = moved.filter { it.id !in gated }

        // Frame-pair rotation (debug, axis check, time-offset calibration).
        val pair = ransacPair.estimate(kept.map { cam.bearing(it.prev) }, kept.map { cam.bearing(it.cur) }, thresholdRad)
        val pairCells = IntArray(pair.nInliers)
        var k = 0
        val inl = ArrayList<Px>(); val outl = ArrayList<Px>()
        kept.forEachIndexed { i, t -> if (pair.inlierMask.getOrElse(i) { false }) { inl += t.cur; pairCells[k++] = bucketer.cellOf(t.cur) } else outl += t.cur }
        val pairQ = VisionQuality.evaluate(pair.nInliers, moved.size, pairCells, cfg)
        val rvPair = pair.q21?.let { AnchorWindow.deviceRotation(it, rCD).log() }

        // Occlusion state (obstacle layer 9).
        val state = occlusion.update(f.tNs, tracker.size, imageStd)
        if (state != lastState) {
            lastState = state
            sink.onOcclusion(state)
            if (state == OcclusionState.BLOCKED) tracker.reset()
            window.reset()
        }

        if (state == OcclusionState.OK) handleWindow(f, tracker.tracks()) else window.reset()

        val procMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        sink.onFrameRow(
            CsvSchema.VisionFrames.format(
                lastTCamNs, f.tCamNs, f.tNs, tracker.size, tr.nFbOk, gated.size, pair.nInliers, rvPair,
                pairQ.valid, imageStd, state.name, procMs,
            ),
        )
        lastTNs = f.tNs
        lastTCamNs = f.tCamNs
        frameCount++
        updateFps(f.tNs)
        if (cfg.debugOverlay && frameCount % 2 == 0) {
            debug.value = FrameDebug(width, height, inl, outl, moved.filter { it.id in gated }.map { it.cur })
        }
        stats.value = VisionStats(fps, procMs, tracker.size, pair.nInliers, state, windowsSent)
    }

    private fun handleWindow(f: GrayFrame, tracks: List<Track>) {
        val anchored = tracks.filter { it.anchor != null }
        when (window.onFrame(f.tNs, anchored.size)) {
            AnchorWindow.Decision.CONTINUE -> Unit
            AnchorWindow.Decision.RESET -> restartWindow(f.tNs)
            AnchorWindow.Decision.CLOSE -> {
                val t1 = window.anchorTNs!!
                val r = ransacWindow.estimate(anchored.map { cam.bearing(it.anchor!!) }, anchored.map { cam.bearing(it.cur) }, thresholdRad)
                val q21 = r.q21
                if (q21 != null) {
                    val cells = anchored.filterIndexed { i, _ -> r.inlierMask[i] }.map { bucketer.cellOf(it.cur) }.toIntArray()
                    val q = VisionQuality.evaluate(r.nInliers, anchored.size + gatedSinceAnchor, cells, cfg)
                    if (q.valid) {
                        sink.onMeasurement(AnchorWindow.buildMeasurement(t1, f.tNs, f.tCamNs, q21, rCD, q, r.nInliers, r.rmsErrRad))
                        windowsSent++
                    }
                }
                restartWindow(f.tNs)
            }
        }
    }

    private fun restartWindow(tNs: Long) {
        tracker.reanchorAll()
        gatedSinceAnchor = 0
        window.start(tNs, tracker.size)
    }

    private fun updateFps(tNs: Long) {
        if (fpsT0 == 0L) fpsT0 = tNs
        fpsCount++
        if (tNs - fpsT0 >= 1_000_000_000L) {
            fps = fpsCount * 1e9 / (tNs - fpsT0)
            fpsCount = 0
            fpsT0 = tNs
        }
    }

    fun release() {
        tracker.release()
        gray.release()
        meanMat.release()
        stdMat.release()
    }
}
