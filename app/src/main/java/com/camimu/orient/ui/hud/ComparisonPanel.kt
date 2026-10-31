package com.camimu.orient.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.model.AttitudeAngles
import com.camimu.orient.ui.theme.Hud
import java.util.Locale

/** Live custom-vs-ARCore table plus a 30-s Δyaw trace. */
@Composable
fun ComparisonPanel(custom: AttitudeAngles?, arcore: AttitudeAngles?, dyawHistory: List<Double>, modifier: Modifier = Modifier) {
    Column(modifier.background(Hud.Scrim, RoundedCornerShape(6.dp)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Cell("", Hud.Muted); Cell("CUSTOM", Hud.Green); Cell("ARCORE", Hud.Amber); Cell("Δ", Hud.Text)
        }
        for ((name, c, a) in listOf(
            Triple("YAW", custom?.yawRad, arcore?.yawRad),
            Triple("PITCH", custom?.pitchRad, arcore?.pitchRad),
            Triple("ROLL", custom?.rollRad, arcore?.rollRad),
        )) {
            Row(Modifier.fillMaxWidth()) {
                Cell(name, Hud.Muted)
                Cell(c?.let { deg(it) } ?: "—", Hud.Green)
                Cell(a?.let { deg(it) } ?: "—", Hud.Amber)
                Cell(if (c != null && a != null) deg(AngleMath.angleDiff(c, a)) else "—", Hud.Text)
            }
        }
        Text("Δyaw last 30 s (±5° full scale)", color = Hud.Muted, style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val mid = size.height / 2
            drawLine(Hud.Border, Offset(0f, mid), Offset(size.width, mid), 1f)
            if (dyawHistory.size > 1) {
                val step = size.width / (HISTORY - 1)
                for (i in 1 until dyawHistory.size) {
                    fun y(v: Double) = (mid - (v.coerceIn(-5.0, 5.0) / 5.0) * mid).toFloat()
                    drawLine(Hud.Lime, Offset((i - 1) * step, y(dyawHistory[i - 1])), Offset(i * step, y(dyawHistory[i])), 2f)
                }
            }
        }
    }
}

const val HISTORY = 300

private fun deg(rad: Double) = String.format(Locale.ROOT, "%+.1f°", AngleMath.deg(rad))

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, color: Color) =
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
