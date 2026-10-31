package com.camimu.orient.ar

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.camimu.orient.camera.YPlaneCopier
import com.camimu.orient.core.config.CameraIntrinsics
import com.camimu.orient.core.config.IntrinsicsSource
import com.camimu.orient.core.model.ArCoreReading
import com.camimu.orient.core.model.GrayFrame
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ARCore session + GLSurfaceView renderer. Publishes poses; in benchmark mode also hands ARCore's
 * CPU images to [onCpuFrame] (the receiver copies and processes them on the vision thread).
 */
class ArCoreController(
    private val activity: Activity,
    verticalPitchRad: Double,
    private val arcoreTimeOffsetNs: Long,
    private val onReading: (ArCoreReading) -> Unit,
    private val onCpuFrame: ((GrayFrame, CameraIntrinsics) -> Unit)?,
    private val onError: (String) -> Unit,
) : GLSurfaceView.Renderer {
    @Volatile private var session: Session? = null
    private val renderer = BackgroundRenderer()
    val poseReader = ArCorePoseReader(verticalPitchRad)
    @Volatile private var textureBound = false
    @Volatile private var geometryDirty = true
    private var viewW = 1
    private var viewH = 1
    private var buf = ByteArray(0)
    @Volatile private var clockOffsetNs: Long? = null

    /** Main thread, from ON_RESUME. */
    fun resume() {
        try {
            val s = session ?: Session(activity).also { s ->
                s.configure(Config(s).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.FIXED
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                })
                session = s
                textureBound = false
            }
            s.resume()
            geometryDirty = true
        } catch (e: Exception) {
            Log.e(TAG, "ARCore resume failed", e)
            onError("ARCore error: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    fun pause() {
        session?.pause()
    }

    fun close() {
        session?.close()
        session = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.04f, 0.04f, 1f)
        renderer.createOnGlThread()
        textureBound = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width; viewH = height
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!textureBound) { s.setCameraTextureName(renderer.textureId); textureBound = true }
        if (geometryDirty) { s.setDisplayGeometry(Surface.ROTATION_0, viewW, viewH); geometryDirty = false }
        val frame = try {
            s.update()
        } catch (e: CameraNotAvailableException) {
            onError("Camera not available to ARCore")
            return
        } catch (e: Exception) {
            Log.w(TAG, "update failed: ${e.message}")
            return
        }
        renderer.draw(frame)
        if (frame.timestamp == 0L) return
        onReading(poseReader.read(frame))
        onCpuFrame?.let { sendCpuFrame(frame, it) }
    }

    private fun sendCpuFrame(frame: Frame, sink: (GrayFrame, CameraIntrinsics) -> Unit) {
        try {
            frame.acquireCameraImage().use { img ->
                val w = img.width; val h = img.height
                if (buf.size != w * h) buf = ByteArray(w * h)
                val plane = img.planes[0]
                YPlaneCopier.copy(plane.buffer, plane.rowStride, w, h, buf)
                val tAr = frame.timestamp
                // ARCore's time base is undocumented: detect it once (same base as elapsedRealtime, or
                // System.nanoTime). Any residual constant offset is calibrated into arcoreTimeOffsetNs.
                if (clockOffsetNs == null) clockOffsetNs = detectClockOffset(tAr)
                val ci = frame.camera.imageIntrinsics
                val f = ci.focalLength; val pp = ci.principalPoint; val dims = ci.imageDimensions
                val sx = w.toDouble() / dims[0]; val sy = h.toDouble() / dims[1]
                val k = CameraIntrinsics(w, h, f[0] * sx, f[1] * sy, pp[0] * sx, pp[1] * sy, source = IntrinsicsSource.ARCORE)
                sink(GrayFrame(tAr, tAr + (clockOffsetNs ?: 0L) + arcoreTimeOffsetNs, w, h, buf), k)
            }
        } catch (e: NotYetAvailableException) {
            // no CPU image this frame
        } catch (e: Exception) {
            Log.w(TAG, "cpu image failed: ${e.message}")
        }
    }

    val measuredClockOffsetNs get() = clockOffsetNs

    private fun detectClockOffset(tAr: Long): Long {
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val mono = System.nanoTime()
        return when {
            elapsed - tAr in 0..MAX_LAG_NS -> 0L
            mono - tAr in 0..MAX_LAG_NS -> elapsed - mono
            else -> elapsed - tAr - TYPICAL_LATENCY_NS
        }.also { Log.i(TAG, "ARCore clock offset ${it / 1e6} ms") }
    }

    companion object {
        private const val TAG = "CamIMU.Ar"
        private const val MAX_LAG_NS = 500_000_000L
        private const val TYPICAL_LATENCY_NS = 40_000_000L
    }
}
