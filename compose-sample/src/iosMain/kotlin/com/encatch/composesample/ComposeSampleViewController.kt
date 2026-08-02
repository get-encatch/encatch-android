package com.encatch.composesample

import androidx.compose.ui.window.ComposeUIViewController
import com.encatch.iosnativeui.EncatchNativeFormHost
import platform.UIKit.UIViewController

/**
 * Swift-callable entry point producing the root `UIViewController` for the Compose Multiplatform
 * sample screen. The real iOS host app embeds this (e.g. via `UIViewControllerRepresentable` in
 * SwiftUI). Installs [EncatchNativeFormHost] (the native Kotlin/Native modal form host) once —
 * the inline form path handles its own slot registration per-view, but the modal path needs a
 * single app-wide listener, same as `swift/`'s `EncatchFormHost`.
 */
@Suppress("unused")
fun ComposeSampleViewController(mockServerBaseUrl: String?): UIViewController {
    EncatchNativeFormHost.install()
    return ComposeUIViewController { ComposeSampleApp(mockServerBaseUrl) }
}
