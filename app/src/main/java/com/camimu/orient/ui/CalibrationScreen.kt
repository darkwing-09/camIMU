package com.camimu.orient.ui

import android.util.Size
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.camimu.orient.pipeline.AppMode
import com.camimu.orient.pipeline.OrientationSession
import com.camimu.orient.ui.hud.DebugLine
import com.camimu.orient.ui.hud.HudButton
import com.camimu.orient.ui.theme.Hud
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures checkerboard images at the exact analysis resolution/settings used by CUSTOM mode.
 * Run tools/calibrate_intrinsics.py on the pulled folder, then push calibration.json back.
 */
@Composable
fun CalibrationScreen(deps: AppDeps, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val vcfg = remember { deps.settings.vision() }
    val dir = remember { OrientationSession.newSessionDir(ctx, AppMode.CALIBRATE) }
    var count by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf("") }
    val capture = remember { AtomicBoolean(false) }
    val exec = remember { Executors.newSingleThreadExecutor { Thread(it, "calib") } }
    val previewView = remember {
        PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(Unit) {
        val src = CameraXFrameSource(ctx, owner, previewView, Size(vcfg.analysisWidth, vcfg.analysisHeight), 0L, exec)
        src.start(
            onInfo = { info = "sensor orientation ${it.sensorOrientationDeg}°, OIS ${it.oisAvailable}, ts ${it.timestampSourceName}" },
            onFrame = { f ->
                if (capture.compareAndSet(true, false)) {
                    val m = Mat(f.height, f.width, CvType.CV_8UC1)
                    m.put(0, 0, f.data)
                    val name = String.format(Locale.ROOT, "calib_%03d.png", count + 1)
                    if (Imgcodecs.imwrite(File(dir, name).absolutePath, m)) count++
                    m.release()
                }
            },
            onError = { info = it },
        )
        onDispose { src.stop(); exec.shutdown() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            TopBar("CALIBRATE", onBack)
            Column(Modifier.padding(8.dp).background(Hud.Scrim, RoundedCornerShape(6.dp)).padding(8.dp)) {
                Text("Checkerboard 9×6 inner corners. Capture 20–30 views: all image corners, tilts ±30–45°, different distances.",
                    color = Hud.Text, style = MaterialTheme.typography.bodySmall)
                DebugLine(info)
                DebugLine("saved $count → ${dir.absolutePath}")
                DebugLine(deps.calibration.load().status)
                DebugLine("push result to: ${deps.calibration.file.absolutePath}")
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HudButton("CAPTURE (${count})", { capture.set(true) }, Modifier.weight(1f))
        }
    }
}
