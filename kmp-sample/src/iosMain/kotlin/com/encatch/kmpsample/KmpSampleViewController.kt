@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.encatch.kmpsample

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
 * that a KMP app's iosMain can drive a real native screen straight from commonMain calls. Kept
 * deliberately UI-light (status text + buttons, no hosted-form rendering): the form-rendering UI
 * is already proven end-to-end by variants 3 & 4; giving it its own UIKit-in-Kotlin screen here
 * would just be redundant WebView-porting effort for no new coverage.
 */
@Suppress("unused")
fun KmpSampleViewController(mockServerBaseUrl: String?): UIViewController {
    val controller = UIViewController()
    val scope = CoroutineScope(Dispatchers.Main)

    val statusLabel = UILabel().apply {
        text = "Not initialized"
        numberOfLines = 0
    }

    fun button(title: String, onTap: () -> Unit): UIButton {
        val button = UIButton.buttonWithType(platform.UIKit.UIButtonTypeSystem)
        button.setTitle(title, forState = platform.UIKit.UIControlStateNormal)
        button.backgroundColor = UIColor(red = 0.35, green = 0.30, blue = 0.55, alpha = 1.0)
        button.setTitleColor(UIColor.whiteColor, forState = platform.UIKit.UIControlStateNormal)
        button.layer.cornerRadius = 8.0
        button.addTarget(
            target = ClosureTarget(onTap),
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
    val inlineButton = button("Show inline form") {
        scope.launch { statusLabel.text = SampleAppController.showInlineForm() }
    }

    val stack = UIStackView(arrangedSubviews = listOf(statusLabel, initButton, modalButton, inlineButton))
    stack.axis = platform.UIKit.UILayoutConstraintAxisVertical
    stack.spacing = 16.0
    stack.setTranslatesAutoresizingMaskIntoConstraints(false)

    controller.view.backgroundColor = UIColor.whiteColor
    controller.view.addSubview(stack)
    NSLayoutConstraint.activateConstraints(
        listOf(
            stack.leadingAnchor.constraintEqualToAnchor(controller.view.leadingAnchor, constant = 24.0),
            stack.trailingAnchor.constraintEqualToAnchor(controller.view.trailingAnchor, constant = -24.0),
            stack.topAnchor.constraintEqualToAnchor(controller.view.safeAreaLayoutGuide.topAnchor, constant = 24.0),
        ),
    )

    return controller
}

/** Bridges a Kotlin closure to a UIKit target-action selector (`UIButton.addTarget` needs an ObjC object). */
private class ClosureTarget(private val action: () -> Unit) : platform.darwin.NSObject() {
    @Suppress("unused")
    @ObjCAction
    fun invoke() {
        action()
    }
}
