package com.camimu.orient.vision

import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.core.model.Px
import com.camimu.orient.core.model.Track
import com.camimu.orient.core.vision.GridBucketer
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.hypot

/** Grid-bucketed Shi–Tomasi detection + pyramidal KLT with gyro-seeded initial flow and a forward–backward check. */
class FeatureTracker(private val cfg: VisionConfig, private val width: Int, private val height: Int) {
    private class T(val id: Long, var anchor: Px?, var prev: Px, var cur: Px, var age: Int)

    data class Result(val nBeforeFb: Int, val nFbOk: Int, val replenished: Int)

    private val tracks = ArrayList<T>()
    private val bucketer = GridBucketer(width, height, cfg.gridCols, cfg.gridRows)
    private var prevGray: Mat? = null
    private val mask = Mat(height, width, CvType.CV_8UC1)
    private var nextId = 0L
    private val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 30, 0.01)
    private val win = Size(cfg.kltWinSize.toDouble(), cfg.kltWinSize.toDouble())

    val size get() = tracks.size

    /** [gray] CV_8UC1, valid only during this call. [predict] maps a previous pixel to its predicted position. */
    fun process(gray: Mat, predict: ((Px) -> Px?)?): Result {
        var nBefore = 0
        var nOk = 0
        val pg = prevGray
        if (pg != null && tracks.isNotEmpty()) {
            nBefore = tracks.size
            val prevPts = MatOfPoint2f(*tracks.map { Point(it.cur.u, it.cur.v) }.toTypedArray())
            val guess = tracks.map { t -> predict?.invoke(t.cur)?.takeIf { inside(it) } ?: t.cur }
            val nextPts = MatOfPoint2f(*guess.map { Point(it.u, it.v) }.toTypedArray())
            val backPts = MatOfPoint2f(*tracks.map { Point(it.cur.u, it.cur.v) }.toTypedArray())
            val st1 = MatOfByte(); val st2 = MatOfByte(); val e1 = MatOfFloat(); val e2 = MatOfFloat()
            Video.calcOpticalFlowPyrLK(pg, gray, prevPts, nextPts, st1, e1, win, cfg.kltMaxLevel, criteria, Video.OPTFLOW_USE_INITIAL_FLOW, 1e-4)
            Video.calcOpticalFlowPyrLK(gray, pg, nextPts, backPts, st2, e2, win, cfg.kltMaxLevel, criteria, Video.OPTFLOW_USE_INITIAL_FLOW, 1e-4)
            val s1 = st1.toArray(); val s2 = st2.toArray(); val n = nextPts.toArray(); val b = backPts.toArray()
            val survivors = ArrayList<T>(tracks.size)
            for (i in tracks.indices) {
                val t = tracks[i]
                val p = Px(n[i].x, n[i].y)
                val fb = hypot(b[i].x - t.cur.u, b[i].y - t.cur.v)
                if (s1[i].toInt() == 1 && s2[i].toInt() == 1 && inside(p) && fb < cfg.fbThresholdPx) {
                    t.prev = t.cur; t.cur = p; t.age++
                    survivors += t
                }
            }
            tracks.clear(); tracks.addAll(survivors)
            nOk = survivors.size
            listOf(prevPts, nextPts, backPts, st1, st2, e1, e2).forEach { it.release() }
        } else {
            tracks.forEach { it.prev = it.cur }
        }
        var added = 0
        if (tracks.size < cfg.replenishFraction * cfg.targetTracks) added = replenish(gray)
        if (prevGray == null) prevGray = Mat()
        gray.copyTo(prevGray)
        return Result(nBefore, nOk, added)
    }

    private fun replenish(gray: Mat): Int {
        mask.setTo(Scalar(255.0))
        val r = cfg.minDistancePx.toInt()
        for (t in tracks) Imgproc.circle(mask, Point(t.cur.u, t.cur.v), r, Scalar(0.0), -1)
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(gray, corners, cfg.detectMaxCorners, cfg.qualityLevel, cfg.minDistancePx, mask, cfg.blockSize, false, 0.04)
        val cands = corners.toArray().map { Px(it.x, it.y) }
        corners.release()
        val sel = bucketer.select(cands, tracks.map { it.cur }, cfg.maxPerCell, cfg.targetTracks)
        for (p in sel) tracks += T(nextId++, null, p, p, 0)
        return sel.size
    }

    private fun inside(p: Px) = p.u >= 0 && p.v >= 0 && p.u <= width - 1 && p.v <= height - 1

    fun tracks(): List<Track> = tracks.map { Track(it.id, it.anchor, it.prev, it.cur, it.age) }

    fun remove(ids: Set<Long>) {
        if (ids.isNotEmpty()) tracks.removeAll { it.id in ids }
    }

    /** Anchor := current position for every live track (start of a new window). */
    fun reanchorAll() = tracks.forEach { it.anchor = it.cur }

    fun reset() {
        tracks.clear()
        prevGray?.release()
        prevGray = null
    }

    fun release() {
        reset()
        mask.release()
    }
}
