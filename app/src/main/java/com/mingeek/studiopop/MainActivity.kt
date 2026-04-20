package com.mingeek.studiopop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mingeek.studiopop.ui.AppNavHost
import com.mingeek.studiopop.ui.theme.StudioPopTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioPopTheme {
                AppNavHost()
            }
        }
    }
}
