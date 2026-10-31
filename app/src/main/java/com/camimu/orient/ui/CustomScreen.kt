package com.camimu.orient.ui

import android.util.Size
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.camimu.orient.camera.CameraXFrameSource
import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.pipeline.AppMode
import com.camimu.orient.pipeline.OrientationSession
import com.camimu.orient.pipeline.label
import com.camimu.orient.ui.hud.ConfidenceBadge
import com.camimu.orient.ui.hud.DebugLine
import com.camimu.orient.ui.hud.FeatureOverlay
import com.camimu.orient.ui.hud.HudPanel
import com.camimu.orient.ui.hud.WarningsBanner
import com.camimu.orient.ui.theme.Hud
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** CUSTOM (camera + IMU) and CUSTOM_IMU_ONLY modes. Pressing REC restarts the session with logging. */
@Composable
fun CustomScreen(mode: AppMode, deps: AppDeps, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var recording by remember { mutableStateOf(false) }
    var sessionKey by remember { mutableIntStateOf(0) }
    var lastSaved by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val calib = remember(sessionKey) { deps.calibration.load() }
    val vcfg = remember(sessionKey) { deps.settings.vision() }
    val session = remember(sessionKey) {
        OrientationSession(ctx, mode, deps.settings.estimator(), vcfg, calib.data, recording)
    }
    DisposableEffect(session) {
        session.start()
        onDispose {
            session.stop()
            session.sessionDir?.let { lastSaved = it.absolutePath }
        }
    }
    val previewView = remember {
        PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    if (mode == AppMode.CUSTOM) {
        DisposableEffect(session) {
            val exec = Executor { r ->
                try { session.visionExecutor.execute(r) } catch (_: RejectedExecutionException) { /* session stopping */ }
            }
            val src = CameraXFrameSource(ctx, lifecycleOwner, previewView, Size(vcfg.analysisWidth, vcfg.analysisHeight), calib.data.timeOffsetNs, exec, vcfg.lockFocusInfinity)
            src.start(onInfo = { session.cameraInfo = it }, onFrame = { session.onFrame(it) }, onError = { cameraError = it })
            onDispose { src.stop() }
        }
    }
    val out by session.runner.output.collectAsState()
    val stats by session.visionStats.collectAsState()
    val debug by session.frameDebug.collectAsState()

    Box(Modifier.fillMaxSize().background(Hud.Background)) {
        if (mode == AppMode.CUSTOM) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            FeatureOverlay(debug, session.cameraInfo?.sensorOrientationDeg ?: OrientationSession.DEFAULT_SENSOR_ORIENTATION)
        }
        Column(Modifier.fillMaxSize()) {
            TopBar(if (mode == AppMode.CUSTOM) "CUSTOM" else "IMU ONLY", onBack) {
                val o = out
                if (o != null) ConfidenceBadge(o.confidence, if (o.initialized) AngleMath.deg(o.sigmaYawRad) else null)
            }
            Column(Modifier.padding(8.dp)) {
                WarningsBanner(out?.warnings ?: emptySet())
                (session.error ?: cameraError)?.let { DebugLine("ERROR: $it") }
                Column(Modifier.background(Hud.Scrim, RoundedCornerShape(4.dp)).padding(6.dp)) {
                    val o = out
                    DebugLine(String.format(Locale.ROOT, "gyro %.0f Hz · clock %s", session.gyroHz, session.sensorClockName))
                    if (o != null && o.initialized) {
                        DebugLine(
                            String.format(
                                Locale.ROOT, "bias °/s %+.3f %+.3f %+.3f · drift σ %.2f°/min",
                                AngleMath.deg(o.gyroBias.x), AngleMath.deg(o.gyroBias.y), AngleMath.deg(o.gyroBias.z),
                                AngleMath.radPerSecToDegPerMin(o.driftSigmaRadS),
                            ),
                        )
                    }
                    if (mode == AppMode.CUSTOM) {
                        DebugLine(
                            String.format(
                                Locale.ROOT, "vision %.0f fps · %.1f ms · tracks %d · inl %d · %s",
                                stats.fps, stats.procMs, stats.tracks, stats.inliers, stats.occlusion,
                            ),
                        )
                        DebugLine("windows ${session.runner.visionAccepted.get()}/${session.runner.visionTotal.get()} accepted · K ${session.intrinsicsUsed.label()}")
                    }
                    if (recording) DebugLine("● REC → ${session.sessionDir?.name}")
                    else lastSaved?.let { DebugLine("saved: $it") }
                }
            }
        }
        HudPanel(
            angles = out?.takeIf { it.initialized }?.angles,
            recording = recording,
            onResetYaw = { session.runner.postResetYaw() },
            onMark = { session.runner.postEvent("MARK") },
            onToggleRecord = { recording = !recording; sessionKey++ },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}
