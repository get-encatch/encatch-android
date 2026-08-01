package com.encatch.core

import android.content.Context
import androidx.startup.Initializer

/**
 * Holds the application [Context], captured automatically at process start via
 * AndroidX App Startup — no explicit context param needed on [Encatch.init], keeping
 * the public API signature identical to the React Native SDK's `init(apiKey, config)`.
 */
internal object EncatchAndroidContext {
    lateinit var applicationContext: Context
        private set

    internal fun attach(context: Context) {
        if (!::applicationContext.isInitialized) {
            applicationContext = context.applicationContext
        }
    }

    val isAttached: Boolean get() = ::applicationContext.isInitialized
}

/** Registered via androidx.startup in AndroidManifest.xml; runs automatically before [android.app.Application.onCreate]. */
class EncatchContextInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        EncatchAndroidContext.attach(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
