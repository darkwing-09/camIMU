package com.camimu.orient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.pipeline.OrientationSession
import com.camimu.orient.ui.hud.DebugLine
import com.camimu.orient.ui.hud.HudButton
import com.camimu.orient.ui.theme.Hud
import java.util.Locale
import kotlin.math.roundToInt

/** Key tunables (docs/Implementation.md "Tuning guide"); applied at the next session start. */
@Composable
fun SettingsScreen(deps: AppDeps, onBack: () -> Unit) {
    val ctx = LocalContext.current
    SettingsContent(
        initialEst = deps.settings.estimator(), initialVis = deps.settings.vision(),
        calibrationStatus = deps.calibration.load().status, calibrationPath = deps.calibration.file.absolutePath,
        sessionsPath = OrientationSession.sessionsRoot(ctx).absolutePath, packageName = ctx.packageName,
        onSave = { e, v -> deps.settings.save(e, v) }, onDefaults = { deps.settings.resetToDefaults() }, onBack = onBack,
    )
}

/** Stateless settings layout (also used for screenshots). */
@Composable
fun SettingsContent(
    initialEst: EstimatorConfig, initialVis: VisionConfig, calibrationStatus: String, calibrationPath: String,
    sessionsPath: String, packageName: String, onSave: (EstimatorConfig, VisionConfig) -> Unit, onDefaults: () -> Unit,
    onBack: () -> Unit,
) {
    var est by remember { mutableStateOf(initialEst) }
    var vis by remember { mutableStateOf(initialVis) }
    var saved by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar("SETTINGS", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Section("FUSION")
            Param("kTilt (1/s) — tilt correction gain", est.kTilt, 0.05f, 2f) { est = est.copy(kTilt = it) }
            Param("visionAttitudeGain — camera pull on attitude", est.visionAttitudeGain, 0.05f, 1f) { est = est.copy(visionAttitudeGain = it) }
            Param("sigmaRotRad ×1e-4 — vision noise", est.sigmaRotRad * 1e4, 0.5f, 20f) { est = est.copy(sigmaRotRad = it * 1e-4) }
            Param("accelGateMs2 — accel disturbance gate", est.accelGateMs2, 0.2f, 3f) { est = est.copy(accelGateMs2 = it) }
            Param("estLogDecimation (est.csv every N)", est.estLogDecimation.toDouble(), 1f, 8f, integer = true) {
                est = est.copy(estLogDecimation = it.roundToInt())
            }
            Section("VISION")
            Param("gateBasePx — gyro gate", vis.gateBasePx, 1f, 12f) { vis = vis.copy(gateBasePx = it) }
            Param("minInlierRatio — majority rule", vis.minInlierRatio, 0.2f, 0.8f) { vis = vis.copy(minInlierRatio = it) }
            Param("maxPerCell — features per grid cell", vis.maxPerCell.toDouble(), 2f, 8f, integer = true) {
                vis = vis.copy(maxPerCell = it.roundToInt())
            }
            Param("windowMaxSec — anchor window", vis.windowMaxSec, 0.4f, 2f) { vis = vis.copy(windowMaxSec = it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Feature overlay", color = Hud.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(vis.debugOverlay, { vis = vis.copy(debugOverlay = it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Hud.Green, checkedTrackColor = Hud.Border))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lock focus at infinity (accuracy > sharpness)", color = Hud.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(vis.lockFocusInfinity, { vis = vis.copy(lockFocusInfinity = it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Hud.Green, checkedTrackColor = Hud.Border))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HudButton("SAVE", {
                    runCatching { onSave(est, vis) }
                        .onSuccess { saved = "saved — applies to the next session" }
                        .onFailure { saved = "invalid: ${it.message}" }
                }, Modifier.weight(1f))
                HudButton("DEFAULTS", {
                    onDefaults(); est = EstimatorConfig(); vis = VisionConfig(); saved = "defaults restored"
                }, Modifier.weight(1f), color = Hud.Amber)
            }
            if (saved.isNotEmpty()) DebugLine(saved)
            Section("CALIBRATION")
            DebugLine(calibrationStatus)
            DebugLine("file: $calibrationPath")
            Section("RECORDINGS")
            DebugLine(sessionsPath)
            DebugLine("adb pull /sdcard/Android/data/$packageName/files/sessions/ ./sessions/")
            Section("NO-COMPASS GUARANTEE")
            DebugLine("Registered sensors: gyroscope (uncalibrated) + accelerometer only.")
            DebugLine("Each session's meta.json lists registeredSensorTypes.")
        }
    }
}

@Composable
private fun Section(t: String) = Text(t, color = Hud.Green, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))

@Composable
private fun Param(label: String, value: Double, min: Float, max: Float, integer: Boolean = false, onChange: (Double) -> Unit) {
    Column {
        Text(
            if (integer) "$label: ${value.roundToInt()}" else String.format(Locale.ROOT, "%s: %.3f", label, value),
            color = Hud.Text, style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = value.toFloat().coerceIn(min, max), onValueChange = { onChange(it.toDouble()) }, valueRange = min..max,
            steps = if (integer) (max - min).roundToInt() - 1 else 0,
            colors = SliderDefaults.colors(thumbColor = Hud.Green, activeTrackColor = Hud.Green, inactiveTrackColor = Hud.Border),
        )
    }
}
