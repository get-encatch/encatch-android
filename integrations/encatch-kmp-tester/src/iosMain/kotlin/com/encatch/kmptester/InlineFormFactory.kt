@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.encatch.kmptester

import com.encatch.bridge.EncatchInlineFormView
import platform.UIKit.UIView

/**
 * Swift-callable factory for a real inline form view — `:kmp-sdk` has no Compose dependency and
 * doesn't expose `EncatchInlineFormView`'s type itself (same known gap as `kmp-sample`, see
 * `build.gradle.kts`), so this module keeps its own cinterop onto `ios-native/`'s `@objc` facade
 * for this one view type. Returned as the plain `UIView` supertype so the generated framework
 * header doesn't need to re-export the cinterop bridge's own types to Swift.
 *
 * NOTE: our custom cinterop bridge doesn't synthesize Kotlin properties for the generated
 * header's Objective-C `@property` declarations — hence `setFormId(...)` instead of `formId = ...`.
 */
@Suppress("unused")
fun makeInlineFormView(formId: String?): UIView =
    EncatchInlineFormView().apply { setFormId(formId) }
