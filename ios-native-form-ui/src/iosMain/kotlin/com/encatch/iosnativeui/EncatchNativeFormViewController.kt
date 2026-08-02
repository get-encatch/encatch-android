@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.encatch.iosnativeui

import com.encatch.core.Encatch
import com.encatch.core.FormWebViewBridge
import com.encatch.core.ShowFormPayload
import com.encatch.core.extractShareableMode
import com.encatch.core.extractThemeJsonForMode
import com.encatch.core.getAnimationConfig
import com.encatch.core.getBackgroundColor
import com.encatch.core.getBorderRadii
import com.encatch.core.getPositionLayout
import com.encatch.core.normalizePosition
import com.encatch.core.parseCssColorToArgb
import com.encatch.core.resolveActiveMode
import com.encatch.core.resolveCornerRadiusDp
import com.encatch.core.resolveCornersFromFormConfig
import com.encatch.core.resolveDarkOverlayFromFormConfig
import com.encatch.core.resolveInAppMaxWidthDp
import com.encatch.core.resolveInAppSizeFromFormConfig
import com.encatch.core.resolveMaxDialogHeightFraction
import com.encatch.core.resolveModalOverlayBackgroundColor
import com.encatch.core.resolveSystemColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.cinterop.useContents
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIActivityIndicatorView
import platform.UIKit.UIColor
import platform.UIKit.UITraitCollection
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.UIUserInterfaceStyle
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation

/**
 * Native Kotlin/Native port of `swift/`'s `EncatchFormViewController` — modal overlay hosting
 * [EncatchNativeWebView], mirroring `:android`'s `EncatchFormDialog`: position/size/corners/
 * background/overlay resolved from the form's `appearanceProperties`, height capped at
 * `maxDialogHeightPercentInApp` (default 80%) of the screen. Live light/dark-mode reactivity
 * comes from `traitCollectionDidChange`, same as the Swift version.
 *
 * **Known limitation (unresolved)**: the presentation/event pipeline is fully working — verified
 * via device logs that `ShowForm` events arrive with the correct payload, a presenter is found,
 * appearance/position/size resolve correctly, and the window is created and made key. But the
 * *visual* result is broken: only plain, unstyled, narrow-wrapped text from the hosted page
 * renders (no button, no background, no corner radius, no overlay dim) — the WKWebView's content
 * doesn't composite correctly. This exact broken rendering was reproduced identically across four
 * different presentation strategies (subview of the existing window, `presentViewController`, a
 * separate high-`windowLevel` `UIWindow`, two different screen-size sources for the sizing calc)
 * and in two completely different host apps (`:compose-sample`, which uses Compose Multiplatform's
 * Metal-backed rendering, and `:kmp-sample`, which is plain UIKit with no Compose at all) —
 * ruling out both "which presentation API" and "Compose's Metal surface" as the cause. Most likely
 * a WKWebView-content-process compositing issue specific to a `WKWebView` created and added to a
 * *freshly constructed* `UIWindow`/view hierarchy in this iOS Simulator version. Inline forms
 * ([EncatchNativeInlineFormView]) don't hit this — they're added to an *existing*, already-laid-out
 * view hierarchy instead of a fresh window, and render correctly. Not resolved as of this writing;
 * next person picking this up should try: a real device (not just Simulator) to rule out a
 * Simulator-specific bug, or loading the WKWebView into a window that already existed at app
 * launch instead of one created fresh at presentation time.
 */
internal class EncatchNativeFormViewController : UIViewController(nibName = null, bundle = null) {

    private val webView = EncatchNativeWebView()
    private val popupShell = UIView()
    private val overlayView = UIView()
    private val skeleton = UIActivityIndicatorView()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webViewInstanceKey = 0

    private var heightConstraint: NSLayoutConstraint? = null
    private var widthConstraint: NSLayoutConstraint? = null
    private var positionConstraints: List<NSLayoutConstraint> = emptyList()
    private var maxDialogHeight: Double = Double.MAX_VALUE
    private var contentHeight: Double = 0.0
    private var isFullHeightOverlay = false
    private var isFullCenter = false
    private var currentPosition = "middle-center"
    private var currentPayload: ShowFormPayload? = null

