@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.encatch.iosnativeui

import com.encatch.core.FormWebViewBridge
import com.encatch.core.SDKMessage
import com.encatch.core.buildSdkMessageInjectionScript
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIColor
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

private const val BRIDGE_MESSAGE_HANDLER_NAME = "encatchBridge"

private const val BRIDGE_SHIM_SCRIPT = """
window.ReactNativeWebView = {
    postMessage: function (message) {
        window.webkit.messageHandlers.$BRIDGE_MESSAGE_HANDLER_NAME.postMessage(message);
    }
};
"""

/**
 * Builds the hosted-form WebView URL, mirrors `swift/`'s `FormWebViewURL.swift` (a plain string
 * builder there too, not shared via `:core`, since it's a light one-off).
 */
internal fun buildInlineFormWebViewUrl(webHost: String, formId: String, instanceKey: Int, debugMode: Boolean, presentation: String): String {
    val params = mutableListOf("formId=$formId", "ts=$instanceKey")
    if (debugMode) params.add("debug=true")
    if (presentation == "inline") params.add("presentation=inline")
    return "$webHost/s/react-native-sdk-form?" + params.joinToString("&")
}

internal fun uiColorFromArgb(argb: Int): UIColor {
    val value = argb.toLong() and 0xFFFFFFFFL
    val alpha = ((value shr 24) and 0xFF) / 255.0
    val red = ((value shr 16) and 0xFF) / 255.0
    val green = ((value shr 8) and 0xFF) / 255.0
    val blue = (value and 0xFF) / 255.0
    return UIColor(red = red, green = green, blue = blue, alpha = alpha)
}

/**
 * Kotlin/Native port of `swift/`'s `EncatchWebView` — WKWebView with the same
 * `window.ReactNativeWebView` JS-bridge shim, so the unmodified hosted form page works
 * identically to the Android/Swift UIs. Shared by [EncatchNativeInlineFormView] and
 * [EncatchNativeFormViewController].
 */
internal class EncatchNativeWebView : WKWebView, WKNavigationDelegateProtocol {
    var bridge: FormWebViewBridge? = null
    private var loadedUrl: String = ""
    private val messageReceiver = ScriptMessageReceiver { raw -> bridge?.handleMessage(raw) }

    constructor() : super(
        frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
        configuration = WKWebViewConfiguration().apply {
            allowsInlineMediaPlayback = true
            userContentController.addUserScript(
                WKUserScript(
                    source = BRIDGE_SHIM_SCRIPT,
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                    forMainFrameOnly = true,
                ),
            )
        },
    ) {
        configuration.userContentController.addScriptMessageHandler(messageReceiver, name = BRIDGE_MESSAGE_HANDLER_NAME)
        navigationDelegate = this
        backgroundColor = UIColor.clearColor
        opaque = false
        scrollView.bounces = false
    }

    fun loadFormUrl(url: String) {
        loadedUrl = url
        val requestUrl = NSURL.URLWithString(url) ?: return
        loadRequest(NSURLRequest.requestWithURL(requestUrl))
    }

    fun sendToWebView(message: SDKMessage) {
        evaluateJavaScript(buildSdkMessageInjectionScript(message), completionHandler = null)
    }

    override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
        val requestUrl = decidePolicyForNavigationAction.request.URL?.absoluteString ?: ""
        val isTopFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: true
        val allow = bridge?.shouldAllowNavigation(requestUrl, loadedUrl, isTopFrame) ?: true
        decisionHandler(if (allow) WKNavigationActionPolicy.WKNavigationActionPolicyAllow else WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
    }

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        bridge?.handleFormReady()
    }
}

private class ScriptMessageReceiver(private val onMessage: (String) -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        val body = didReceiveScriptMessage.body as? String ?: return
        onMessage(body)
    }
}
