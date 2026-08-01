package com.encatch.composesample

import platform.UIKit.UIView

/**
 * Kotlin/Native can't cinterop against a Swift Package directly, so this module's iOS
 * `EncatchInlineFormHost` actual can't construct `swift/`'s `EncatchInlineFormView` itself.
 * Instead, the real iOS host app (built in Xcode, linking both `EncatchComposeSample.xcframework`
 * and the `swift/` package) sets [inlineFormViewFactory] once at startup, e.g.:
 *
 * ```swift
 * IOSNativeViewBridge.shared.inlineFormViewFactory = { formId in
 *     let view = EncatchInlineFormView()
 *     view.formId = formId
 *     return view
 * }
 * ```
 */
object IOSNativeViewBridge {
    var inlineFormViewFactory: ((formId: String) -> UIView)? = null
}