    private val bridge: FormWebViewBridge by lazy {
        FormWebViewBridge(
            scope = scope,
            presentation = "modal",
            onClose = { immediate -> close(immediate) },
            onHeightChange = { height -> applyHeight(height.toDouble()) },
            onForceFullHeight = { force -> applyForceFullHeight(force) },
            onReady = { skeleton.stopAnimating() },
            sendToWebView = { message -> webView.sendToWebView(message) },
            redirectOpener = { _ -> Unit },
            openExternal = { _ -> Unit },
        )
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.clearColor
        overlayView.backgroundColor = UIColor.clearColor
        overlayView.setTranslatesAutoresizingMaskIntoConstraints(false)
        view.addSubview(overlayView)
        NSLayoutConstraint.activateConstraints(
            listOf(
                overlayView.topAnchor.constraintEqualToAnchor(view.topAnchor),
                overlayView.bottomAnchor.constraintEqualToAnchor(view.bottomAnchor),
                overlayView.leadingAnchor.constraintEqualToAnchor(view.leadingAnchor),
                overlayView.trailingAnchor.constraintEqualToAnchor(view.trailingAnchor),
            ),
        )

        popupShell.clipsToBounds = true
        popupShell.setTranslatesAutoresizingMaskIntoConstraints(false)
        view.addSubview(popupShell)

        webView.bridge = bridge
        webView.setTranslatesAutoresizingMaskIntoConstraints(false)
        popupShell.addSubview(webView)
        NSLayoutConstraint.activateConstraints(
            listOf(
                webView.topAnchor.constraintEqualToAnchor(popupShell.topAnchor),
                webView.bottomAnchor.constraintEqualToAnchor(popupShell.bottomAnchor),
                webView.leadingAnchor.constraintEqualToAnchor(popupShell.leadingAnchor),
                webView.trailingAnchor.constraintEqualToAnchor(popupShell.trailingAnchor),
            ),
        )

        skeleton.setTranslatesAutoresizingMaskIntoConstraints(false)
        popupShell.addSubview(skeleton)
        NSLayoutConstraint.activateConstraints(
            listOf(
                skeleton.centerXAnchor.constraintEqualToAnchor(popupShell.centerXAnchor),
                skeleton.centerYAnchor.constraintEqualToAnchor(popupShell.centerYAnchor),
            ),
        )
    }

    private var overlayWindow: platform.UIKit.UIWindow? = null

    /**
     * Presented in a genuinely separate `UIWindow` at a high `windowLevel`, not as a subview of
     * the existing window and not via `presentViewController`. Both of those were reliably a
     * no-op on top of a Compose Multiplatform-hosted screen — every callback fired correctly
     * (`viewDidLoad`, presentation completion, `onReady`, `onHeightChange` with real non-zero
     * values, the view landing in the window's subview list with the right frame) but nothing
     * ever became visible. Compose Multiplatform's iOS target renders through its own Metal
     * surface for the whole window, which apparently sits above ordinary UIKit subview
     * compositing regardless of normal z-order within that same window. A second OS-level window
     * is a different compositing layer entirely, which is the standard iOS technique for
     * guaranteed-on-top overlays (system alerts use the same mechanism) — this reliably renders
     * above the Metal surface where being "just another subview" of the same window did not.
     */
    fun present(payload: ShowFormPayload, from: UIViewController) {
        val scene = from.viewIfLoaded?.window?.windowScene
            ?: topmostViewController()?.viewIfLoaded?.window?.windowScene
            ?: return
        val window = platform.UIKit.UIWindow(windowScene = scene)
        window.rootViewController = this
        window.windowLevel = platform.UIKit.UIWindowLevelAlert + 1.0
        window.backgroundColor = UIColor.clearColor
        overlayWindow = window

        // `UIScreen.mainScreen.bounds` is unreliable here — it was returning a near-zero size in
        // this scene-based multi-window setup, which silently floored every size calculation
        // downstream (a 100x100 shell, `max(screenWidthDp, 100)`'s floor value) with no error,
        // just a suspiciously tiny modal. `window.bounds` right after construction (before it's
        // key/laid out) turned out to be just as unreliable — the scene's own `screen.bounds` is
        // populated unconditionally regardless of any window's layout state.
        val screenSize = scene.screen.bounds.useContents { size }

        webViewInstanceKey += 1
        currentPayload = payload
        applyAppearance(payload, screenSize.width, screenSize.height)
        skeleton.startAnimating()
        bridge.setFormPayload(payload)
        Encatch.setFormVisible(true)

        val url = buildInlineFormWebViewUrl(
            webHost = Encatch.webHost,
            formId = payload.formId,
            instanceKey = webViewInstanceKey,
            debugMode = Encatch.debugMode,
            presentation = "modal",
        )
        webView.loadFormUrl(url)

        window.makeKeyAndVisible()
        runEntranceAnimation()
    }

