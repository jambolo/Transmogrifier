package io.github.jambolo.transmogrifier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.jambolo.transmogrifier.ui.AppNavHost
import io.github.jambolo.transmogrifier.ui.theme.TransmogrifierTheme

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
