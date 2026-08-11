@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.encatch.sdk.compose

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.encatch.bridge.EncatchInlineFormView

/**
 * iOS `actual`. Copied from `compose-sample`'s former `EncatchInlineFormHost.ios.kt` (now deleted
 * in favour of this library composable), renamed — including its `isNativeAccessibilityEnabled`
 * fix below.
 *
 * No form-host-install plumbing needed here: `:kmp-sdk`'s `Encatch.ios.kt` `init(...)` already
 * calls `EncatchBridge.installFormHost()` internally (verified directly in that file), since iOS
 * has no Application-equivalent context requirement the way Android's `EncatchFormHost.install`
 * does — see `EncatchInlineForm.android.kt`'s doc comment for the full asymmetry explanation.
 */
@Composable
actual fun EncatchInlineForm(formId: String?, modifier: Modifier) {
    // Auto height: Compose Multiplatform's UIKitView ignores a UIView's own Auto Layout height
    // constraint / intrinsicContentSize, so the native view's self-sizing (skeleton placeholder,
    // live form:resize values — see ios-native's EncatchInlineFormView) is bridged out through
    // its onHeightChange callback and applied as a Compose height modifier instead. Points map
    // 1:1 to dp on iOS. Callers should NOT pin their own height — pass layout-only modifiers.
    var heightDp by remember { mutableStateOf(0.0) }
    // isNativeAccessibilityEnabled defaults to false — Compose Multiplatform's iOS interop
    // views don't expose their native accessibility subtree (e.g. the hosted WKWebView's DOM
    // content surfaced via ios-native's bridge) to the app's accessibility tree unless opted
    // in. Without this, the embedded form's "Submit" button renders and is tappable by raw
    // coordinate, but is invisible to XCUITest/VoiceOver — found via a real automated UI test
    // (ios-compose-sample/UITests) that could tap the button by identifier but never see the
    // inline form's Submit button, despite the exact same content working fine when presented
    // as a real modal UIViewController (a separate, non-interop native window).
    UIKitView(
        factory = {
            EncatchInlineFormView().apply {
                setFormId(formId)
                setOnHeightChange { height -> heightDp = height }
            }
        },
        modifier = modifier.height(heightDp.dp),
        update = { view -> view.setFormId(formId) },
        properties = UIKitInteropProperties(isNativeAccessibilityEnabled = true),
    )
}
