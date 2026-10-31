package com.camimu.orient.ui

import android.app.Activity
import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.camimu.orient.ar.ArCoreAvailability
import com.camimu.orient.ar.ArCoreController
import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.model.ArTracking
import com.camimu.orient.core.model.Confidence
import com.camimu.orient.core.model.OrientationWarning
import com.camimu.orient.pipeline.AppMode
import com.camimu.orient.pipeline.OrientationSession
import com.camimu.orient.pipeline.label
import com.camimu.orient.ui.hud.ComparisonPanel
import com.camimu.orient.ui.hud.ConfidenceBadge
import com.camimu.orient.ui.hud.DebugLine
import com.camimu.orient.ui.hud.FeatureOverlay
import com.camimu.orient.ui.hud.HISTORY
import com.camimu.orient.ui.hud.HudPanel
import com.camimu.orient.ui.hud.WarningsBanner
import kotlinx.coroutines.delay
import java.util.Locale

/** ARCORE (reference only) and BENCHMARK (ARCore + custom estimator on ARCore's CPU images). */
@Composable
fun ArCoreScreen(deps: AppDeps, benchmark: Boolean, onBack: () -> Unit) {
    val activity = LocalContext.current as Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    var recording by remember { mutableStateOf(false) }
    var sessionKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastSaved by remember { mutableStateOf<String?>(null) }
    val calib = remember(sessionKey) { deps.calibration.load() }
    val estCfg = remember(sessionKey) { deps.settings.estimator() }
    val mode = if (benchmark) AppMode.BENCHMARK else AppMode.ARCORE
    val session = remember(sessionKey) {
        OrientationSession(activity, mode, estCfg, deps.settings.vision(), calib.data, recording)
    }
    val controller = remember(sessionKey) {
        ArCoreController(
            activity, AngleMath.rad(estCfg.verticalPitchDeg), calib.data.arcoreTimeOffsetNs,
            onReading = { session.onArCore(it) },
            onCpuFrame = if (benchmark) { f, k -> session.submitFrame(f, k) } else null,
            onError = { error = it },
        )
    }
    val glView = remember(sessionKey) {
        GLSurfaceView(activity).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(controller)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }
    DisposableEffect(session) {
        session.start()
        var installRequested = false
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    if (ArCoreAvailability.ensureInstalled(activity, !installRequested)) {
                        controller.resume(); glView.onResume()
                    } else installRequested = true
                }
                Lifecycle.Event.ON_PAUSE -> { glView.onPause(); controller.pause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glView.onPause(); controller.pause(); controller.close()
            controller.measuredClockOffsetNs?.let { session.note("arcoreClockOffsetNs", it.toString()) }
            session.stop()
            session.sessionDir?.let { lastSaved = it.absolutePath }
        }
    }
    val ar by session.arcoreReading.collectAsState()
    val out by session.runner.output.collectAsState()
    val stats by session.visionStats.collectAsState()
    val debug by session.frameDebug.collectAsState()
    val history = remember(sessionKey) { mutableStateListOf<Double>() }
    if (benchmark) {
        LaunchedEffect(sessionKey) {
            while (true) {
                delay(100)
                val c = session.runner.output.value
                val a = session.arcoreReading.value
                if (c != null && c.initialized && a != null && a.tracking == ArTracking.TRACKING) {
                    history += AngleMath.deg(AngleMath.angleDiff(c.angles.yawRad, a.angles.yawRad))
                    if (history.size > HISTORY) history.removeAt(0)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ glView }, Modifier.fillMaxSize())
        if (benchmark) FeatureOverlay(debug, OrientationSession.DEFAULT_SENSOR_ORIENTATION)
        Column(Modifier.fillMaxSize()) {
            TopBar(if (benchmark) "BENCHMARK" else "ARCORE", onBack) {
                val conf = if (benchmark) out?.confidence ?: Confidence.LOW
                else if (ar?.tracking == ArTracking.TRACKING) Confidence.HIGH else Confidence.LOW
                ConfidenceBadge(conf, if (benchmark) out?.takeIf { it.initialized }?.let { AngleMath.deg(it.sigmaYawRad) } else null)
            }
            Column(Modifier.padding(8.dp)) {
                val warnings = buildSet {
                    if (ar != null && ar?.tracking != ArTracking.TRACKING) add(OrientationWarning.TRACKING_LOST)
                    if (benchmark) out?.warnings?.let { addAll(it) }
                }
                WarningsBanner(warnings)
                ar?.let { if (it.tracking != ArTracking.TRACKING) DebugLine("ARCore: ${it.tracking} (${it.failureReason})") }
                error?.let { DebugLine("ERROR: $it") }
                if (benchmark) {
                    DebugLine(
                        String.format(Locale.ROOT, "vision %.0f fps · %.1f ms · tracks %d · %s · K %s",
                            stats.fps, stats.procMs, stats.tracks, stats.occlusion, session.intrinsicsUsed.label()),
                    )
                    ComparisonPanel(out?.takeIf { it.initialized }?.angles, ar?.angles, history.toList(), Modifier.fillMaxWidth())
                }
                if (recording) DebugLine("● REC → ${session.sessionDir?.name}") else lastSaved?.let { DebugLine("saved: $it") }
            }
        }
        HudPanel(
            angles = if (ar?.tracking == ArTracking.TRACKING) ar?.angles else null,
            recording = recording,
            onResetYaw = { controller.poseReader.requestResetYaw(); session.runner.postResetYaw() },
            onMark = { session.runner.postEvent("MARK") },
            onToggleRecord = { recording = !recording; sessionKey++ },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}
