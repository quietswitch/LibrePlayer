package com.libreplayer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.libreplayer.navigation.LibrePlayerApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as LibrePlayerApplication).appContainer
        setContent {
            LibrePlayerApp(appContainer = appContainer)
        }
    }
}
