package com.camimu.orient.pipeline

import android.content.Context
import android.os.Build
import android.util.Log
import com.camimu.orient.BuildConfig
import com.camimu.orient.camera.CameraInfoData
import com.camimu.orient.core.CoreInfo
import com.camimu.orient.core.config.CalibrationData
import com.camimu.orient.core.config.CameraIntrinsics
import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.core.frames.Frames
import com.camimu.orient.core.io.SessionMeta
import com.camimu.orient.core.model.ArCoreReading
import com.camimu.orient.core.model.GrayFrame
import com.camimu.orient.core.model.OcclusionState
import com.camimu.orient.core.model.VisionMeasurement
import com.camimu.orient.core.vision.CameraModel
import com.camimu.orient.logging.SessionLogger
import com.camimu.orient.sensors.ImuSource
import com.camimu.orient.vision.FrameDebug
import com.camimu.orient.vision.VisionPipeline
import com.camimu.orient.vision.VisionSink
import com.camimu.orient.vision.VisionStats
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class AppMode { ARCORE, CUSTOM, CUSTOM_IMU_ONLY, BENCHMARK, CALIBRATE }

/**
 * One running estimator session: IMU thread + estimator, optional vision thread, optional recording.
 * Create, [start], feed frames via [onFrame] (vision thread) and [stop] when the screen goes away.
 */
