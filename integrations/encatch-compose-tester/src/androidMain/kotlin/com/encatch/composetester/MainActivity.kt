package com.encatch.composetester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = applicationContext
        // No EncatchFormHost.install: the modal host comes from com.encatch:android's App Startup
        // initializer, as for any Compose app (CP-001).
        setContent {
            TesterApp()
        }
    }
}
