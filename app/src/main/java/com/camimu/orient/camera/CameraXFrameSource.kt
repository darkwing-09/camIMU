package com.camimu.orient.camera

import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.camimu.orient.core.model.GrayFrame
import com.camimu.orient.sensors.TimeSync
import java.util.concurrent.Executor

/**
 * CameraX back camera: 4:3 preview + 640×480 gray analysis stream with OIS/EIS and autofocus off.
 * Frames are delivered on [analysisExecutor] with timestamps on the IMU clock.
 */
class CameraXFrameSource(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView?,
    private val analysisSize: Size,
    private val timeOffsetNs: Long,
    private val analysisExecutor: Executor,
    /** true = focus locked at infinity (best geometric accuracy, blurry < ~1 m); false = continuous video AF. */
    private val lockFocusInfinity: Boolean = false,
) {
    private var provider: ProcessCameraProvider? = null
    @Volatile private var clockOffsetNs = 0L

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun start(onInfo: (CameraInfoData) -> Unit, onFrame: (GrayFrame) -> Unit, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).firstOrNull()
                    ?: return@addListener onError("no back camera")
                val data = CameraInfoReader.read(info)
                clockOffsetNs = if (data.timestampRealtime) 0L else TimeSync.monotonicToElapsedOffsetNs()
                onInfo(data)

                val res = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(analysisSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val ab = ImageAnalysis.Builder().setResolutionSelector(res)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Preview: sharp 4:3 stream for the user (same FOV as the analysis stream).
                val pb = Preview.Builder().setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(PREVIEW_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build(),
                )
                applyCaptureOptions(Camera2Interop.Extender(ab))
                applyCaptureOptions(Camera2Interop.Extender(pb))

                val analysis = ab.build()
                var buf = ByteArray(0)
                analysis.setAnalyzer(analysisExecutor) { img ->
                    try {
                        val n = img.width * img.height
                        if (buf.size != n) buf = ByteArray(n)
                        val plane = img.planes[0]
                        YPlaneCopier.copy(plane.buffer, plane.rowStride, img.width, img.height, buf)
                        val tCam = img.imageInfo.timestamp
                        onFrame(GrayFrame(tCam, tCam + clockOffsetNs + timeOffsetNs, img.width, img.height, buf))
                    } catch (e: Exception) {
                        Log.w(TAG, "frame error: ${e.message}")
                    } finally {
                        img.close() // always close, or the stream stalls
                    }
                }
                p.unbindAll()
                if (previewView != null) {
                    val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } else {
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                }
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                onError("camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun <T> applyCaptureOptions(e: Camera2Interop.Extender<T>) {
        e.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        e.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (lockFocusInfinity) {
            e.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            e.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f) // infinity; ignored by fixed-focus lenses
        } else {
            // Smooth continuous AF: sharp image at any distance; focus breathing changes FOV by ~1% (ADR-024).
            e.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
    }

    /** Main thread. */
    fun stop() {
        provider?.unbindAll()
        provider = null
    }

    companion object {
        private const val TAG = "CamIMU.Camera"
        private val PREVIEW_SIZE = Size(1440, 1080)
    }
}
