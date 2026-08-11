package com.encatch.composetester

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Swift-callable entry point producing the root `UIViewController` for the whole tester app — the
 * real iOS host app (`encatch-compose-tester-ios/`) just embeds this. No manual form-host install
 * here: `:kmp-sdk`'s `Encatch.init(...)` (called from `App.kt`'s Setup screen) already installs
 * the modal form host internally on iOS — see `compose-sdk/.../EncatchInlineForm.ios.kt`'s doc
 * comment for the full Android/iOS asymmetry explanation.
 */
@Suppress("unused")
fun ComposeTesterViewController(): UIViewController = ComposeUIViewController { TesterApp() }
