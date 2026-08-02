package com.encatch.iosnativeui

import com.encatch.core.EncatchInternalEmitter
import com.encatch.core.InternalEvent

/**
 * Native Kotlin/Native port of `swift/`'s `EncatchFormHost` — installs the modal form UI at the
 * app root. Call [install] once, typically from the app's entry point (e.g. the root
 * `UIViewController` factory function's first invocation). Subscribes to `EncatchInternalEmitter`
 * for `ShowForm` (presentation != "inline") / `DismissForm` and presents/dismisses
 * [EncatchNativeFormViewController] on the topmost view controller.
 *
 * Exists because linking `swift/`'s package (which already has this) alongside a consumer's own
 * Kotlin/Native XCFramework would statically embed `:core` twice, producing two disconnected
 * `Encatch` singletons in one process — see HANDOFF notes on `EncatchNativeInlineFormView`. Any
 * app using this must NOT also link `swift/`.
 */
object EncatchNativeFormHost {
    private var installed = false
    private var currentController: EncatchNativeFormViewController? = null

    fun install() {
        if (installed) return
        installed = true

        EncatchInternalEmitter.on { event ->
            runOnMain {
                when (event) {
                    is InternalEvent.ShowForm -> {
                        if (event.payload.presentation != "inline") {
                            val presenter = topmostViewController()
                            if (presenter != null) {
                                currentController?.removeOverlay()
                                val controller = EncatchNativeFormViewController()
                                currentController = controller
                                controller.present(event.payload, from = presenter)
                            }
                        }
                    }
                    is InternalEvent.DismissForm -> {
                        currentController?.removeOverlay()
                        currentController = null
                    }
                    else -> Unit
                }
            }
        }
    }
}
