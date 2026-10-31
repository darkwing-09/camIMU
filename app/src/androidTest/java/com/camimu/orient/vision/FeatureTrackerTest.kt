package com.camimu.orient.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.camimu.orient.core.config.VisionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Runs on the phone: a known image shift must be recovered by the KLT tracker. */
@RunWith(AndroidJUnit4::class)
class FeatureTrackerTest {
    @Before fun init() = assertTrue(OpenCvInit.ensure())

    @Test fun recoversKnownShift() {
        val w = 640; val h = 480
        val noise = Mat(h, w, CvType.CV_8UC1)
        Core.randu(noise, 0.0, 255.0)
        val img1 = Mat()
        Imgproc.GaussianBlur(noise, img1, Size(7.0, 7.0), 2.0)
        val shift = Mat(2, 3, CvType.CV_64F).apply { put(0, 0, 1.0, 0.0, 5.0, 0.0, 1.0, -3.0) }
        val img2 = Mat()
        Imgproc.warpAffine(img1, img2, shift, Size(w.toDouble(), h.toDouble()))
        val t = FeatureTracker(VisionConfig(), w, h)
        t.process(img1, null)
        t.process(img2, null)
        val moved = t.tracks().filter { it.age > 0 && it.cur.u in 20.0..620.0 && it.cur.v in 20.0..460.0 }
        assertTrue("tracks ${moved.size}", moved.size >= 100)
        val du = moved.map { it.cur.u - it.prev.u }.sorted()[moved.size / 2]
        val dv = moved.map { it.cur.v - it.prev.v }.sorted()[moved.size / 2]
        assertEquals(5.0, du, 0.2)
        assertEquals(-3.0, dv, 0.2)
        t.release()
        MatOfDouble().release()
    }
}
