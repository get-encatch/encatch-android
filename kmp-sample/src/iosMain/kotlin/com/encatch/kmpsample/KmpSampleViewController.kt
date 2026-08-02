@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.encatch.kmpsample

import com.encatch.bridge.EncatchInlineFormView
import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIButton
import platform.UIKit.UIColor
import platform.UIKit.UILabel
import platform.UIKit.UIStackView
import platform.UIKit.UIViewController
import platform.Foundation.setValue

/**
 * Sets `accessibilityIdentifier` via Objective-C key-value coding rather than a direct property
 * assignment or a cast to `UIAccessibilityIdentificationProtocol`. Real UIKit classes
 * (`UIView`/`UILabel`/`UIButton`, ...) expose `accessibilityIdentifier` through an
 * Objective-C *category* on `NSObject`, not through formal protocol conformance — Kotlin/Native's
 * generated `UIAccessibilityIdentificationProtocol` binding exists (matching the header), but
 * casting a real `UIView` instance to it throws `TypeCastException` at runtime (crashed the app
 * on launch when first tried here: "class UILabel cannot be cast to class
 * platform.UIKit.UIAccessibilityIdentificationProtocol") because the class doesn't formally
 * declare that protocol's conformance at the Objective-C runtime level. KVC bypasses static
 * typing entirely and talks to the same underlying `setAccessibilityIdentifier:` selector.
 */
private fun platform.darwin.NSObject.setAccessibilityIdentifierViaKVC(identifier: String) {
    this.setValue(identifier as Any?, forKey = "accessibilityIdentifier")
}

/**
 * Swift-callable entry point producing the root `UIViewController` for the KMP host sample
 * screen — built entirely in Kotlin/Native (no SwiftUI, no Swift business logic) to demonstrate
 * that a KMP app's iosMain can drive a real native screen straight from commonMain calls, this
 * time backed by `:kmp-sdk`'s `Encatch` (which itself forwards through Kotlin/Native cinterop
 * onto the pure-Swift `ios-native` SDK). No manual form-host install here: `:kmp-sdk`'s
 * `Encatch.init(...)` (called from `SampleAppController.initSdk`) already installs the modal form
 * host internally — see `kmp-sdk/src/iosMain/kotlin/com/encatch/sdk/Encatch.ios.kt`. This screen
 * still embeds a real [EncatchInlineFormView] directly via this module's own cinterop bindings
 * for the inline-form path, since `:kmp-sdk` has no Compose dependency and doesn't expose the raw
 * view type itself — see this module's `build.gradle.kts` for that known gap.
 */
@Suppress("unused")
fun KmpSampleViewController(mockServerBaseUrl: String?): UIViewController =
    KmpRootViewController(mockServerBaseUrl)

// NOTE: our custom cinterop bridge doesn't synthesize Kotlin properties for the generated
// header's Objective-C `@property` declarations (see `SampleSdk.ios.kt`'s doc comment) — hence
// `setFormId(...)` below instead of `formId = ...`.

/**
 * `UIControl.addTarget` doesn't retain its target — a bare `ClosureTarget` created inline gets
 * deallocated right after `addTarget` returns, silently dropping the tap handler (found via a real
 * tap doing nothing on-device, not a compile/type error). This subclass holds the [targets] list
 * so they live as long as the controller does.
 */
private class KmpRootViewController(mockServerBaseUrl: String?) : UIViewController(nibName = null, bundle = null) {
    private val targets = mutableListOf<ClosureTarget>()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val statusLabel = UILabel().apply {
        text = "Not initialized"
        numberOfLines = 0
        setAccessibilityIdentifierViaKVC("statusText")
    }

    init {
        fun button(title: String, accessibilityId: String, onTap: () -> Unit): UIButton {
            val button = UIButton.buttonWithType(platform.UIKit.UIButtonTypeSystem)
            button.setTitle(title, forState = platform.UIKit.UIControlStateNormal)
            button.backgroundColor = UIColor(red = 0.35, green = 0.30, blue = 0.55, alpha = 1.0)
            button.setTitleColor(UIColor.whiteColor, forState = platform.UIKit.UIControlStateNormal)
            button.layer.cornerRadius = 8.0
            button.setAccessibilityIdentifierViaKVC(accessibilityId)
            val target = ClosureTarget(onTap)
            targets.add(target)
            button.addTarget(
                target = target,
                action = platform.objc.sel_registerName("invoke"),
                forControlEvents = platform.UIKit.UIControlEventTouchUpInside,
            )
            return button
        }

        val initButton = button("Init SDK", "initButton") {
            scope.launch { statusLabel.text = SampleAppController.initSdk(mockServerBaseUrl) }
        }
        val modalButton = button("Show modal form", "showModalButton") {
            scope.launch { statusLabel.text = SampleAppController.showModalForm() }
        }
        val inlineForm = EncatchInlineFormView().apply {
            setFormId("kmp-inline-form-id")
            setTranslatesAutoresizingMaskIntoConstraints(false)
        }
        val inlineButton = button("Show inline form", "showInlineButton") {
            scope.launch { statusLabel.text = SampleAppController.showInlineForm() }
        }

        val stack = UIStackView(arrangedSubviews = listOf(statusLabel, initButton, modalButton, inlineForm, inlineButton))
        stack.axis = platform.UIKit.UILayoutConstraintAxisVertical
        stack.spacing = 16.0
        stack.setTranslatesAutoresizingMaskIntoConstraints(false)

        view.backgroundColor = UIColor.whiteColor
        view.addSubview(stack)
        NSLayoutConstraint.activateConstraints(
            listOf(
                stack.leadingAnchor.constraintEqualToAnchor(view.leadingAnchor, constant = 24.0),
                stack.trailingAnchor.constraintEqualToAnchor(view.trailingAnchor, constant = -24.0),
                stack.topAnchor.constraintEqualToAnchor(view.safeAreaLayoutGuide.topAnchor, constant = 24.0),
            ),
        )
    }
}

/** Bridges a Kotlin closure to a UIKit target-action selector (`UIButton.addTarget` needs an ObjC object). */
private class ClosureTarget(private val action: () -> Unit) : platform.darwin.NSObject() {
    @Suppress("unused")
    @ObjCAction
    fun invoke() {
        action()
    }
}
