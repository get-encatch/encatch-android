@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.encatch.composesample

import androidx.compose.ui.window.ComposeUIViewController
import com.encatch.bridge.EncatchBridge
import platform.UIKit.UIViewController

/**
 * Swift-callable entry point producing the root `UIViewController` for the Compose Multiplatform
 * sample screen. The real iOS host app embeds this (e.g. via `UIViewControllerRepresentable` in
 * SwiftUI). Installs the modal form host once via the cinterop'd `EncatchBridge.installFormHost()`
 * (Kotlin binding for `ios-native`'s `EncatchFormHost.install()`, see `EncatchBridge.swift`) — the
 * inline form path handles its own slot registration per-view, but the modal path needs a single
 * app-wide listener, same as `swift/`'s `EncatchFormHost`.
 */
@Suppress("unused")
fun ComposeSampleViewController(mockServerBaseUrl: String?): UIViewController {
    EncatchBridge.installFormHost()
    return ComposeUIViewController { ComposeSampleApp(mockServerBaseUrl) }
}
