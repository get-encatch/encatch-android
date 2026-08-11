package com.encatch.core

import kotlin.test.Test

class LoggerTest {

    @Test
    fun defaultLogger_neverThrows_regardlessOfDebugModeOrPlatformLogAvailability() {
        val debugOnLogger = DefaultEncatchLogger { true }
        debugOnLogger.debug("hello")
        debugOnLogger.warn("uh oh")

        val debugOffLogger = DefaultEncatchLogger { false }
        debugOffLogger.debug("should be gated but must not throw")
        debugOffLogger.warn("warn always logs but must not throw")
    }
}
