package com.encatch.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.encatch.core.FormWebViewBridge
import com.encatch.core.INLINE_WEBVIEW_SIZING_FIX_SCRIPT
import com.encatch.core.SDKMessage
import com.encatch.core.buildSdkMessageInjectionScript

/**
 * Wraps [android.webkit.WebView] to host the Encatch form page, mirroring `EncatchWebView.tsx`'s
 * WebView configuration: JS bridge via `postMessage`, same-origin camera/mic auto-grant, and
 * navigation restricted to the loaded form's origin+path.
 */
@SuppressLint("SetJavaScriptEnabled")
class EncatchWebView(context: Context) : WebView(context) {

    private var loadedUrl: String = ""
    var bridge: FormWebViewBridge? = null
    var onLoadFallbackReady: (() -> Unit)? = null

    /**
     * Fired when the form page can no longer possibly become interactive: a main-frame load
     * failure, or the WebView render process dying. Hosts must tear the form UI down — the
     * close button lives inside the web page, so without this the user is trapped behind the
     * overlay. NOTE: handling onRenderProcessGone is also load-bearing on Android — the default
     * behavior kills the entire host app process.
     */
    var onUnrecoverableFailure: ((String) -> Unit)? = null

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
        }

        // The hosted form page (form.encatch.com) is shared with the RN SDK and calls
        // `window.ReactNativeWebView.postMessage(...)` — matching that exact global name
        // (the same one react-native-webview's own Android implementation registers) lets
        // the unmodified web form work with this native bridge too.
        addJavascriptInterface(EncatchJsInterface(), "ReactNativeWebView")

        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val sameOrigin = runCatching {
                    Uri.parse(request.origin.toString()).host == Uri.parse(loadedUrl).host
                }.getOrDefault(false)

                if (sameOrigin) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allow = bridge?.shouldAllowNavigation(request.url.toString(), loadedUrl, request.isForMainFrame) ?: true
                return !allow
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Inline embeds need the sizing fix so the page reports true (including
                // shrinking) content heights — see INLINE_WEBVIEW_SIZING_FIX_SCRIPT.
                if (bridge?.presentation == "inline") {
                    view.evaluateJavascript(INLINE_WEBVIEW_SIZING_FIX_SCRIPT, null)
                }
                postDelayed({ bridge?.handleFormReady() }, 300)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    onUnrecoverableFailure?.invoke("load failed: ${error.description}")
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                onUnrecoverableFailure?.invoke("render process gone (crashed=${detail.didCrash()})")
                return true // handled — returning false would kill the entire host app
            }
        }
    }

    fun loadFormUrl(url: String) {
        loadedUrl = url
        loadUrl(url)
    }

    /** Native -> WebView: injects an `sdk:*` message, mirrors `injectSDKMessage` in `useEncatchFormWebView.ts`. */
    fun sendToWebView(message: SDKMessage) {
        evaluateJavascript(buildSdkMessageInjectionScript(message), null)
    }

    private inner class EncatchJsInterface {
        @JavascriptInterface
        fun postMessage(raw: String) {
            post { bridge?.handleMessage(raw) }
        }
    }
}
