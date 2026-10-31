package com.camimu.orient.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.camimu.orient.ar.ArCoreAvailability
import com.camimu.orient.core.config.EstimatorConfig
import com.camimu.orient.core.config.VisionConfig
import com.camimu.orient.core.math.AngleMath
import com.camimu.orient.core.model.AttitudeAngles
import com.camimu.orient.core.model.Confidence
import com.camimu.orient.core.model.OrientationWarning
import com.camimu.orient.core.model.Px
import com.camimu.orient.ui.HomeContent
import com.camimu.orient.ui.SettingsContent
import com.camimu.orient.ui.SplashContent
import com.camimu.orient.ui.TopBar
import com.camimu.orient.ui.hud.ComparisonPanel
import com.camimu.orient.ui.hud.ConfidenceBadge
import com.camimu.orient.ui.hud.DebugLine
import com.camimu.orient.ui.hud.FeatureOverlay
import com.camimu.orient.ui.hud.HudButton
import com.camimu.orient.ui.hud.HudPanel
import com.camimu.orient.ui.hud.WarningsBanner
import com.camimu.orient.ui.theme.CamImuTheme
import com.camimu.orient.ui.theme.Hud
import com.camimu.orient.vision.FrameDebug
import org.junit.Rule
import org.junit.Test
import java.util.Random
import kotlin.math.sin

/**
 * Renders every app screen to PNG with Paparazzi (no phone needed):
 *   ./gradlew :app:recordPaparazziDebug   → PNG files in app/src/test/snapshots/images
 * Camera views are replaced by a synthetic scene; everything drawn on top is the real HUD code.
 */
