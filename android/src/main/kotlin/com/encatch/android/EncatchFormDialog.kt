package com.encatch.android

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams
import com.encatch.core.Encatch
import com.encatch.core.SDKMessage
import com.encatch.core.ShowFormPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Modal overlay hosting [EncatchWebView], mirroring `EncatchWebView.tsx`'s Modal presentation:
 * full-screen or resizable-height WebView, IME-aware, dismissible via the bridge's onClose.
 */
class EncatchFormDialog(context: Context) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val webView = EncatchWebView(context)
    private val container = FrameLayout(context)
    private var webViewInstanceKey = 0
    private var isFullHeight = false
    private val redirectBrowser = RedirectBrowser(context)

    private val bridge = FormWebViewBridge(
        scope = scope,
        presentation = "modal",
        onClose = { immediate -> close(immediate) },
        onHeightChange = { height -> applyHeight(height) },
        onForceFullHeight = { force -> isFullHeight = force },
        onReady = { /* entrance animation hook, if any */ },
        sendToWebView = { message: SDKMessage -> webView.sendToWebView(message) },
        redirectOpener = redirectBrowser,
        openExternal = { url -> redirectBrowser.openExternal(url) },
    )

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        webView.bridge = bridge
        container.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(container)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        setOnDismissListener {
            Encatch.setFormVisible(false)
        }
    }

    fun present(payload: ShowFormPayload) {
        webViewInstanceKey += 1
        bridge.setFormPayload(payload)
        Encatch.setFormVisible(true)

        val url = buildFormWebViewUrl(
            webHost = Encatch.webHost,
            formId = payload.formId,
            instanceKey = webViewInstanceKey,
            debugMode = Encatch.debugMode,
            presentation = "modal",
        )
        webView.loadFormUrl(url)
        show()
    }

    private fun applyHeight(heightPx: Int) {
        if (isFullHeight) return
        webView.updateLayoutParams<FrameLayout.LayoutParams> { height = heightPx }
    }

    private fun close(immediate: Boolean) {
        Encatch.setFormVisible(false)
        if (isShowing) dismiss()
    }

    override fun dismiss() {
        super.dismiss()
        bridge.setFormPayload(null)
    }
}
