package com.encatch.composesample

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Swift-callable entry point producing the root `UIViewController` for the Compose Multiplatform
 * sample screen. The real iOS host app embeds this (e.g. via `UIViewControllerRepresentable` in
 * SwiftUI) after setting [IOSNativeViewBridge.inlineFormViewFactory].
 */
@Suppress("unused")
fun ComposeSampleViewController(mockServerBaseUrl: String?): UIViewController =
    ComposeUIViewController { ComposeSampleApp(mockServerBaseUrl) }
