package com.encatch.composesample

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Swift-callable entry point producing the root `UIViewController` for the Compose Multiplatform
 * sample screen. The real iOS host app embeds this (e.g. via `UIViewControllerRepresentable` in
 * SwiftUI). No manual form-host install here: `:kmp-sdk`'s `Encatch.init(...)` (called from
 * `ComposeSampleScreen`) already installs the modal form host internally via
 * `EncatchBridge.installFormHost()` — see `kmp-sdk/src/iosMain/kotlin/com/encatch/sdk/Encatch.ios.kt`.
 */
@Suppress("unused")
fun ComposeSampleViewController(mockServerBaseUrl: String?): UIViewController =
    ComposeUIViewController { ComposeSampleApp(mockServerBaseUrl) }
