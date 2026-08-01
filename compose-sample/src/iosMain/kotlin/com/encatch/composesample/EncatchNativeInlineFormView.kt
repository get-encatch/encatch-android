@file:OptIn(ExperimentalForeignApi::class)

package com.encatch.composesample

import com.encatch.core.Encatch
import com.encatch.core.EncatchInternalEmitter
import com.encatch.core.FormWebViewBridge
import com.encatch.core.InlineSlotRegistry
import com.encatch.core.InternalEvent
import com.encatch.core.SDKMessage
import com.encatch.core.ShowFormPayload
import com.encatch.core.buildSdkMessageInjectionScript
import com.encatch.core.extractShareableMode
import com.encatch.core.extractThemeJsonForMode
import com.encatch.core.getBackgroundColor
import com.encatch.core.getInlineBorderRadii
import com.encatch.core.parseCssColorToArgb
import com.encatch.core.resolveActiveMode
import com.encatch.core.resolveCornerRadiusDp
import com.encatch.core.resolveCornersFromFormConfig
import com.encatch.core.resolveSystemColorScheme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIActivityIndicatorView
import platform.UIKit.UIColor
import platform.UIKit.UITraitCollection
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIView
import platform.UIKit.UIWindow
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
import kotlin.math.max
import kotlin.math.min

private const val BRIDGE_MESSAGE_HANDLER_NAME = "encatchBridge"

private const val BRIDGE_SHIM_SCRIPT = """
window.ReactNativeWebView = {
    postMessage: function (message) {
        window.webkit.messageHandlers.$BRIDGE_MESSAGE_HANDLER_NAME.postMessage(message);
    }
};
"""

private fun buildInlineFormWebViewUrl(webHost: String, formId: String, instanceKey: Int, debugMode: Boolean): String {
    val params = mutableListOf("formId=$formId", "ts=$instanceKey", "presentation=inline")
    if (debugMode) params.add("debug=true")
    return "$webHost/s/react-native-sdk-form?" + params.joinToString("&")
}

/**
 * Native Kotlin/Native port of `swift/`'s `EncatchInlineFormView`/`EncatchWebView`, written
 * directly in `:compose-sample`'s iosMain rather than reusing the Swift Package. Kotlin/Native
 * can't cinterop against a compiled Swift Package, and linking `swift/`'s `EncatchCore.xcframework`
 * alongside this module's own XCFramework would statically embed `:core` twice — two independent
 * `Encatch` singletons in one process, found via a real crash while testing this variant (see
 * HANDOFF.md). All business logic here is [FormWebViewBridge] from `:core` commonMain, unchanged
 * from `:android`/`swift`'s usage — only the raw WKWebView/UIView plumbing is iOS-native Kotlin.
 */
class EncatchNativeInlineFormView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {

    var formId: String? = null
        set(value) {
            field = value
            slotId?.let { InlineSlotRegistry.updateInlineSlot(it, value) }
        }

    var minHeight: Double = 0.0
    var onOverlayOpenChange: ((Boolean) -> Unit)? = null

    private var slotId: String? = null
    private var unsubscribe: (() -> Unit)? = null

    private var webView: EncatchNativeWebView? = null
    private var bridge: FormWebViewBridge? = null
    private var skeleton: UIActivityIndicatorView? = null
    private var webViewInstanceKey = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var heightConstraint: NSLayoutConstraint? = null
    private var contentHeight: Double = 0.0
    private var overlayFrozenHeight: Double? = null
    private var overlayActive = false
    private var currentPayload: ShowFormPayload? = null

    init {
        clipsToBounds = true
        heightConstraint = heightAnchor.constraintEqualToConstant(0.0)
        heightConstraint?.active = true
    }

    override fun willMoveToWindow(newWindow: UIWindow?) {
        super.willMoveToWindow(newWindow)
        if (newWindow != null && slotId == null) {
            slotId = InlineSlotRegistry.registerInlineSlot(formId)
            unsubscribe = EncatchInternalEmitter.on { event -> handle(event) }
        } else if (newWindow == null && slotId != null) {
            InlineSlotRegistry.unregisterInlineSlot(slotId!!)
            slotId = null
            unsubscribe?.invoke()
            unsubscribe = null
            if (bridge?.formPayload != null) Encatch.setFormVisible(false)
            clearForm()
        }
    }

    private fun handle(event: InternalEvent) {
        when (event) {
            is InternalEvent.ShowForm -> {
                if (event.payload.presentation == "inline" && event.payload.inlineSlotId == slotId) {
                    loadForm(event.payload)
                } else if (bridge?.formPayload != null) {
                    Encatch.setFormVisible(false)
                    clearForm()
                }
            }
            is InternalEvent.DismissForm -> {
                if (bridge?.formPayload != null) {
                    Encatch.setFormVisible(false)
                    clearForm()
                }
            }
            else -> Unit
        }
    }

