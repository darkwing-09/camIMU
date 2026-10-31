package com.camimu.orient.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.camimu.orient.calibration.CalibrationStore
import com.camimu.orient.pipeline.AppMode
import com.camimu.orient.settings.SettingsRepository
import com.camimu.orient.ui.theme.Hud

enum class AppScreen { HOME, ARCORE, CUSTOM, CUSTOM_IMU_ONLY, BENCHMARK, CALIBRATE, SETTINGS }

/** Shared app-level dependencies. */
class AppDeps(val settings: SettingsRepository, val calibration: CalibrationStore)

@Composable
fun CamImuApp() {
    val ctx = LocalContext.current.applicationContext
    val deps = remember { AppDeps(SettingsRepository(ctx), CalibrationStore(ctx)) }
    var showSplash by rememberSaveable { mutableStateOf(true) }
    if (showSplash) {
        SplashScreen(onDone = { showSplash = false })
        return
    }
    var screen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    val back = { screen = AppScreen.HOME }
    BackHandler(enabled = screen != AppScreen.HOME) { back() }
    Surface(Modifier.fillMaxSize(), color = Hud.Background) {
        when (screen) {
            AppScreen.HOME -> HomeScreen(onOpen = { screen = it })
            AppScreen.CUSTOM -> PermissionGate(back) { CustomScreen(AppMode.CUSTOM, deps, back) }
            AppScreen.CUSTOM_IMU_ONLY -> CustomScreen(AppMode.CUSTOM_IMU_ONLY, deps, back)
            AppScreen.ARCORE -> PermissionGate(back) { ArCoreScreen(deps, benchmark = false, onBack = back) }
            AppScreen.BENCHMARK -> PermissionGate(back) { ArCoreScreen(deps, benchmark = true, onBack = back) }
            AppScreen.CALIBRATE -> PermissionGate(back) { CalibrationScreen(deps, back) }
            AppScreen.SETTINGS -> SettingsScreen(deps, back)
        }
    }
}