class OrientationSession(
    private val context: Context,
    val mode: AppMode,
    estimatorConfig: EstimatorConfig,
    private val visionConfig: VisionConfig,
    private val calibration: CalibrationData,
    record: Boolean,
) {
    private val estCfg = calibration.applyTo(estimatorConfig)
    val sessionDir: File? = if (record) newSessionDir(context, mode) else null
    private val logger = sessionDir?.let { SessionLogger(it, estCfg.loggerFlushMs) }
    val runner = EstimatorRunner(estCfg, logger)
    private val imu = ImuSource(context, estCfg.maxAccelAgeNs, runner::onImu)
    val visionExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "vision") }
    private val visionBusy = AtomicBoolean(false)
    @Volatile private var pipeline: VisionPipeline? = null
    @Volatile var cameraInfo: CameraInfoData? = null
    @Volatile var intrinsicsUsed: CameraIntrinsics? = null
        private set
    private val startWall = Date()
    private val notes = HashMap<String, String>()

    val frameDebug = MutableStateFlow<FrameDebug?>(null)
    val visionStats = MutableStateFlow(VisionStats())
    val arcoreReading = MutableStateFlow<ArCoreReading?>(null)
    @Volatile var started = false
        private set
    @Volatile var error: String? = null
        private set

    val isRecording get() = logger != null

    fun start() {
        runner.handler = imu.handler
        if (!imu.start(estCfg.imuSamplingPeriodUs)) {
            error = "This phone has no gyroscope/accelerometer."
            return
        }
        started = true
        runner.postEvent("SESSION_START", mode.name)
        Log.i(TAG, "session $mode started, recording=${sessionDir?.absolutePath}")
    }

    fun note(key: String, value: String) = synchronized(notes) { notes[key] = value }

    /** Vision thread (CameraX analyzer runs on [visionExecutor]). */
    fun onFrame(f: GrayFrame, arcoreIntrinsics: CameraIntrinsics? = null) {
        if (!started) return
        val p = pipeline ?: createPipeline(f.width, f.height, arcoreIntrinsics).also { pipeline = it }
        p.onFrame(f)
        frameDebug.value = p.debug.value
        visionStats.value = p.stats.value
    }

    /** From another thread (ARCore GL thread): submit only if the vision thread is idle; the frame data is copied. */
    fun submitFrame(f: GrayFrame, arcoreIntrinsics: CameraIntrinsics?) {
        if (!visionBusy.compareAndSet(false, true)) return
        val copy = GrayFrame(f.tCamNs, f.tNs, f.width, f.height, f.data.copyOf())
        try {
            visionExecutor.execute {
                try { onFrame(copy, arcoreIntrinsics) } finally { visionBusy.set(false) }
            }
        } catch (e: Exception) {
            visionBusy.set(false)
        }
    }

    fun onArCore(r: ArCoreReading) {
        arcoreReading.value = r
        logger?.arcore(r)
    }

    private fun createPipeline(w: Int, h: Int, arcoreIntrinsics: CameraIntrinsics?): VisionPipeline {
        val info = cameraInfo
        val orientation = calibration.sensorOrientationDegOverride ?: info?.sensorOrientationDeg ?: DEFAULT_SENSOR_ORIENTATION
        val rCD = Frames.rCDForSensorOrientation(orientation)
        val k = when {
            arcoreIntrinsics != null -> arcoreIntrinsics
            calibration.intrinsics?.scaledTo(w, h) != null -> calibration.intrinsics!!.scaledTo(w, h)!!
            info?.focalLengthMm != null && info.sensorWidthMm != null ->
                CameraModel.fovEstimate(w, h, info.focalLengthMm, info.sensorWidthMm)
            else -> CameraIntrinsics(w, h, w * DEFAULT_FX_FRACTION, w * DEFAULT_FX_FRACTION, w / 2.0, h / 2.0)
        }
        intrinsicsUsed = k
        Log.i(TAG, "vision pipeline ${w}x$h orientation=$orientation intrinsics=$k")
        val sink = object : VisionSink {
            override fun onMeasurement(m: VisionMeasurement) = runner.postVision(m)
            override fun onOcclusion(state: OcclusionState) = runner.postVisionState(state)
            override fun onFrameRow(line: String) { logger?.frame(line) }
        }
        return VisionPipeline(visionConfig, CameraModel(k), rCD, w, h, runner.gyroHistory, { runner.latestBias }, sink)
    }

    fun stop() {
        if (!started) { imu.quit(); visionExecutor.shutdownNow(); return }
        started = false
        runner.postEvent("SESSION_END", "")
        imu.quit()
        visionExecutor.execute { pipeline?.release(); pipeline = null }
        visionExecutor.shutdown()
        visionExecutor.awaitTermination(2, TimeUnit.SECONDS)
        logger?.close(buildMeta())
    }

    private fun buildMeta(): SessionMeta {
        val info = cameraInfo
        val k = intrinsicsUsed
        val orientation = calibration.sensorOrientationDegOverride ?: info?.sensorOrientationDeg ?: DEFAULT_SENSOR_ORIENTATION
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return SessionMeta(
            appVersion = BuildConfig.VERSION_NAME, coreVersion = CoreInfo.VERSION,
            deviceManufacturer = Build.MANUFACTURER, deviceModel = Build.MODEL, androidSdk = Build.VERSION.SDK_INT,
            startWallClockIso = iso.format(startWall), mode = mode.name,
            estimatorConfig = estCfg, visionConfig = visionConfig, calibration = calibration,
            registeredSensorTypes = imu.registeredTypes.toList(), sensorNames = imu.sensorNames.toList(),
            sensorClock = imu.timeSync.sensorClock.name, measuredGyroHz = imu.gyroRate.hz, measuredAccelHz = imu.accelRate.hz,
            cameraTimestampSource = if (mode == AppMode.BENCHMARK) "ARCORE" else info?.timestampSourceName ?: "",
            sensorOrientationDeg = orientation, rCD = Frames.rCDForSensorOrientation(orientation).toRowMajor().toList(),
            analysisWidth = k?.width ?: 0, analysisHeight = k?.height ?: 0, intrinsicsSource = k?.source?.name ?: "",
            notes = synchronized(notes) { HashMap(notes) },
        )
    }

    val gyroHz get() = imu.gyroRate.hz
    val sensorClockName get() = imu.timeSync.sensorClock.name

    companion object {
        private const val TAG = "CamIMU.Session"
        const val DEFAULT_SENSOR_ORIENTATION = 90
        /** Fallback fx ≈ 0.8·width (≈64° horizontal FOV) when no camera metadata is available. */
        const val DEFAULT_FX_FRACTION = 0.8

        fun sessionsRoot(context: Context): File = File(context.getExternalFilesDir(null), "sessions")

        fun newSessionDir(context: Context, mode: AppMode): File {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
            return File(sessionsRoot(context), "${stamp}_${mode.name}").apply { mkdirs() }
        }
    }
}

/** Intrinsics source shown in the UI. */
fun CameraIntrinsics?.label(): String = this?.let { "${it.source} fx=%.1f".format(Locale.ROOT, it.fx) } ?: "—"