    private fun loadForm(payload: ShowFormPayload) {
        subviews.forEach { (it as UIView).removeFromSuperview() }
        webViewInstanceKey += 1
        contentHeight = 0.0
        overlayFrozenHeight = null
        overlayActive = false
        currentPayload = payload

        val newWebView = EncatchNativeWebView()
        val newBridge = FormWebViewBridge(
            scope = scope,
            presentation = "inline",
            onClose = { _ ->
                Encatch.setFormVisible(false)
                clearForm()
            },
            onHeightChange = { h -> applyHeight(h.toDouble()) },
            onForceFullHeight = { force -> applyForceFullHeight(force) },
            onReady = { skeleton?.stopAnimating(); skeleton?.removeFromSuperview(); skeleton = null },
            sendToWebView = { message -> newWebView.sendToWebView(message) },
            redirectOpener = { url -> Unit },
            openExternal = { _ -> Unit },
        )
        newWebView.bridge = newBridge
        newWebView.setTranslatesAutoresizingMaskIntoConstraints(false)
        addSubview(newWebView)
        NSLayoutConstraint.activateConstraints(
            listOf(
                newWebView.topAnchor.constraintEqualToAnchor(topAnchor),
                newWebView.bottomAnchor.constraintEqualToAnchor(bottomAnchor),
                newWebView.leadingAnchor.constraintEqualToAnchor(leadingAnchor),
                newWebView.trailingAnchor.constraintEqualToAnchor(trailingAnchor),
            ),
        )

        newBridge.setFormPayload(payload)
        webView = newWebView
        bridge = newBridge

        applyInlineAppearance(payload)

        val loadingIndicator = UIActivityIndicatorView()
        loadingIndicator.setTranslatesAutoresizingMaskIntoConstraints(false)
        addSubview(loadingIndicator)
        NSLayoutConstraint.activateConstraints(
            listOf(
                loadingIndicator.centerXAnchor.constraintEqualToAnchor(centerXAnchor),
                loadingIndicator.centerYAnchor.constraintEqualToAnchor(centerYAnchor),
            ),
        )
        loadingIndicator.startAnimating()
        skeleton = loadingIndicator

        applyHeight(300.0)
        Encatch.setFormVisible(true)

        val url = buildInlineFormWebViewUrl(
            webHost = Encatch.webHost,
            formId = payload.formId,
            instanceKey = webViewInstanceKey,
            debugMode = Encatch.debugMode,
        )
        newWebView.loadFormUrl(url)
    }

    private fun applyInlineAppearance(payload: ShowFormPayload) {
        val appearanceProperties = payload.formConfig.appearanceProperties
        val corners = resolveCornersFromFormConfig(appearanceProperties)

        val systemScheme = resolveSystemColorScheme(traitCollection.userInterfaceStyle == platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleDark)
        val shareableMode = extractShareableMode(appearanceProperties)
        val activeMode = resolveActiveMode(payload.theme?.wireValue ?: shareableMode, systemScheme)

        val themeJson = extractThemeJsonForMode(appearanceProperties, activeMode)
        val fallbackBg = if (activeMode == "dark") "#1a1a1a" else "#ffffff"
        val backgroundColorStr = getBackgroundColor(themeJson, fallbackBg)
        val fallbackArgb = if (activeMode == "dark") 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
        val backgroundArgb = parseCssColorToArgb(backgroundColorStr, fallbackArgb)

        val radii = getInlineBorderRadii(corners)
        val radiusPoints = resolveCornerRadiusDp(corners).toDouble()
        backgroundColor = uiColorFromArgb(backgroundArgb)
        layer.cornerRadius = if (radii.topLeftDp > 0) radiusPoints else 0.0
        webView?.backgroundColor = UIColor.clearColor
    }

    private fun clearForm() {
        webView = null
        bridge = null
        skeleton = null
        contentHeight = 0.0
        overlayFrozenHeight = null
        overlayActive = false
        currentPayload = null
        subviews.forEach { (it as UIView).removeFromSuperview() }
        applyHeight(0.0)
    }

    private fun resolveContentHeight(height: Double): Double = if (minHeight > 0) max(height, minHeight) else height

    private fun applyHeight(height: Double) {
        if (overlayActive) return
        val resolved = resolveContentHeight(height)
        contentHeight = resolved
        heightConstraint?.constant = resolved
    }

    private fun applyForceFullHeight(force: Boolean) {
        overlayActive = force
        if (force) {
            val base = if (contentHeight > 0) resolveContentHeight(contentHeight) else if (minHeight > 0) minHeight else 300.0
            val maxHeight = (window?.bounds?.useContents { size.height } ?: 800.0) * 0.8
            val frozen = min(base, maxHeight)
            overlayFrozenHeight = frozen
            heightConstraint?.constant = frozen
        } else {
            overlayFrozenHeight = null
            heightConstraint?.constant = if (contentHeight > 0) contentHeight else if (minHeight > 0) minHeight else 300.0
        }
        onOverlayOpenChange?.invoke(force)
    }

    override fun traitCollectionDidChange(previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        val payload = currentPayload ?: return
        applyInlineAppearance(payload)
    }
}

private fun uiColorFromArgb(argb: Int): UIColor {
    val value = argb.toLong() and 0xFFFFFFFFL
    val alpha = ((value shr 24) and 0xFF) / 255.0
    val red = ((value shr 16) and 0xFF) / 255.0
    val green = ((value shr 8) and 0xFF) / 255.0
    val blue = (value and 0xFF) / 255.0
    return UIColor(red = red, green = green, blue = blue, alpha = alpha)
}

/**
 * Kotlin/Native port of `swift/`'s `EncatchWebView` — WKWebView with the same `window.ReactNativeWebView`
 * JS-bridge shim, so the unmodified hosted form page works identically to the Android/Swift UIs.
 */
private class EncatchNativeWebView : WKWebView, WKNavigationDelegateProtocol {
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
