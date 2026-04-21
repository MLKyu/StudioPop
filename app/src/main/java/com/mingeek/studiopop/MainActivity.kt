package com.mingeek.studiopop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mingeek.studiopop.ui.AppNavHost
import com.mingeek.studiopop.ui.theme.StudioPopTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // ⚠️ super.onCreate() 보다 먼저 호출해야 SplashScreen 이 정상 동작.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Immersive sticky: status / nav bar 기본 숨김.
        // 엣지 스와이프 시 일시적으로 노출됐다가 자동으로 다시 숨음.
        // imePadding() 은 그대로 동작 — IME 인셋은 별도 처리.
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            StudioPopTheme {
                AppNavHost()
            }
        }
    }
}