class AppScreenshots {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, showSystemUi = false, useDeviceResolution = true)

    private val angles = AttitudeAngles(AngleMath.rad(23.4), AngleMath.rad(-4.2), AngleMath.rad(6.8), false)

    private fun shot(content: @Composable () -> Unit) = paparazzi.snapshot { CamImuTheme { content() } }

    @Test fun s0_splash() = shot { SplashContent() }

    @Test fun s1_home() = shot { Box(Modifier.background(Hud.Background)) { HomeContent(ArCoreAvailability.Status.SUPPORTED) {} } }

    @Test fun s2_custom_tracking() = shot {
        CameraHud(
            "CUSTOM", Confidence.HIGH, 0.6, emptySet(), angles, featureDebug(person = true),
            listOf(
                "gyro 200 Hz · clock ELAPSED_REALTIME",
                "bias °/s +0.412 -0.687 +0.344 · drift σ 0.42°/min",
                "vision 30 fps · 11.8 ms · tracks 146 · inl 118 · OK",
                "windows 57/58 accepted · K CALIBRATED fx=512.4",
            ),
        )
    }

    @Test fun s3_custom_initializing() = shot {
        CameraHud("CUSTOM", Confidence.LOW, null, setOf(OrientationWarning.INITIALIZING), null, null,
            listOf("gyro 200 Hz · clock ELAPSED_REALTIME"))
    }

    @Test fun s4_custom_camera_blocked() = shot {
        CameraHud(
            "CUSTOM", Confidence.LOW, 3.8, setOf(OrientationWarning.CAMERA_BLOCKED), angles.copy(yawRad = AngleMath.rad(41.0)),
            null, listOf("vision 30 fps · 4.1 ms · tracks 0 · inl 0 · BLOCKED", "gyro-only fallback · σψ growing"),
            blocked = true, recording = true,
        )
    }

    @Test fun s5_imu_only() = shot {
        Box(Modifier.fillMaxSize().background(Hud.Background)) {
            Overlay("IMU ONLY", Confidence.MEDIUM, 1.9, setOf(OrientationWarning.ACCEL_DISTURBED), angles,
                listOf("gyro 200 Hz · clock ELAPSED_REALTIME", "bias °/s +0.409 -0.690 +0.351 · drift σ 1.30°/min"), false)
        }
    }

    @Test fun s6_arcore() = shot {
        Box(Modifier.fillMaxSize()) {
            Scene()
            Overlay("ARCORE", Confidence.HIGH, null, emptySet(), angles.copy(yawRad = AngleMath.rad(23.9)), emptyList(), false)
        }
    }

    @Test fun s7_benchmark() = shot {
        Box(Modifier.fillMaxSize()) {
            Scene()
            FeatureOverlay(featureDebug(person = false), 90)
            Column(Modifier.fillMaxSize()) {
                TopBar("BENCHMARK", {}) { ConfidenceBadge(Confidence.HIGH, 0.7) }
                Column(Modifier.padding(8.dp)) {
                    DebugLine("vision 30 fps · 13.2 ms · tracks 139 · OK · K ARCORE fx=498.1")
                    ComparisonPanel(
                        angles, angles.copy(yawRad = AngleMath.rad(24.3), pitchRad = AngleMath.rad(-3.9), rollRad = AngleMath.rad(7.1)),
                        List(300) { 0.6 * sin(it / 25.0) + 0.25 * sin(it / 7.0) - 0.4 }, Modifier.fillMaxWidth(),
                    )
                }
            }
            HudPanel(angles.copy(yawRad = AngleMath.rad(24.3)), false, {}, {}, {}, Modifier.align(Alignment.BottomCenter))
        }
    }

    @Test fun s8_calibrate() = shot {
        Box(Modifier.fillMaxSize()) {
            Scene(checkerboard = true)
            Column(Modifier.fillMaxSize()) {
                TopBar("CALIBRATE", {})
                Column(Modifier.padding(8.dp).background(Hud.Scrim, RoundedCornerShape(6.dp)).padding(8.dp)) {
                    Text("Checkerboard 9×6 inner corners. Capture 20–30 views: all image corners, tilts ±30–45°, different distances.",
                        color = Hud.Text, style = MaterialTheme.typography.bodySmall)
                    DebugLine("sensor orientation 90°, OIS true, ts REALTIME")
                    DebugLine("saved 14 → …/files/sessions/20261031_142210_CALIBRATE")
                    DebugLine("no calibration.json (using defaults + FOV estimate)")
                }
            }
            HudButton("CAPTURE (14)", {}, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp))
        }
    }

    @Test fun s9_settings() = shot {
        Box(Modifier.background(Hud.Background)) {
            SettingsContent(
                EstimatorConfig(), VisionConfig(), "loaded calibration.json: intrinsics=CALIBRATED, t_d=11.5 ms",
                "/storage/emulated/0/Android/data/com.camimu.orient/files/calibration.json",
                "/storage/emulated/0/Android/data/com.camimu.orient/files/sessions", "com.camimu.orient",
                { _, _ -> }, {}, {},
            )
        }
    }

    // ---- helpers ----

    @Composable
    private fun CameraHud(
        title: String, conf: Confidence, sigma: Double?, warnings: Set<OrientationWarning>, a: AttitudeAngles?,
        debug: FrameDebug?, lines: List<String>, blocked: Boolean = false, recording: Boolean = false,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (blocked) Box(Modifier.fillMaxSize().background(Color(0xFF050505))) else Scene()
            FeatureOverlay(debug, 90)
            Overlay(title, conf, sigma, warnings, a, lines, recording)
        }
    }

    @Composable
    private fun Overlay(
        title: String, conf: Confidence, sigma: Double?, warnings: Set<OrientationWarning>, a: AttitudeAngles?,
        lines: List<String>, recording: Boolean,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopBar(title, {}) { ConfidenceBadge(conf, sigma) }
                Column(Modifier.padding(8.dp)) {
                    WarningsBanner(warnings)
                    if (lines.isNotEmpty() || recording) {
                        Column(Modifier.background(Hud.Scrim, RoundedCornerShape(4.dp)).padding(6.dp)) {
                            lines.forEach { DebugLine(it) }
                            if (recording) DebugLine("● REC → 20261031_143005_CUSTOM")
                        }
                    }
                }
            }
            HudPanel(a, recording, {}, {}, {}, Modifier.align(Alignment.BottomCenter))
        }
    }

    /** Synthetic "room" standing in for the live camera preview. */
    @Composable
    private fun Scene(checkerboard: Boolean = false) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF2B3036), Color(0xFF3B352C), Color(0xFF231F1A))))
            val w = size.width; val h = size.height
            drawRect(Color(0xFF454B52), Offset(w * 0.05f, h * 0.12f), Size(w * 0.38f, h * 0.30f))
            drawRect(Color(0xFF5A6066), Offset(w * 0.09f, h * 0.15f), Size(w * 0.30f, h * 0.22f))
            drawRect(Color(0xFF51463A), Offset(w * 0.55f, h * 0.20f), Size(w * 0.36f, h * 0.45f))
            for (i in 0 until 6) drawRect(Color(0xFF6B5D4B), Offset(w * 0.57f, h * (0.23f + i * 0.07f)), Size(w * 0.32f, h * 0.012f))
            drawRect(Color(0xFF2E2A25), Offset(0f, h * 0.68f), Size(w, h * 0.32f))
            if (checkerboard) {
                val sq = w * 0.075f; val x0 = w * 0.16f; val y0 = h * 0.30f
                drawRect(Color.White, Offset(x0 - sq * 0.5f, y0 - sq * 0.5f), Size(sq * 11, sq * 8))
                for (r in 0 until 7) for (c in 0 until 10) if ((r + c) % 2 == 0) drawRect(Color.Black, Offset(x0 + c * sq, y0 + r * sq), Size(sq, sq))
            }
        }
    }

    private fun featureDebug(person: Boolean): FrameDebug {
        val rnd = Random(3)
        val inl = ArrayList<Px>(); val out = ArrayList<Px>(); val gated = ArrayList<Px>()
        for (i in 0 until 130) {
            val p = Px(20 + rnd.nextDouble() * 600, 20 + rnd.nextDouble() * 440)
            val inPerson = person && p.u in 120.0..300.0 && p.v in 250.0..420.0
            when {
                inPerson && i % 3 == 0 -> out += p
                inPerson -> gated += p
                i % 17 == 0 -> out += p
                else -> inl += p
            }
        }
        return FrameDebug(640, 480, inl, out, gated)
    }

}
