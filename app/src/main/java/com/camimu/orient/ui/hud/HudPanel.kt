package com.camimu.orient.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.camimu.orient.core.model.AttitudeAngles
import com.camimu.orient.ui.theme.Hud

/** Bottom instrument panel shared by all modes. */
@Composable
fun HudPanel(
    angles: AttitudeAngles?,
    recording: Boolean,
    onResetYaw: () -> Unit,
    onMark: (() -> Unit)?,
    onToggleRecord: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Hud.Scrim, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .border(1.dp, Hud.Border, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NumericReadout(angles)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            ArtificialHorizon(angles?.pitchDeg ?: 0.0, if (angles?.isVertical == true) 0.0 else angles?.rollDeg ?: 0.0)
            HeadingDial(angles?.yawDeg ?: 0.0, frozen = angles?.isVertical == true)
        }
        Text(
            "Yaw is relative to the start / last reset (no compass). Turning left = +yaw.",
            color = Hud.Muted, style = MaterialTheme.typography.labelSmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("RESET YAW", onResetYaw, Modifier.weight(1f))
            if (onMark != null) HudButton("MARK", onMark, Modifier.weight(0.7f), color = Hud.Lime, enabled = recording)
            if (onToggleRecord != null) {
                HudButton(if (recording) "■ STOP" else "● REC", onToggleRecord, Modifier.weight(0.8f), color = if (recording) Hud.Red else Hud.Amber)
            }
        }
    }
}
