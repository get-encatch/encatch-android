@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.encatch.composesample

import com.encatch.bridge.EncatchBridge
import com.encatch.bridge.EncatchBridgeConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * iOS backing for [SampleSdk] — forwards to the pure-Swift `ios-native` SDK via
 * `EncatchBridge`/`EncatchBridgeConfig`, the Kotlin/Native cinterop bindings generated from
 * `ios-native/Sources/Encatch/ObjCBridge/EncatchBridge.swift`'s `@objc` facade (see
 * `compose-sample/src/nativeInterop/cinterop/EncatchBridge.def`). The Swift side is
 * completion-handler based (Swift `async`/`throws` isn't cinterop-representable); this wraps each
 * call back into a suspend function via `suspendCancellableCoroutine`.
 *
 * Deviation from idiomatic Kotlin/Native ObjC interop: our custom cinterop bridge does NOT
 * synthesize Kotlin `val`/`var` properties for the generated header's Objective-C `@property`
 * declarations (unlike, say, `platform.UIKit`'s prebuilt bindings) — calls go through the raw
 * getter/setter methods (`.shared()`, `.isInitialized()`, `.setApiBaseUrl(...)`, etc.) instead.
 */
actual object SampleSdk {
    actual val isInitialized: Boolean get() = EncatchBridge.shared().isInitialized()

    actual suspend fun init(apiKey: String, apiBaseUrl: String?, webHost: String?, debugMode: Boolean) {
        val config = EncatchBridgeConfig().apply {
            setApiBaseUrl(apiBaseUrl)
            setWebHost(webHost)
            setDebugMode(debugMode)
        }
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().initializeWithApiKey(apiKey, config) { error ->
                if (error != null) {
                    cont.resumeWithException(RuntimeException(error.localizedDescription))
                } else {
                    cont.resume(Unit)
                }
            }
        }
    }

    actual suspend fun showForm(formId: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().showForm(formId) { error ->
                if (error != null) {
                    cont.resumeWithException(RuntimeException(error.localizedDescription))
                } else {
                    cont.resume(Unit)
                }
            }
        }
    }
}