    override fun traitCollectionDidChange(previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        val payload = currentPayload ?: return
        applyThemeColors(payload, currentPosition)
    }

    private fun applyAppearance(payload: ShowFormPayload, screenWidth: Double, screenHeight: Double): String {
        val screenWidthDp = screenWidth.toInt()
        val appearanceProperties = payload.formConfig.appearanceProperties

        val rawPosition = com.encatch.core.resolveSelectedPositionFromFormConfig(appearanceProperties)
        val position = normalizePosition(rawPosition, screenWidthDp)
        isFullCenter = position == "full-center"
        currentPosition = position

        val size = resolveInAppSizeFromFormConfig(appearanceProperties)
        val maxWidthDp = resolveInAppMaxWidthDp(size, position, screenWidthDp, 0)

        val maxHeightFraction = resolveMaxDialogHeightFraction(appearanceProperties)
        maxDialogHeight = if (isFullCenter) screenHeight else maxOf(screenHeight * maxHeightFraction, 100.0)
        contentHeight = 0.0
        isFullHeightOverlay = false

        applyPositionConstraints(position, maxWidthDp.toDouble())
        applyHeight(0.0)

        return applyThemeColors(payload, position)
    }

    private fun applyPositionConstraints(position: String, maxWidthDp: Double) {
        NSLayoutConstraint.deactivateConstraints(positionConstraints)
        positionConstraints = emptyList()
        widthConstraint?.active = false

        val safeArea = view.safeAreaLayoutGuide
        positionConstraints = if (isFullCenter) {
            listOf(
                popupShell.topAnchor.constraintEqualToAnchor(view.topAnchor),
                popupShell.bottomAnchor.constraintEqualToAnchor(view.bottomAnchor),
                popupShell.leadingAnchor.constraintEqualToAnchor(view.leadingAnchor),
                popupShell.trailingAnchor.constraintEqualToAnchor(view.trailingAnchor),
            )
        } else {
            val alignment = getPositionLayout(position)
            val constraints = mutableListOf<NSLayoutConstraint>()
            when (alignment.vertical) {
                com.encatch.core.VerticalAnchor.TOP -> constraints.add(popupShell.topAnchor.constraintEqualToAnchor(safeArea.topAnchor, constant = 16.0))
                com.encatch.core.VerticalAnchor.BOTTOM -> constraints.add(popupShell.bottomAnchor.constraintEqualToAnchor(safeArea.bottomAnchor, constant = -16.0))
                else -> constraints.add(popupShell.centerYAnchor.constraintEqualToAnchor(safeArea.centerYAnchor))
            }
            when (alignment.horizontal) {
                com.encatch.core.HorizontalAnchor.START -> constraints.add(popupShell.leadingAnchor.constraintEqualToAnchor(safeArea.leadingAnchor, constant = 16.0))
                com.encatch.core.HorizontalAnchor.END -> constraints.add(popupShell.trailingAnchor.constraintEqualToAnchor(safeArea.trailingAnchor, constant = -16.0))
                else -> constraints.add(popupShell.centerXAnchor.constraintEqualToAnchor(safeArea.centerXAnchor))
            }
            val width = popupShell.widthAnchor.constraintEqualToConstant(maxWidthDp)
            widthConstraint = width
            constraints.add(width)
            constraints
        }
        NSLayoutConstraint.activateConstraints(positionConstraints)
    }

