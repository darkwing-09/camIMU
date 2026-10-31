package com.camimu.orient.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.camimu.orient.core.vision.OverlayMapping
import com.camimu.orient.ui.theme.Hud
import com.camimu.orient.vision.FrameDebug

/** Draws tracked features over the preview: green = inliers, red = RANSAC outliers, amber = gyro-gated. */
@Composable
fun FeatureOverlay(debug: FrameDebug?, sensorOrientationDeg: Int, modifier: Modifier = Modifier) {
    if (debug == null) return
    Canvas(modifier.fillMaxSize()) {
        val m = OverlayMapping(sensorOrientationDeg, debug.width, debug.height, size.width.toDouble(), size.height.toDouble())
        fun dot(p: com.camimu.orient.core.model.Px, color: androidx.compose.ui.graphics.Color, r: Float) {
            val v = m.toView(p)
            drawCircle(color, r, Offset(v.u.toFloat(), v.v.toFloat()))
        }
        debug.gated.forEach { dot(it, Hud.Amber, 5f) }
        debug.outliers.forEach { dot(it, Hud.Red, 4f) }
        debug.inliers.forEach { dot(it, Hud.Green, 3.5f) }
    }
}
