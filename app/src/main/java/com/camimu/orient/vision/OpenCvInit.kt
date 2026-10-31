package com.camimu.orient.vision

import android.util.Log
import org.opencv.android.OpenCVLoader

/** Loads the OpenCV native library once. */
object OpenCvInit {
    @Volatile private var ok: Boolean? = null

    @Synchronized
    fun ensure(): Boolean {
        ok?.let { return it }
        val r = runCatching { OpenCVLoader.initLocal() }.getOrElse { Log.e("CamIMU.Vision", "OpenCV load failed", it); false }
        ok = r
        Log.i("CamIMU.Vision", "OpenCV loaded=$r")
        return r
    }
}
