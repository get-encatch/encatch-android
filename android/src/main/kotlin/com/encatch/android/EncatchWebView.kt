package com.encatch.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
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
                if (!sameOrigin) {
                    request.deny()
                    return
                }

                // Granting the WebView request only takes effect if the app itself holds the
                // matching Android runtime permissions — without them, getUserMedia silently
                // fails and the form's video/audio recorder does nothing. If the host app
                // DECLARES the permissions but hasn't been granted them yet, the SDK shows the
                // system dialog itself (via an invisible proxy activity) so recording questions
                // are drop-in. Apps that don't declare CAMERA/RECORD_AUDIO get a deny plus a
                // log hint — the SDK deliberately doesn't add permissions to consumers'
                // manifests.
                val needed = request.resources.mapNotNull {
                    when (it) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> android.Manifest.permission.CAMERA
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> android.Manifest.permission.RECORD_AUDIO
                        else -> null
                    }
                }.distinct()
                val missing = needed.filter {
                    context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    request.grant(request.resources)
                    return
                }

                val declared = runCatching {
                    context.packageManager
                        .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                        .requestedPermissions?.toSet() ?: emptySet()
                }.getOrDefault(emptySet())
                if (!declared.containsAll(missing)) {
                    android.util.Log.w(
                        "Encatch",
                        "Form requested ${missing.joinToString()} but the app manifest doesn't declare " +
                            "them — video/audio recording questions need these <uses-permission> entries.",
                    )
                    request.deny()
                    return
                }

                EncatchPermissionActivity.launch(context, missing.toTypedArray()) { granted ->
                    // grant/deny must run on the UI thread; the proxy activity calls back there,
                    // but post defensively — a leaked PermissionRequest freezes the recorder UI.
                    post { if (granted) request.grant(request.resources) else request.deny() }
                }
            }

            // Without this override, Android WebView silently ignores <input type="file"> —
            // the form's file-upload/gallery/camera pickers do nothing. (react-native-webview
            // implements this internally, which is why the RN SDK never needed it.) The system
            // chooser is launched via a translucent proxy activity, since a plain View can't
            // receive onActivityResult.
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                // A second request while one is pending must resolve the first with null —
                // leaking a ValueCallback freezes all future choosers in this WebView.
                EncatchFileChooserActivity.pendingCallback?.onReceiveValue(null)
                EncatchFileChooserActivity.pendingCallback = null

                val content = fileChooserParams.createIntent()
                if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                // Offer the camera alongside the file picker when images are acceptable — the
                // capture writes to a FileProvider-backed cache file (see AndroidManifest).
                var cameraOutput: Uri? = null
                val acceptTypes = fileChooserParams.acceptTypes.filter { it.isNotBlank() }
                val acceptsImages = acceptTypes.isEmpty() || acceptTypes.any { it == "*/*" || it.startsWith("image/") }
                val cameraIntent = if (acceptsImages) {
                    runCatching {
                        val photoFile = java.io.File.createTempFile("encatch_capture_", ".jpg", context.cacheDir)
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.encatch.fileprovider", photoFile,
                        )
                        cameraOutput = uri
                        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }.getOrNull()
                } else {
                    null
                }

                val chooser = Intent(Intent.ACTION_CHOOSER).putExtra(Intent.EXTRA_INTENT, content)
                if (cameraIntent != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                }

                EncatchFileChooserActivity.pendingCallback = filePathCallback
                EncatchFileChooserActivity.pendingCameraOutput = cameraOutput
                EncatchFileChooserActivity.pendingChooserIntent = chooser
                val launched = runCatching {
                    context.startActivity(
                        Intent(context, EncatchFileChooserActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
                if (!launched) {
                    EncatchFileChooserActivity.pendingCallback = null
                    EncatchFileChooserActivity.pendingCameraOutput = null
                    EncatchFileChooserActivity.pendingChooserIntent = null
                    filePathCallback.onReceiveValue(null)
                }
                return launched
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
                // didCrash() is API 26+; minSdk is 24
                val crashed = if (android.os.Build.VERSION.SDK_INT >= 26) detail.didCrash().toString() else "unknown"
                onUnrecoverableFailure?.invoke("render process gone (crashed=$crashed)")
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
        // Thread guard, not just documentation: the bridge invokes this from Dispatchers.Default
        // coroutines (upload responses, refine-text/QnA replies) and from network-thread progress
        // callbacks — WebView hard-throws "A WebView method was called on thread ..." for any
        // call off the thread it was created on.
        val script = buildSdkMessageInjectionScript(message)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            evaluateJavascript(script, null)
        } else {
            post { evaluateJavascript(script, null) }
        }
    }

    private inner class EncatchJsInterface {
        @JavascriptInterface
        fun postMessage(raw: String) {
            post { bridge?.handleMessage(raw) }
        }
    }
}
