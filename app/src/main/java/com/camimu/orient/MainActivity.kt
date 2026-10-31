package com.camimu.orient

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.camimu.orient.ui.CamImuApp
import com.camimu.orient.ui.theme.CamImuTheme
import com.camimu.orient.vision.OpenCvInit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only effective while our window is visible; no wakelock is held.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        OpenCvInit.ensure()
        setContent { CamImuTheme { CamImuApp() } }
    }
}
