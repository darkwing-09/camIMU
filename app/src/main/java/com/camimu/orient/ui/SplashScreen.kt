package com.camimu.orient.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camimu.orient.R
import com.camimu.orient.ui.theme.Hud
import com.camimu.orient.ui.theme.HudMono
import kotlinx.coroutines.delay

/** Duration the launch popup stays on screen. */
const val SPLASH_DURATION_MS = 2500L

/** Launch popup: "POWERED BY" + Avinya logo, fades in, then calls [onDone] after [SPLASH_DURATION_MS]. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(600))
        delay(SPLASH_DURATION_MS - 600)
        onDone()
    }
    SplashContent(Modifier.alpha(alpha.value))
}

/** Stateless splash layout (also used for screenshots): "POWERED BY" + Avinya logo on a light card. */
@Composable
fun SplashContent(modifier: Modifier = Modifier) {
    Box(Modifier.fillMaxSize().background(Hud.Background), contentAlignment = Alignment.Center) {
        Column(
            modifier
                .border(2.dp, Hud.Green, RoundedCornerShape(16.dp))
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("POWERED BY", color = Color(0xFF1B2A4E), fontFamily = HudMono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(14.dp))
            Image(
                painter = painterResource(R.drawable.avinya_logo),
                contentDescription = "Avinya Tech 2026",
                modifier = Modifier.width(220.dp),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}
