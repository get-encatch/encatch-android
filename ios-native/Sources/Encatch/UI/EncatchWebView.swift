#if canImport(UIKit)
import WebKit

/// Wraps `WKWebView` to host the Encatch form page, mirroring `:android`'s `EncatchWebView`: JS
/// bridge via `postMessage`, same-origin camera/mic auto-grant, navigation restricted to the
/// loaded form's origin+path.
///
/// The hosted form page (shared with the RN/Android SDKs) always calls
/// `window.ReactNativeWebView.postMessage(json)` — WKWebView's native bridge mechanism is
/// `window.webkit.messageHandlers.<name>.postMessage(...)` instead, so a `WKUserScript` injected
/// at document-start defines `window.ReactNativeWebView` as a thin shim over that, letting the
/// unmodified web form work with this native bridge too (same idea as Android's exact
/// `"ReactNativeWebView"` JS-interface name).
final class EncatchWebView: WKWebView {

    private static let bridgeMessageHandlerName = "encatchBridge"

    private static let bridgeShimScript = WKUserScript(
        source: """
        window.ReactNativeWebView = {
            postMessage: function (message) {
                window.webkit.messageHandlers.\(bridgeMessageHandlerName).postMessage(message);
            }
        };
        """,
        injectionTime: .atDocumentStart,
        forMainFrameOnly: true
    )

    var bridge: FormWebViewBridge?
    /// Fired when the form page can no longer possibly become interactive: a main-frame load
    /// failure, or WebKit's web-content process dying. Hosts must tear the form UI down —
    /// without this the close button (which lives inside the web page) can never appear and
    /// the user is trapped behind the overlay.
    var onUnrecoverableFailure: ((String) -> Void)?
    private var loadedUrl: String = ""
    private let messageReceiver = ScriptMessageReceiver()

    init() {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.userContentController.addUserScript(Self.bridgeShimScript)

        super.init(frame: .zero, configuration: configuration)

        messageReceiver.onMessage = { [weak self] raw in self?.bridge?.handleMessage(raw) }
        configuration.userContentController.add(messageReceiver, name: Self.bridgeMessageHandlerName)

        navigationDelegate = self
        uiDelegate = self
        backgroundColor = .clear
        isOpaque = false
        scrollView.bounces = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    func loadFormUrl(_ url: String) {
        loadedUrl = url
        guard let requestUrl = URL(string: url) else { return }
        load(URLRequest(url: requestUrl))
    }

    /// Native -> WebView: injects an `sdk:*` message, mirrors `injectSDKMessage` in
    /// `useEncatchFormWebView.ts`.
    func sendToWebView(_ message: SDKMessage) {
        evaluateJavaScript(buildSdkMessageInjectionScript(message: message), completionHandler: nil)
    }

    /// Bridges `WKScriptMessageHandler` (a separate object, not `self`, avoids the well-known
    /// WKUserContentController retain cycle when a WKWebView's own delegate is also its message
    /// handler).
    private final class ScriptMessageReceiver: NSObject, WKScriptMessageHandler {
        var onMessage: ((String) -> Void)?

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let raw = message.body as? String else { return }
            onMessage?(raw)
        }
    }
}

extension EncatchWebView: WKNavigationDelegate {
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let requestUrl = navigationAction.request.url?.absoluteString ?? ""
        let isTopFrame = navigationAction.targetFrame?.isMainFrame ?? true
        let allow = bridge?.shouldAllowNavigation(requestUrl: requestUrl, formWebViewUrl: loadedUrl, isTopFrame: isTopFrame) ?? true
        decisionHandler(allow ? .allow : .cancel)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        // Inline embeds need the sizing fix so the page reports true (including shrinking)
        // content heights — see INLINE_WEBVIEW_SIZING_FIX_SCRIPT.
        if bridge?.presentation == "inline" {
            evaluateJavaScript(INLINE_WEBVIEW_SIZING_FIX_SCRIPT, completionHandler: nil)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.bridge?.handleFormReady()
        }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        // NSURLErrorCancelled fires for ordinary in-page navigation cancellations — not fatal.
        guard (error as NSError).code != NSURLErrorCancelled else { return }
        onUnrecoverableFailure?("load failed: \(error.localizedDescription)")
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        guard (error as NSError).code != NSURLErrorCancelled else { return }
        onUnrecoverableFailure?("load failed: \(error.localizedDescription)")
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        onUnrecoverableFailure?("web content process terminated")
    }
}

extension EncatchWebView: WKUIDelegate {
    @available(iOS 15.0, *)
    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType, decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        let formHost = URL(string: loadedUrl)?.host
        decisionHandler(origin.host == formHost ? .grant : .deny)
    }
}
#endif
