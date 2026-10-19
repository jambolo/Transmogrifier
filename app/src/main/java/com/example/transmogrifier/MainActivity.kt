package com.example.transmogrifier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.transmogrifier.ui.AppNavHost
import com.example.transmogrifier.ui.theme.TransmogrifierTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TransmogrifierApplication).container
        setContent {
            TransmogrifierTheme {
                AppNavHost(container)
            }
        }
    }
}
