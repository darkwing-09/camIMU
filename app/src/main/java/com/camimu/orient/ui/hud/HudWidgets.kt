package com.camimu.orient.ui.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camimu.orient.core.model.AttitudeAngles
import com.camimu.orient.core.model.Confidence
import com.camimu.orient.core.model.OrientationWarning
import com.camimu.orient.ui.theme.Hud
import com.camimu.orient.ui.theme.HudMono
import java.util.Locale

fun Confidence.color(): Color = when (this) {
    Confidence.HIGH -> Hud.Green
    Confidence.MEDIUM -> Hud.Amber
    Confidence.LOW -> Hud.Red
}

private fun fmt(v: Double) = String.format(Locale.ROOT, "%+7.1f°", v)

/** Large YAW / PITCH / ROLL readout. Yaw and roll show "—" near the vertical singularity. */
@Composable
fun NumericReadout(angles: AttitudeAngles?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        val frozen = angles?.isVertical == true
        AngleCell("YAW", angles?.let { if (frozen) null else it.yawDeg }, "rel. to start")
        AngleCell("PITCH", angles?.pitchDeg, "+ = up")
        AngleCell("ROLL", angles?.let { if (frozen) null else it.rollDeg }, "+ = right up")
    }
}

@Composable
private fun AngleCell(label: String, value: Double?, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
        Text(
            value?.let { fmt(it) } ?: "   —   ",
            fontFamily = HudMono, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Hud.Green,
        )
        Text(caption, style = MaterialTheme.typography.labelSmall, color = Hud.Muted, fontSize = 9.sp)
    }
}

/** Confidence pill: HIGH/MEDIUM/LOW + optional ±σ_yaw. */
@Composable
fun ConfidenceBadge(confidence: Confidence, sigmaYawDeg: Double?, modifier: Modifier = Modifier) {
    val c = confidence.color()
    Row(
        modifier
            .border(BorderStroke(1.5.dp, c), RoundedCornerShape(50))
            .background(c.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("● ", color = c, fontSize = 12.sp)
        Text(confidence.name, color = c, style = MaterialTheme.typography.labelLarge)
        if (sigmaYawDeg != null) {
            Spacer(Modifier.width(8.dp))
            Text(String.format(Locale.ROOT, "σψ ±%.1f°", sigmaYawDeg), color = Hud.Text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun OrientationWarning.text(): String = when (this) {
    OrientationWarning.INITIALIZING -> "HOLD STILL — initializing gyro bias"
    OrientationWarning.INIT_NOT_STATIC -> "Started while moving — bias not calibrated"
    OrientationWarning.CAMERA_BLOCKED -> "Camera blocked: using gyro only"
    OrientationWarning.LOW_TEXTURE -> "Low texture: point at a detailed scene"
    OrientationWarning.MOVING_TOO_FAST -> "Moving too fast for the camera"
    OrientationWarning.VERTICAL -> "Looking straight up/down: yaw & roll frozen"
    OrientationWarning.ACCEL_DISTURBED -> "Accelerating: tilt correction paused"
    OrientationWarning.SENSOR_GAP -> "Sensor data gap"
    OrientationWarning.TRACKING_LOST -> "Tracking lost"
}

/** Stack of active warnings (most important first). */
@Composable
fun WarningsBanner(warnings: Set<OrientationWarning>, modifier: Modifier = Modifier) {
    if (warnings.isEmpty()) return
    val severe = setOf(OrientationWarning.CAMERA_BLOCKED, OrientationWarning.INITIALIZING, OrientationWarning.TRACKING_LOST)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        warnings.sortedBy { if (it in severe) 0 else 1 }.take(3).forEach { w ->
            val c = if (w in severe) Hud.Red else Hud.Amber
            Text(
                "▲ ${w.text()}", color = c, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .background(Hud.Scrim, RoundedCornerShape(4.dp))
                    .border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
fun HudButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Hud.Green, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier,
        border = BorderStroke(1.5.dp, if (enabled) color else Hud.Border),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Hud.Scrim, contentColor = color),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

/** Small "key: value" debug line. */
@Composable
fun DebugLine(text: String) = Text(text, color = Hud.Muted, style = MaterialTheme.typography.labelSmall)
