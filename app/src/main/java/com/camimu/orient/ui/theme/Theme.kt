package com.camimu.orient.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * HUD palette. Base colours from the open-design.ai "HUD" design system
 * (https://open-design.ai/systems/hud/): #0A0A0A, #111316, #1E2328, #00FF41, #7FFF00.
 * Amber/red are the standard caution/warning companions for confidence states.
 */
object Hud {
    val Background = Color(0xFF0A0A0A)
    val Surface = Color(0xFF111316)
    val Border = Color(0xFF1E2328)
    val Green = Color(0xFF00FF41)
    val Lime = Color(0xFF7FFF00)
    val Amber = Color(0xFFFFB000)
    val Red = Color(0xFFFF3B30)
    val Text = Color(0xFFD7FFE0)
    val Muted = Color(0xFF6B7F70)
    val Sky = Color(0xFF0B2A3A)
    val Ground = Color(0xFF3A2A12)
    val Scrim = Color(0xCC0A0A0A)
}

val HudMono = FontFamily.Monospace

private val HudTypography = Typography(
    headlineMedium = TextStyle(fontFamily = HudMono, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontFamily = HudMono, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.5.sp),
    bodyMedium = TextStyle(fontFamily = HudMono, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = HudMono, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = HudMono, fontSize = 11.sp, letterSpacing = 1.sp),
    labelLarge = TextStyle(fontFamily = HudMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp),
)

@Composable
fun CamImuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Hud.Green, onPrimary = Hud.Background, secondary = Hud.Lime, onSecondary = Hud.Background,
            tertiary = Hud.Amber, error = Hud.Red, background = Hud.Background, onBackground = Hud.Text,
            surface = Hud.Surface, onSurface = Hud.Text, surfaceVariant = Hud.Border, onSurfaceVariant = Hud.Muted,
            outline = Hud.Border,
        ),
        typography = HudTypography,
        content = content,
    )
}
