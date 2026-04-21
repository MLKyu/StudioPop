package com.mingeek.studiopop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mingeek.studiopop.ui.AppNavHost
import com.mingeek.studiopop.ui.theme.StudioPopTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // ⚠️ super.onCreate() 보다 먼저 호출해야 SplashScreen 이 정상 동작.
        // postSplashScreenTheme(themes.xml) 가 자동 적용됨.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioPopTheme {
                AppNavHost()
            }
        }
    }
}
