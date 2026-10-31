package com.camimu.orient.ui.hud

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.camimu.orient.ui.theme.Hud
import kotlin.math.cos
import kotlin.math.sin

/**
 * Attitude indicator. Roll + (right side raised) rotates the horizon clockwise on screen, as the
 * world appears from the camera; pitch + (looking up) moves the horizon down (more sky).
 */
@Composable
fun ArtificialHorizon(pitchDeg: Double, rollDeg: Double, modifier: Modifier = Modifier, diameter: Dp = 140.dp) {
    Canvas(modifier.size(diameter)) {
        val r = size.minDimension / 2
        val c = center
        val pxPerDeg = r / 45f
        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
        clipPath(circle) {
            rotate(rollDeg.toFloat(), c) {
                val horizonY = c.y + pitchDeg.toFloat() * pxPerDeg
                drawRect(Hud.Sky, topLeft = Offset(c.x - 3 * r, horizonY - 6 * r), size = Size(6 * r, 6 * r))
                drawRect(Hud.Ground, topLeft = Offset(c.x - 3 * r, horizonY), size = Size(6 * r, 6 * r))
                drawLine(Hud.Green, Offset(c.x - 3 * r, horizonY), Offset(c.x + 3 * r, horizonY), strokeWidth = 3f)
                for (k in -6..6) {
                    if (k == 0) continue
                    val y = horizonY - k * 10 * pxPerDeg
                    val half = if (k % 2 == 0) r * 0.35f else r * 0.2f
                    drawLine(Hud.Green.copy(alpha = 0.7f), Offset(c.x - half, y), Offset(c.x + half, y), strokeWidth = 2f)
                }
            }
        }
        drawCircle(Hud.Border, r, c, style = Stroke(4f))
        // Fixed aircraft symbol.
        val w = r * 0.45f
        drawLine(Hud.Amber, Offset(c.x - w, c.y), Offset(c.x - w * 0.3f, c.y), strokeWidth = 6f, cap = StrokeCap.Round)
        drawLine(Hud.Amber, Offset(c.x + w * 0.3f, c.y), Offset(c.x + w, c.y), strokeWidth = 6f, cap = StrokeCap.Round)
        drawCircle(Hud.Amber, 5f, c)
        // Roll pointer at the top, rotating with roll.
        rotate(rollDeg.toFloat(), c) {
            val tri = Path().apply {
                moveTo(c.x, c.y - r + 4); lineTo(c.x - 8, c.y - r + 18); lineTo(c.x + 8, c.y - r + 18); close()
            }
            drawPath(tri, Hud.Lime)
        }
    }
}

/**
 * Relative heading dial (no compass). Labels increase counter-clockwise (turning left = +yaw);
 * the value under the top marker is the current yaw.
 */
@Composable
fun HeadingDial(yawDeg: Double, frozen: Boolean, modifier: Modifier = Modifier, diameter: Dp = 140.dp) {
    val paint = remember {
        Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
    }
    Canvas(modifier.size(diameter)) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(Hud.Surface, r, c)
        drawCircle(Hud.Border, r, c, style = Stroke(4f))
        paint.textSize = r * 0.17f
        paint.color = (if (frozen) Hud.Muted else Hud.Green).toArgb()
        for (a in 0 until 360 step 10) {
            // Clockwise screen angle from top for label a: φ = yaw − a.
            val phi = Math.toRadians(yawDeg - a)
            val sx = sin(phi).toFloat(); val sy = -cos(phi).toFloat()
            val major = a % 30 == 0
            val inner = if (major) r * 0.78f else r * 0.86f
            drawLine(
                if (major) Hud.Green else Hud.Muted, Offset(c.x + sx * inner, c.y + sy * inner),
                Offset(c.x + sx * (r - 4), c.y + sy * (r - 4)), strokeWidth = if (major) 3f else 1.5f,
            )
            if (major) {
                val label = if (a > 180) "${a - 360}" else "$a"
                val tr = r * 0.6f
                drawContext.canvas.nativeCanvas.drawText(label, c.x + sx * tr, c.y + sy * tr + paint.textSize / 3, paint)
            }
        }
        val tri = Path().apply { moveTo(c.x, c.y - r + 2); lineTo(c.x - 9, c.y - r - 12); lineTo(c.x + 9, c.y - r - 12); close() }
        drawPath(tri, Hud.Amber)
        drawLine(Hud.Amber, Offset(c.x, c.y - r * 0.5f), Offset(c.x, c.y - r * 0.2f), strokeWidth = 4f)
        drawCircle(Hud.Amber, 4f, c)
    }
}
