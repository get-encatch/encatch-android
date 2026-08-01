package com.encatch.core

import android.util.Log

internal actual fun platformLog(tag: String, level: LogLevel, message: String) {
    // Swallow failures so logging never crashes the host app — also keeps this safe to call
    // from plain JVM unit tests where android.util.Log isn't backed by a real implementation.
    runCatching {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
        }
    }
}
