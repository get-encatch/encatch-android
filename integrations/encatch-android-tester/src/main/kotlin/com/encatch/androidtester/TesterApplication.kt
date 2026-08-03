package com.encatch.androidtester

import android.app.Application
import com.encatch.android.EncatchFormHost

class TesterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EncatchFormHost.install(this)
    }
}
