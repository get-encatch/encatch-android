package com.encatch.composetester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.encatch.android.EncatchFormHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = applicationContext
        EncatchFormHost.install(application)
        setContent {
            TesterApp()
        }
    }
}
