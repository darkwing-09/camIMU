package com.camimu.orient.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.camimu.orient.ui.hud.HudButton
import com.camimu.orient.ui.theme.Hud

/** Requests the camera permission and shows [content] once granted. */
@Composable
fun PermissionGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) {
        content()
    } else {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CAMERA PERMISSION NEEDED", color = Hud.Amber, style = MaterialTheme.typography.titleMedium)
            Text("The camera is the yaw reference (no compass is used).", color = Hud.Text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(8.dp))
            Row {
                HudButton("GRANT", { launcher.launch(Manifest.permission.CAMERA) })
                Spacer(Modifier.width(12.dp))
                HudButton("BACK", onBack, color = Hud.Muted)
            }
        }
    }
}

/** Top status row: back, title, right-side content. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, right: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Hud.Scrim).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudButton("◀", onBack, color = Hud.Muted)
        Spacer(Modifier.width(8.dp))
        Text(title, color = Hud.Green, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        right()
    }
}

@Composable
fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Hud.Amber, style = MaterialTheme.typography.bodyMedium)
    }
}
