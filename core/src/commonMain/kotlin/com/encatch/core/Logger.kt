package com.encatch.core

/** Internal logger for the Encatch SDK, mirroring `logger.ts`'s `EncatchLogger`. */
interface EncatchLogger {
    fun debug(message: String)
    fun warn(message: String)
}

internal enum class LogLevel { DEBUG, WARN }

/** Platform log sink — Android: `android.util.Log`. */
internal expect fun platformLog(tag: String, level: LogLevel, message: String)

/** `debug` is gated by [debugMode]; `warn` always logs, matching the RN SDK's fallback logger. */
internal class DefaultEncatchLogger(private val debugMode: () -> Boolean) : EncatchLogger {
    override fun debug(message: String) {
        if (debugMode()) platformLog("Encatch", LogLevel.DEBUG, message)
    }

    override fun warn(message: String) {
        platformLog("Encatch", LogLevel.WARN, message)
    }
}
