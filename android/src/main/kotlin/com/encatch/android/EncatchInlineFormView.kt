package com.encatch.android

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import com.encatch.core.Encatch
import com.encatch.core.EncatchInternalEmitter
import com.encatch.core.FormWebViewBridge
import com.encatch.core.InlineSlotRegistry
import com.encatch.core.InternalEvent
import com.encatch.core.SDKMessage
import com.encatch.core.ShowFormPayload
import com.encatch.core.getBackgroundColor
import com.encatch.core.getInlineBorderRadii
import com.encatch.core.parseCssColorToArgb
import com.encatch.core.resolveActiveMode
import com.encatch.core.resolveCornersFromFormConfig
import com.encatch.core.resolveSystemColorScheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders the Encatch form inline within the host layout — no modal, no overlay. Place it
 * anywhere in an Activity/Fragment's view hierarchy (XML or programmatically), mirroring
 * `EncatchInlineForm.tsx`.
 *
 * Routing (resolved by [InlineSlotRegistry] before this view receives anything):
 *  - Exact match: set [formId] to catch `showForm("slug")` for that id only.
 *  - Wildcard: leave [formId] null to catch any form not claimed by an exact slot.
 *  - Fallback: when no inline slot is registered, the modal [EncatchFormDialog] takes over.
 *
 * Slot registration is tied to this view's attach/detach lifecycle — unlike the RN version's
 * screen-focus tracking, a host using ViewPager/off-screen page retention should explicitly
 * detach (or set [formId] to a sentinel no form will ever match) while backgrounded, otherwise
 * a wildcard slot on a hidden page could intercept a showForm meant for the visible one.
 *
 * Keyboard/IME: this view grows to the form's full content height and disables its own
 * scrolling — the HOST layout owns scrolling and must also handle IME insets, or the keyboard
 * will cover the form's text inputs with no way to scroll them into view. In Compose, add
 * `Modifier.imePadding()` to the scroll container; in a classic View layout, use
 * `windowSoftInputMode="adjustResize"` — and on Android 15+ (edge-to-edge enforced) apply
 * `WindowInsetsCompat.Type.ime()` insets yourself, since adjustResize alone no longer resizes
 * the window. (The modal [EncatchFormDialog] handles all of this internally; only inline
 * delegates it to the host.)
 */
