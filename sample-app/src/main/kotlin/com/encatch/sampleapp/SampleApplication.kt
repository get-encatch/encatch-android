package com.encatch.sampleapp

import android.app.Application
import com.encatch.android.EncatchFormHost

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EncatchFormHost.install(this)
    }
}
