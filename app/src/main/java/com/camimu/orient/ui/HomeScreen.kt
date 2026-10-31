package com.camimu.orient.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.camimu.orient.ar.ArCoreAvailability
import com.camimu.orient.ui.theme.Hud
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onOpen: (AppScreen) -> Unit) {
    val ctx = LocalContext.current
    var ar by remember { mutableStateOf(ArCoreAvailability.Status.CHECKING) }
    LaunchedEffect(Unit) {
        repeat(20) {
            ar = ArCoreAvailability.check(ctx)
            if (ar != ArCoreAvailability.Status.CHECKING) return@LaunchedEffect
            delay(250)
        }
    }
    HomeContent(ar, onOpen)
}

/** Stateless home layout (also used for screenshots). */
@Composable
fun HomeContent(ar: ArCoreAvailability.Status, onOpen: (AppScreen) -> Unit) {
    val arOk = ar == ArCoreAvailability.Status.SUPPORTED || ar == ArCoreAvailability.Status.NOT_INSTALLED
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("CamIMU", color = Hud.Green, style = MaterialTheme.typography.headlineMedium)
        Text("Yaw · Pitch · Roll from gyro + accelerometer + camera. No compass.", color = Hud.Text, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Hold the phone still ~2 s when a mode starts. Yaw is relative to the start heading.",
            color = Hud.Muted, style = MaterialTheme.typography.bodySmall,
        )
        ModeCard("CUSTOM", "Our estimator: IMU + camera (obstacle-robust)", Hud.Green) { onOpen(AppScreen.CUSTOM) }
        ModeCard("CUSTOM · IMU ONLY", "Gyro + accel only — shows yaw drift without the camera", Hud.Lime) { onOpen(AppScreen.CUSTOM_IMU_ONLY) }
        ModeCard("ARCORE", if (arOk) "Google ARCore reference orientation" else "ARCore: ${ar.name.lowercase()}", Hud.Amber, arOk) {
            onOpen(AppScreen.ARCORE)
        }
        ModeCard("BENCHMARK", if (arOk) "Custom vs ARCore on the same camera frames" else "Needs ARCore", Hud.Amber, arOk) {
            onOpen(AppScreen.BENCHMARK)
        }
        ModeCard("CALIBRATE", "Checkerboard capture for camera intrinsics", Hud.Text) { onOpen(AppScreen.CALIBRATE) }
        ModeCard("SETTINGS", "Filter parameters, calibration status, recordings", Hud.Muted) { onOpen(AppScreen.SETTINGS) }
    }
}

@Composable
private fun ModeCard(title: String, subtitle: String, accent: androidx.compose.ui.graphics.Color, enabled: Boolean = true, onClick: () -> Unit) {
    val c = if (enabled) accent else Hud.Border
    Column(
        Modifier.fillMaxWidth()
            .border(1.5.dp, c, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
    ) {
        Text("▸ $title", color = c, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = if (enabled) Hud.Text else Hud.Muted, style = MaterialTheme.typography.bodySmall)
    }
}
