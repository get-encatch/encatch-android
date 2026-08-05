package com.encatch.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
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
import com.encatch.core.CornerStyle
import com.encatch.core.Encatch
import com.encatch.core.FormWebViewBridge
import com.encatch.core.SDKMessage
import com.encatch.core.ShowFormPayload
import com.encatch.core.dpToPxInt
import com.encatch.core.getAnimationConfig
import com.encatch.core.getBackgroundColor
import com.encatch.core.getBorderRadii
import com.encatch.core.getPositionLayout
import com.encatch.core.normalizePosition
import com.encatch.core.parseCssColorToArgb
import com.encatch.core.resolveActiveMode
import com.encatch.core.resolveCornersFromFormConfig
import com.encatch.core.resolveCloseButtonFromFormConfig
import com.encatch.core.resolveDarkOverlayFromFormConfig
import com.encatch.core.resolveInAppMaxWidthDp
import com.encatch.core.resolveInAppSizeFromFormConfig
import com.encatch.core.resolveMaxDialogHeightFraction
import com.encatch.core.resolveModalOverlayBackgroundColor
import com.encatch.core.resolveSelectedPositionFromFormConfig
import com.encatch.core.resolveSystemColorScheme
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
    // Space left for the popup between the top inset and the keyboard while the IME is up;
    // Int.MAX_VALUE when the keyboard is hidden.
    private var availableHeightAboveImePx = Int.MAX_VALUE
    // True from present() until the bridge's form:ready — holds a placeholder shell height so
    // the shimmer skeleton is visible during load (the WebView starts at 0 height until the
    // first form:height message). Same constant as EncatchInlineFormView's skeleton height.
    private var isLoadingForm = false
    private var currentPosition = "middle-center"
    private var currentPayload: ShowFormPayload? = null
    private var currentAppearanceProperties: JsonObject? = null
    private var currentCorners = CornerStyle.SOFT
    private var currentDarkOverlay = false
    private var lastSystemScheme: String? = null
    private var skeleton: FormWebViewSkeleton? = null
    private var loadingCloseButton: android.widget.ImageButton? = null
    // When the form's closeButton setting is enabled, a tap on the overlay area outside the
    // card also closes the modal (per-present, from the show-form response).
    private var closeOnOverlayTap = false
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
            val scheme = resolveSystemColorScheme(isConfigurationDark(newConfig.uiMode))
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
        // form:resize heights arrive in CSS px (== dp — the WebView renders at density× scale),
        // so they must be converted to physical px before driving pixel-space LayoutParams.
        // ios-native applies them unconverted because iOS points ARE CSS px; skipping this on
        // Android shrinks the modal to 1/density of the intended height (content clipped).
        onHeightChange = { height -> applyHeight(dpToPxInt(height, context.resources.displayMetrics.density), animated = true) },
        onForceFullHeight = { force -> isFullHeightOverlay = force; applyHeight(contentHeightPx) },
        onReady = {
            // onPageFinished's 300ms fallback fires this even when the page HTML loaded but
            // the form JS never booted. A "ready" without any reported height means nothing
            // is actually rendered — keep the skeleton and watchdog until real content
            // arrives (finishLoading runs from applyHeight on the first real height).
            if (contentHeightPx > 0) finishLoading()
        },
        sendToWebView = { message: SDKMessage -> webView.sendToWebView(message) },
        redirectOpener = redirectBrowser,
        openExternal = { url -> redirectBrowser.openExternal(url) },
    )

    // Auto-closes the modal if no real form content ever arrives (silent load hang / dead form
    // JS) — the close button lives inside the web page, so without an escape hatch the user is
    // trapped behind the overlay until app kill.
    private val readyWatchdog = Runnable {
        android.util.Log.w("Encatch", "closing modal form: no form content within 20s")
        close(immediate = true)
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        webView.bridge = bridge
        webView.onUnrecoverableFailure = { reason ->
            android.util.Log.w("Encatch", "closing modal form: $reason")
            close(immediate = true)
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        // Overlay-tap close: overlayRoot only receives clicks that land outside popupShell —
        // the card is clickable itself so its taps never bubble up to the root.
        overlayRoot.setOnClickListener { if (closeOnOverlayTap) close(immediate = false) }
        popupShell.isClickable = true
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
            // Re-cap the popup height to what actually fits above the keyboard (mirrors the RN
            // SDK's `usableHeight` keyboard math): the popup keeps its full-screen target size
            // while it still fits, and shrinks only when it doesn't. Without this a tall popup
            // (e.g. 80% of screen) keeps its exact-height LayoutParams when the IME halves the
            // available space, and FrameLayout clips its lower part behind the keyboard.
            val newAvailable = if (ime.bottom > 0 && view.height > 0) {
                max(view.height - systemBars.top - max(systemBars.bottom, ime.bottom), dpToPxInt(100, context.resources.displayMetrics.density))
            } else {
                Int.MAX_VALUE
            }
            if (newAvailable != availableHeightAboveImePx) {
                availableHeightAboveImePx = newAvailable
                if (contentHeightPx > 0 || isFullHeightOverlay || isFullCenter) applyHeight(contentHeightPx)
            }
            insets
        }

        lastSystemScheme = resolveSystemColorScheme(isConfigurationDark(context.resources.configuration.uiMode))
        context.applicationContext.registerComponentCallbacks(systemThemeCallbacks)

        setOnDismissListener {
            Encatch.setFormVisible(false)
        }
    }

    fun present(payload: ShowFormPayload) {
        closeAnimator?.cancel()
        webViewInstanceKey += 1
        currentPayload = payload
        isLoadingForm = true
        val (activeMode, darkOverlay) = applyAppearance(payload)
        showSkeleton(activeMode)
        applyHeight(0)
        popupShell.removeCallbacks(readyWatchdog)
        popupShell.postDelayed(readyWatchdog, 20_000)
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
        showLoadingCloseButton(activeMode)
    }

    /// Native fail-safe close (✕) shown while loading: appears 1s after present so a slow or
    /// dead form page can always be dismissed by hand, independent of the web page's own close
    /// button; removed once real form content arrives (see finishLoading).
    private fun showLoadingCloseButton(activeMode: String) {
        loadingCloseButton?.let { popupShell.removeView(it) }
        val density = context.resources.displayMetrics.density
        val ink = if (activeMode == "dark") Color.WHITE else Color.BLACK
        val size = dpToPxInt(28, density)
        val button = android.widget.ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = android.content.res.ColorStateList.valueOf((ink and 0x00FFFFFF) or (0x99 shl 24))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val pad = dpToPxInt(6, density)
            setPadding(pad, pad, pad, pad)
            background = null
            visibility = android.view.View.GONE
            setOnClickListener { close(immediate = false) }
        }
        popupShell.addView(
            button,
            FrameLayout.LayoutParams(size, size, android.view.Gravity.TOP or android.view.Gravity.END).apply {
                topMargin = dpToPxInt(10, density)
                marginEnd = dpToPxInt(10, density)
            },
        )
        loadingCloseButton = button
        button.postDelayed({ if (isLoadingForm) button.visibility = android.view.View.VISIBLE }, 1_000)
    }

    private fun applyBlurBehind(enabled: Boolean) {
        val win = window ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (enabled) {
            win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            // Deliberately light — 24dp read as far too heavy in practice; a subtle frost keeps
            // the host UI recognizable behind the modal (matches iOS's reduced blur intensity).
            win.attributes = win.attributes.apply { blurBehindRadius = dpToPxInt(8, context.resources.displayMetrics.density) }
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
        closeOnOverlayTap = resolveCloseButtonFromFormConfig(appearanceProperties)

        currentAppearanceProperties = appearanceProperties
        currentCorners = corners
        currentDarkOverlay = darkOverlay
        currentPosition = position
        popupShell.elevation = if (isFullCenter) 0f else dpToPxInt(20, density).toFloat()
        val activeMode = applyThemeColors(payload)

        val gravity = if (isFullCenter) Gravity.FILL else getPositionLayout(position).toGravity()
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

    private fun finishLoading() {
        popupShell.removeCallbacks(readyWatchdog)
        isLoadingForm = false
        // Fade the skeleton over the rendered form, then remove it — removing immediately
        // makes the handoff feel jerky (mirrors ios-native's skeleton crossfade).
        skeleton?.let { fading ->
            fading.animate().alpha(0f).setDuration(300)
                .withEndAction { popupShell.removeView(fading) }
                .start()
        }
        skeleton = null
        // The real form has its own close button — retire the fail-safe ✕.
        loadingCloseButton?.let { popupShell.removeView(it) }
        loadingCloseButton = null
    }

    private var heightAnimator: ValueAnimator? = null

    private fun applyHeight(heightPx: Int, animated: Boolean = false) {
        contentHeightPx = heightPx
        if (heightPx > 0 && isLoadingForm) finishLoading()
        val density = context.resources.displayMetrics.density
        // While no real form:height has arrived, hold a fixed placeholder height so the shimmer
        // skeleton is visible and the shell can never collapse to an invisible 0px card behind
        // the overlay — mirrors ios-native's EncatchFormViewController.
        val effectiveHeight = if (heightPx <= 0) dpToPxInt(300, density) else heightPx
        val cap = minOf(maxDialogHeightPx, availableHeightAboveImePx)
        val target = if (isFullHeightOverlay || isFullCenter) cap else minOf(effectiveHeight, cap)

        heightAnimator?.cancel()
        val current = webView.layoutParams?.height ?: 0
        // Bridge-driven resizes (placeholder→real height, step changes) animate so the shell
        // glides instead of snapping; keyboard/inset-driven calls stay immediate.
        if (animated && current > 0 && current != target) {
            heightAnimator = ValueAnimator.ofInt(current, target).apply {
                duration = 300
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    webView.updateLayoutParams<FrameLayout.LayoutParams> { height = animator.animatedValue as Int }
                }
                start()
            }
        } else {
            webView.updateLayoutParams<FrameLayout.LayoutParams> { height = target }
        }
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
        popupShell.removeCallbacks(readyWatchdog)
        bridge.setFormPayload(null)
        currentPayload = null
        skeleton?.let { popupShell.removeView(it) }
        skeleton = null
        loadingCloseButton?.let { popupShell.removeView(it) }
        loadingCloseButton = null
        closeAnimator = null
        applyBlurBehind(enabled = false)
        context.applicationContext.unregisterComponentCallbacks(systemThemeCallbacks)
    }
}