    private fun applyThemeColors(payload: ShowFormPayload, position: String): String {
        val appearanceProperties = payload.formConfig.appearanceProperties
        val corners = resolveCornersFromFormConfig(appearanceProperties)
        val darkOverlay = resolveDarkOverlayFromFormConfig(appearanceProperties)

        val systemDark = traitCollection.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark
        val systemScheme = resolveSystemColorScheme(systemDark)
        val shareableMode = extractShareableMode(appearanceProperties)
        val activeMode = resolveActiveMode(payload.theme?.wireValue ?: shareableMode, systemScheme)

        val themeJson = extractThemeJsonForMode(appearanceProperties, activeMode)
        val fallbackBg = if (activeMode == "dark") "#1a1a1a" else "#ffffff"
        val backgroundColorStr = getBackgroundColor(themeJson, fallbackBg)
        val fallbackArgb = if (activeMode == "dark") 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
        val backgroundArgb = parseCssColorToArgb(backgroundColorStr, fallbackArgb)

        val overlayColorStr = resolveModalOverlayBackgroundColor(appearanceProperties, activeMode, darkOverlay)
        val overlayArgb = parseCssColorToArgb(overlayColorStr, 0)

        val radii = getBorderRadii(position, corners)
        popupShell.backgroundColor = uiColorFromArgb(backgroundArgb)
        applyCorners(radii, corners)
        overlayView.backgroundColor = uiColorFromArgb(overlayArgb)
        webView.backgroundColor = UIColor.clearColor

        return activeMode
    }

    private fun applyCorners(radii: com.encatch.core.PopupBorderRadii, corners: com.encatch.core.CornerStyle) {
        val radiusDp = resolveCornerRadiusDp(corners).toDouble()
        var maskedCorners = 0uL
        if (radii.topLeftDp > 0) maskedCorners = maskedCorners or platform.QuartzCore.kCALayerMinXMinYCorner
        if (radii.topRightDp > 0) maskedCorners = maskedCorners or platform.QuartzCore.kCALayerMaxXMinYCorner
        if (radii.bottomLeftDp > 0) maskedCorners = maskedCorners or platform.QuartzCore.kCALayerMinXMaxYCorner
        if (radii.bottomRightDp > 0) maskedCorners = maskedCorners or platform.QuartzCore.kCALayerMaxXMaxYCorner
        popupShell.layer.cornerRadius = radiusDp
        popupShell.layer.maskedCorners = maskedCorners
    }

    private fun runEntranceAnimation() {
        val config = getAnimationConfig(currentPosition)
        popupShell.alpha = 0.0
        popupShell.transform = if (config.type == "slide") {
            CGAffineTransformMakeTranslation(config.txFractionPercent.toDouble(), config.tyFractionPercent.toDouble())
        } else {
            CGAffineTransformMakeScale(0.8, 0.8)
        }
        UIView.animateWithDuration(
            duration = 0.3,
            delay = 0.0,
            options = platform.UIKit.UIViewAnimationOptionCurveEaseOut,
            animations = {
                popupShell.alpha = 1.0
                popupShell.transform = CGAffineTransformMakeScale(1.0, 1.0)
            },
            completion = null,
        )
    }

    private fun runExitAnimation(onDone: () -> Unit) {
        val config = getAnimationConfig(currentPosition)
        UIView.animateWithDuration(
            duration = 0.25,
            animations = {
                popupShell.alpha = 0.0
                popupShell.transform = if (config.type == "slide") {
                    CGAffineTransformMakeTranslation(config.txFractionPercent.toDouble(), config.tyFractionPercent.toDouble())
                } else {
                    CGAffineTransformMakeScale(0.8, 0.8)
                }
            },
            completion = { onDone() },
        )
    }

    private fun applyHeight(height: Double) {
        contentHeight = height
        val target = if (isFullHeightOverlay || isFullCenter) maxDialogHeight else minOf(height, maxDialogHeight)
        heightConstraint?.active = false
        heightConstraint = popupShell.heightAnchor.constraintEqualToConstant(maxOf(target, 0.0))
        heightConstraint?.active = true
        view.layoutIfNeeded()
    }

    private fun applyForceFullHeight(force: Boolean) {
        isFullHeightOverlay = force
        applyHeight(contentHeight)
    }

    private fun close(immediate: Boolean) {
        Encatch.setFormVisible(false)
        if (immediate) {
            removeOverlay()
        } else {
            runExitAnimation { removeOverlay() }
        }
    }

    fun removeOverlay() {
        bridge.setFormPayload(null)
        currentPayload = null
        overlayWindow?.setHidden(true)
        overlayWindow?.rootViewController = null
        overlayWindow = null
    }
}
