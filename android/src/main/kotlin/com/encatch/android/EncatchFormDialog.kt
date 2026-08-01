package com.encatch.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.encatch.core.Encatch
import com.encatch.core.SDKMessage
import com.encatch.core.ShowFormPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Modal overlay hosting [EncatchWebView], mirroring `EncatchWebView.tsx`'s Modal presentation:
 * position/size/corners/background/overlay resolved from the form's `appearanceProperties`,
 * height capped at `maxDialogHeightPercentInApp` (default 80%) of the screen, IME-aware,
 * dismissible via the bridge's onClose.
 */
class EncatchFormDialog(context: Context) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val webView = EncatchWebView(context)
    private val overlayRoot = FrameLayout(context)
    private val popupShell = FrameLayout(context)
    private var webViewInstanceKey = 0
    private var isFullHeightOverlay = false
    private var isFullCenter = false
    private var maxDialogHeightPx = Int.MAX_VALUE
    private var contentHeightPx = 0
    private var currentPosition = "middle-center"
    private var currentPayload: ShowFormPayload? = null
    private var currentAppearanceProperties: JsonObject? = null
    private var currentCorners = CornerStyle.SOFT
    private var currentDarkOverlay = false
    private var lastSystemScheme: String? = null
    private var skeleton: FormWebViewSkeleton? = null
    private var closeAnimator: Animator? = null
    private val redirectBrowser = RedirectBrowser(context)

    /**
     * Observes live system light/dark-mode changes while a form is open, mirroring RN's
     * `Appearance.addChangeListener` — re-resolves theme-derived colors without a full
     * re-layout. Registered on the application context so it fires regardless of whether the
     * host Activity declares `android:configChanges="uiMode"`.
     */
    private val systemThemeCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val scheme = resolveSystemColorScheme(newConfig.uiMode)
            if (scheme == lastSystemScheme) return
            lastSystemScheme = scheme
            val payload = currentPayload ?: return
            if (!isShowing) return
            val activeMode = applyThemeColors(payload)
            if (skeleton != null) showSkeleton(activeMode)
        }

        @Suppress("DEPRECATION")
        override fun onLowMemory() = Unit
        override fun onTrimMemory(level: Int) = Unit
    }

    private val bridge = FormWebViewBridge(
        scope = scope,
        presentation = "modal",
        onClose = { immediate -> close(immediate) },
        onHeightChange = { height -> applyHeight(height) },
        onForceFullHeight = { force -> isFullHeightOverlay = force; applyHeight(contentHeightPx) },
        onReady = { skeleton?.let { popupShell.removeView(it) }; skeleton = null },
        sendToWebView = { message: SDKMessage -> webView.sendToWebView(message) },
        redirectOpener = redirectBrowser,
        openExternal = { url -> redirectBrowser.openExternal(url) },
    )

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        webView.bridge = bridge
        webView.setBackgroundColor(Color.TRANSPARENT)
        popupShell.clipToOutline = true
        popupShell.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        overlayRoot.addView(popupShell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(overlayRoot)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }

        // Manually resolves real status-bar/nav-bar/IME insets and pads the overlay so the
        // popup never sits under the status bar or gesture-nav bar, and lifts above the
        // keyboard — mirrors what `form-modal-safe-area.ts` works around RN's Modal for,
        // but native Android can just ask WindowInsetsCompat directly.
        ViewCompat.setOnApplyWindowInsetsListener(overlayRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                if (isFullCenter) 0 else systemBars.left,
                systemBars.top,
                if (isFullCenter) 0 else systemBars.right,
                max(systemBars.bottom, ime.bottom),
            )
            insets
        }

        lastSystemScheme = resolveSystemColorScheme(context.resources.configuration.uiMode)
        context.applicationContext.registerComponentCallbacks(systemThemeCallbacks)

        setOnDismissListener {
            Encatch.setFormVisible(false)
        }
    }

    fun present(payload: ShowFormPayload) {
        closeAnimator?.cancel()
        webViewInstanceKey += 1
        currentPayload = payload
        val (activeMode, darkOverlay) = applyAppearance(payload)
        showSkeleton(activeMode)
        applyBlurBehind(enabled = !darkOverlay)
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
        popupShell.post { runEntranceAnimation() }
    }

    private fun showSkeleton(activeMode: String) {
        skeleton?.let { popupShell.removeView(it) }
        val newSkeleton = FormWebViewSkeleton(context, activeMode)
        skeleton = newSkeleton
        popupShell.addView(newSkeleton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun applyBlurBehind(enabled: Boolean) {
        val win = window ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (enabled) {
            win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            win.attributes = win.attributes.apply { blurBehindRadius = dpToPxInt(24, context.resources.displayMetrics.density) }
        } else {
            win.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }

    private fun runEntranceAnimation() {
        val cfg = getAnimationConfig(currentPosition)
        val density = context.resources.displayMetrics.density
        popupShell.alpha = 0f
        if (cfg.type == "slide") {
            popupShell.translationX = cfg.txFractionPercent * density
            popupShell.translationY = cfg.tyFractionPercent * density
            popupShell.scaleX = 1f
            popupShell.scaleY = 1f
        } else {
            popupShell.translationX = 0f
            popupShell.translationY = 0f
            popupShell.scaleX = 0.8f
            popupShell.scaleY = 0.8f
        }

        val fade = ObjectAnimator.ofFloat(popupShell, "alpha", 0f, 1f).setDuration(300)
        val settle = if (cfg.type == "slide") {
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(popupShell, "translationX", popupShell.translationX, 0f),
                    ObjectAnimator.ofFloat(popupShell, "translationY", popupShell.translationY, 0f),
                )
            }
        } else {
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(popupShell, "scaleX", 0.8f, 1f),
                    ObjectAnimator.ofFloat(popupShell, "scaleY", 0.8f, 1f),
                )
            }
        }
        settle.duration = 350
        settle.interpolator = OvershootInterpolator(1.2f)

        AnimatorSet().apply {
            playTogether(fade, settle)
            start()
        }
    }

    private fun runExitAnimation(onDone: () -> Unit) {
        val cfg = getAnimationConfig(currentPosition)
        val density = context.resources.displayMetrics.density
        val fade = ObjectAnimator.ofFloat(popupShell, "alpha", popupShell.alpha, 0f).setDuration(250)
        val settle = if (cfg.type == "slide") {
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(popupShell, "translationX", popupShell.translationX, cfg.txFractionPercent * density),
                    ObjectAnimator.ofFloat(popupShell, "translationY", popupShell.translationY, cfg.tyFractionPercent * density),
                )
            }
        } else {
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(popupShell, "scaleX", popupShell.scaleX, 0.8f),
                    ObjectAnimator.ofFloat(popupShell, "scaleY", popupShell.scaleY, 0.8f),
                )
            }
        }
        settle.duration = 250

        val set = AnimatorSet().apply { playTogether(fade, settle) }
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (closeAnimator === set) onDone()
            }
        })
        closeAnimator = set
        set.start()
    }

    private fun applyAppearance(payload: ShowFormPayload): Pair<String, Boolean> {
        val density = context.resources.displayMetrics.density
        val screenWidthDp = (context.resources.displayMetrics.widthPixels / density).roundToInt()
        val screenHeightPx = context.resources.displayMetrics.heightPixels

        val appearanceProperties = payload.formConfig.appearanceProperties as? JsonObject
        val rawPosition = resolveSelectedPositionFromFormConfig(appearanceProperties)
        val position = normalizePosition(rawPosition, screenWidthDp)
        isFullCenter = position == "full-center"

        val corners = resolveCornersFromFormConfig(appearanceProperties)
        val size = resolveInAppSizeFromFormConfig(appearanceProperties)
        val darkOverlay = resolveDarkOverlayFromFormConfig(appearanceProperties)

        currentAppearanceProperties = appearanceProperties
        currentCorners = corners
        currentDarkOverlay = darkOverlay
        currentPosition = position
        popupShell.elevation = if (isFullCenter) 0f else dpToPxInt(20, density).toFloat()
        val activeMode = applyThemeColors(payload)

        val gravity = if (isFullCenter) Gravity.FILL else getPositionLayout(position).gravity
        (popupShell.layoutParams as FrameLayout.LayoutParams).gravity = gravity
        popupShell.layoutParams = (popupShell.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (isFullCenter) ViewGroup.LayoutParams.MATCH_PARENT else dpToPxInt(resolveInAppMaxWidthDp(size, position, screenWidthDp), density)
        }

        val maxHeightFraction = resolveMaxDialogHeightFraction(appearanceProperties)
        maxDialogHeightPx = if (isFullCenter) screenHeightPx else max((screenHeightPx * maxHeightFraction).roundToInt(), dpToPxInt(100, density))
        contentHeightPx = 0
        isFullHeightOverlay = false

        return activeMode to darkOverlay
    }

    /**
     * Resolves and applies theme-derived colors only (background/overlay), without touching
     * position/size/height layout state. Called on initial [applyAppearance] and again whenever
     * [systemThemeCallbacks] observes a live system light/dark-mode change while the form is open.
     */
    private fun applyThemeColors(payload: ShowFormPayload, position: String = currentPosition): String {
        val density = context.resources.displayMetrics.density
        val appearanceProperties = currentAppearanceProperties
        val corners = currentCorners
        val darkOverlay = currentDarkOverlay

        val systemScheme = resolveSystemColorScheme(context.resources.configuration.uiMode)
        val shareableMode = appearanceProperties?.get("featureSettings")?.let { it as? JsonObject }
            ?.get("shareableMode")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val activeMode = resolveActiveMode(payload.theme?.wireValue ?: shareableMode, systemScheme)

        val themeJson = appearanceProperties?.get("themes")?.let { it as? JsonObject }
            ?.get(activeMode)?.let { it as? JsonObject }
            ?.get("theme")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val fallbackBg = if (activeMode == "dark") "#1a1a1a" else "#ffffff"
        val backgroundColorStr = getBackgroundColor(themeJson, fallbackBg)
        val backgroundArgb = parseCssColorToArgb(backgroundColorStr, if (activeMode == "dark") Color.parseColor(fallbackBg) else Color.WHITE)

        val overlayColorStr = resolveModalOverlayBackgroundColor(appearanceProperties, activeMode, darkOverlay)
        val overlayArgb = parseCssColorToArgb(overlayColorStr, Color.TRANSPARENT)

        val radii = getBorderRadii(position, corners)
        popupShell.background = GradientDrawable().apply {
            setColor(backgroundArgb)
            cornerRadii = floatArrayOf(
                dpToPxInt(radii.topLeftDp, density).toFloat(), dpToPxInt(radii.topLeftDp, density).toFloat(),
                dpToPxInt(radii.topRightDp, density).toFloat(), dpToPxInt(radii.topRightDp, density).toFloat(),
                dpToPxInt(radii.bottomRightDp, density).toFloat(), dpToPxInt(radii.bottomRightDp, density).toFloat(),
                dpToPxInt(radii.bottomLeftDp, density).toFloat(), dpToPxInt(radii.bottomLeftDp, density).toFloat(),
            )
        }
        overlayRoot.setBackgroundColor(overlayArgb)
        webView.setBackgroundColor(Color.TRANSPARENT)

        return activeMode
    }

    private fun applyHeight(heightPx: Int) {
        contentHeightPx = heightPx
        val target = if (isFullHeightOverlay || isFullCenter) maxDialogHeightPx else minOf(heightPx, maxDialogHeightPx)
        webView.updateLayoutParams<FrameLayout.LayoutParams> { height = target }
    }

    private fun close(immediate: Boolean) {
        Encatch.setFormVisible(false)
        if (!isShowing) return
        if (immediate) {
            closeAnimator?.cancel()
            dismiss()
        } else {
            runExitAnimation { if (isShowing) dismiss() }
        }
    }

    override fun dismiss() {
        super.dismiss()
        bridge.setFormPayload(null)
        currentPayload = null
        skeleton?.let { popupShell.removeView(it) }
        skeleton = null
        closeAnimator = null
        applyBlurBehind(enabled = false)
        context.applicationContext.unregisterComponentCallbacks(systemThemeCallbacks)
    }
}
