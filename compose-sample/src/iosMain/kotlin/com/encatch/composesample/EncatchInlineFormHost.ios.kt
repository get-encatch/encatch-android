@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.encatch.composesample

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.encatch.bridge.EncatchInlineFormView

@Composable
actual fun EncatchInlineFormHost(formId: String, modifier: Modifier) {
    // isNativeAccessibilityEnabled defaults to false — Compose Multiplatform's iOS interop
    // views don't expose their native accessibility subtree (e.g. the hosted WKWebView's DOM
    // content surfaced via ios-native's bridge) to the app's accessibility tree unless opted
    // in. Without this, the embedded form's "Submit" button renders and is tappable by raw
    // coordinate, but is invisible to XCUITest/VoiceOver — found via a real automated UI test
    // (ios-compose-sample/UITests) that could tap the button by identifier but never see the
    // inline form's Submit button, despite the exact same content working fine when presented
    // as a real modal UIViewController (a separate, non-interop native window).
    UIKitView(
        factory = { EncatchInlineFormView().apply { setFormId(formId) } },
        modifier = modifier,
        update = { view -> view.setFormId(formId) },
        properties = UIKitInteropProperties(isNativeAccessibilityEnabled = true),
    )
}
