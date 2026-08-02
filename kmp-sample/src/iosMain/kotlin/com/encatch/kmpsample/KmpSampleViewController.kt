@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.encatch.kmpsample

import com.encatch.iosnativeui.EncatchNativeFormHost
import com.encatch.iosnativeui.EncatchNativeInlineFormView
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

/**
 * Swift-callable entry point producing the root `UIViewController` for the KMP host sample
 * screen — built entirely in Kotlin/Native (no SwiftUI, no Swift business logic) to demonstrate
 * that a KMP app's iosMain can drive a real native screen straight from commonMain calls.
 * Installs [EncatchNativeFormHost] once (the modal path) and embeds a real
 * [EncatchNativeInlineFormView] (the inline path) — both from `:ios-native-form-ui`, the same
 * native Kotlin/Native form UI `:compose-sample`'s iOS side uses.
 */
@Suppress("unused")
fun KmpSampleViewController(mockServerBaseUrl: String?): UIViewController {
    EncatchNativeFormHost.install()
    return KmpRootViewController(mockServerBaseUrl)
}

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
    }

    init {
        fun button(title: String, onTap: () -> Unit): UIButton {
            val button = UIButton.buttonWithType(platform.UIKit.UIButtonTypeSystem)
            button.setTitle(title, forState = platform.UIKit.UIControlStateNormal)
            button.backgroundColor = UIColor(red = 0.35, green = 0.30, blue = 0.55, alpha = 1.0)
            button.setTitleColor(UIColor.whiteColor, forState = platform.UIKit.UIControlStateNormal)
            button.layer.cornerRadius = 8.0
            val target = ClosureTarget(onTap)
            targets.add(target)
            button.addTarget(
                target = target,
                action = platform.objc.sel_registerName("invoke"),
                forControlEvents = platform.UIKit.UIControlEventTouchUpInside,
            )
            return button
        }

        val initButton = button("Init SDK") {
            scope.launch { statusLabel.text = SampleAppController.initSdk(mockServerBaseUrl) }
        }
        val modalButton = button("Show modal form") {
            scope.launch { statusLabel.text = SampleAppController.showModalForm() }
        }
        val inlineForm = EncatchNativeInlineFormView().apply {
            formId = "kmp-inline-form-id"
            setTranslatesAutoresizingMaskIntoConstraints(false)
        }
        val inlineButton = button("Show inline form") {
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