class EncatchInlineFormView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        /** Visible skeleton height (dp) used before the first form:resize arrives. */
        private const val LOADING_SKELETON_HEIGHT_DP = 300
    }

    /** When set, this slot only catches showForm() calls for this exact form id (slug or uuid). */
    var formId: String? = null
        set(value) {
            field = value
            slotId?.let { InlineSlotRegistry.updateInlineSlot(it, value) }
        }

    /** Minimum height floor (px) after the first form:resize. Defaults to 0. */
    var minHeight: Int = 0

    /** Called when an in-form overlay (QnA with AI, Scheduler) opens or closes. */
    var onOverlayOpenChange: ((Boolean) -> Unit)? = null

    private var slotId: String? = null
    private var unsubscribe: (() -> Unit)? = null

    private var webView: EncatchWebView? = null
    private var bridge: FormWebViewBridge? = null
    private var loadingOverlay: FormWebViewSkeleton? = null
    private var webViewInstanceKey = 0

    private var contentHeightPx = 0
    private var overlayFrozenHeightPx: Int? = null
    private var overlayActive = false
    private val redirectBrowser by lazy { RedirectBrowser(context) }

    private var currentPayload: ShowFormPayload? = null
    private var lastSystemScheme: String? = null

    /** Observes live system light/dark-mode changes while a form is active — see EncatchFormDialog's twin. */
    private val systemThemeCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val scheme = resolveSystemColorScheme(isConfigurationDark(newConfig.uiMode))
            if (scheme == lastSystemScheme) return
            lastSystemScheme = scheme
            val payload = currentPayload ?: return
            val target = webView ?: return
            val activeMode = applyInlineAppearance(payload, target)
            loadingOverlay?.let {
                removeView(it)
                val replacement = FormWebViewSkeleton(context, activeMode).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                }
                loadingOverlay = replacement
                addView(replacement)
            }
        }

        @Suppress("DEPRECATION")
        override fun onLowMemory() = Unit
        override fun onTrimMemory(level: Int) = Unit
    }

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        slotId = InlineSlotRegistry.registerInlineSlot(formId)
        // See EncatchFormHost's twin comment — emit(...) isn't guaranteed to run on the main
        // thread (automatic triggers emit from core's Dispatchers.Default scope), and
        // handleInternalEvent touches views. An event posted just before detach lands after
        // unsubscribe with slotId nulled, so it no-ops harmlessly.
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        unsubscribe = EncatchInternalEmitter.on { event -> mainHandler.post { handleInternalEvent(event) } }
        lastSystemScheme = resolveSystemColorScheme(isConfigurationDark(context.resources.configuration.uiMode))
        context.applicationContext.registerComponentCallbacks(systemThemeCallbacks)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        slotId?.let { InlineSlotRegistry.unregisterInlineSlot(it) }
        slotId = null
        unsubscribe?.invoke()
        unsubscribe = null
        context.applicationContext.unregisterComponentCallbacks(systemThemeCallbacks)

        if (bridge?.formPayload != null) {
            Encatch.setFormVisible(false)
        }
        clearForm()
    }

    private fun handleInternalEvent(event: InternalEvent) {
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
        removeAllViews()
        webViewInstanceKey += 1
        contentHeightPx = 0
        overlayFrozenHeightPx = null
        overlayActive = false
        currentPayload = payload

        val newWebView = EncatchWebView(context)
        val newBridge = FormWebViewBridge(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main),
            presentation = "inline",
            onClose = { _ ->
                Encatch.setFormVisible(false)
                clearForm()
            },
            // form:resize heights arrive in CSS px (== dp) — convert to physical px before
            // applying to LayoutParams (see EncatchFormDialog's onHeightChange note).
            onHeightChange = { h -> applyHeight(dpToPx(h)) },
            onForceFullHeight = { force -> applyForceFullHeight(force) },
            onReady = {
                // Fade the skeleton over the rendered form, then remove it (parity with the
                // modal dialog and ios-native's crossfade).
                loadingOverlay?.let { fading ->
                    fading.animate().alpha(0f).setDuration(300)
                        .withEndAction { removeView(fading) }
                        .start()
                }
                loadingOverlay = null
            },
            sendToWebView = { message: SDKMessage -> newWebView.sendToWebView(message) },
            redirectOpener = redirectBrowser,
            openExternal = { url -> redirectBrowser.openExternal(url) },
        )
        newWebView.bridge = newBridge
        newWebView.onUnrecoverableFailure = { reason ->
            android.util.Log.w("Encatch", "clearing inline form: $reason")
            Encatch.setFormVisible(false)
            clearForm()
        }
        newWebView.settings.setSupportZoom(false)
        newWebView.isVerticalScrollBarEnabled = false
        newWebView.overScrollMode = android.view.View.OVER_SCROLL_NEVER

        newBridge.setFormPayload(payload)
        webView = newWebView
        bridge = newBridge

        val activeMode = applyInlineAppearance(payload, newWebView)
        addView(newWebView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val overlay = FormWebViewSkeleton(context, activeMode).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        loadingOverlay = overlay
        addView(overlay)

        applyHeightPx(dpToPx(LOADING_SKELETON_HEIGHT_DP))
        Encatch.setFormVisible(true)

        val url = buildFormWebViewUrl(
            webHost = Encatch.webHost,
            formId = payload.formId,
            instanceKey = webViewInstanceKey,
            debugMode = Encatch.debugMode,
            presentation = "inline",
        )
        newWebView.loadFormUrl(url)
    }

    private fun applyInlineAppearance(payload: ShowFormPayload, target: EncatchWebView): String {
        val appearanceProperties = payload.formConfig.appearanceProperties as? JsonObject
        val corners = resolveCornersFromFormConfig(appearanceProperties)

        val systemScheme = resolveSystemColorScheme(isConfigurationDark(context.resources.configuration.uiMode))
        val shareableMode = appearanceProperties?.get("featureSettings")?.let { it as? JsonObject }
            ?.get("shareableMode")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val activeMode = resolveActiveMode(payload.theme?.wireValue ?: shareableMode, systemScheme)

        val themeJson = appearanceProperties?.get("themes")?.let { it as? JsonObject }
            ?.get(activeMode)?.let { it as? JsonObject }
            ?.get("theme")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val fallbackBg = if (activeMode == "dark") "#1a1a1a" else "#ffffff"
        val backgroundColorStr = getBackgroundColor(themeJson, fallbackBg)
        val backgroundArgb = parseCssColorToArgb(backgroundColorStr, if (activeMode == "dark") Color.parseColor(fallbackBg) else Color.WHITE)

        val radii = getInlineBorderRadii(corners)
        background = GradientDrawable().apply {
            setColor(backgroundArgb)
            cornerRadii = floatArrayOf(
                dpToPx(radii.topLeftDp).toFloat(), dpToPx(radii.topLeftDp).toFloat(),
                dpToPx(radii.topRightDp).toFloat(), dpToPx(radii.topRightDp).toFloat(),
                dpToPx(radii.bottomRightDp).toFloat(), dpToPx(radii.bottomRightDp).toFloat(),
                dpToPx(radii.bottomLeftDp).toFloat(), dpToPx(radii.bottomLeftDp).toFloat(),
            )
        }
        clipToOutline = true
        target.setBackgroundColor(Color.TRANSPARENT)
        return activeMode
    }

    private fun clearForm() {
        webView = null
        bridge = null
        loadingOverlay = null
        contentHeightPx = 0
        overlayFrozenHeightPx = null
        overlayActive = false
        currentPayload = null
        removeAllViews()
        applyHeightPx(0)
    }

    private fun resolveContentHeight(h: Int): Int = if (minHeight > 0) max(h, minHeight) else h

    private fun applyHeight(heightPx: Int) {
        if (overlayActive) return
        val resolved = resolveContentHeight(heightPx)
        contentHeightPx = resolved
        applyHeightPx(resolved)
    }

    private fun applyForceFullHeight(force: Boolean) {
        overlayActive = force
        if (force) {
            val base = if (contentHeightPx > 0) resolveContentHeight(contentHeightPx) else if (minHeight > 0) minHeight else dpToPx(LOADING_SKELETON_HEIGHT_DP)
            val maxHeight = (resources.displayMetrics.heightPixels * 0.8).roundToInt()
            val frozen = min(base, maxHeight)
            overlayFrozenHeightPx = frozen
            applyHeightPx(frozen)
        } else {
            overlayFrozenHeightPx = null
            applyHeightPx(if (contentHeightPx > 0) contentHeightPx else if (minHeight > 0) minHeight else dpToPx(LOADING_SKELETON_HEIGHT_DP))
        }
        onOverlayOpenChange?.invoke(force)
    }

    private fun applyHeightPx(px: Int) {
        val params = layoutParams ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px)
        params.height = px
        layoutParams = params
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()
}
