#if canImport(UIKit)
import UIKit

/// Modal overlay hosting `EncatchWebView`, mirroring `:android`'s `EncatchFormDialog`:
/// position/size/corners/background/overlay resolved from the form's `appearanceProperties`,
/// height capped at `maxDialogHeightPercentInApp` (default 80%) of the screen, dismissible via
/// the bridge's `onClose`. Live light/dark-mode reactivity comes for free from
/// `traitCollectionDidChange` — no manual system-appearance observer needed, unlike Android.
public final class EncatchFormViewController: UIViewController {

    private let webView = EncatchWebView()
    private let popupShell = UIView()
    /// Rounded-clipping container inside `popupShell` for the WebView/skeleton. Clipping lives
    /// here (not on the shell) so the shell can cast its drop shadow — `clipsToBounds` on the
    /// shadow-casting layer would clip the shadow away.
    private let contentClip = UIView()
    private let overlayView = UIView()
    private let backdropBlur = UIVisualEffectView(effect: nil)
    private var backdropBlurAnimator: UIViewPropertyAnimator?

    /// Fractional blur strength when darkOverlay is off — mirrors the React Native SDK's
    /// `MODAL_BACKDROP_BLUR_INTENSITY` (52/100 on iOS).
    private static let backdropBlurIntensity: CGFloat = 0.52
    private let skeleton = FormWebViewSkeletonView()

    /// Shell height while the WebView is still loading (skeleton visible, no `form:height` yet) —
    /// matches `EncatchInlineFormView`'s `LOADING_SKELETON_HEIGHT_DP`.
    private static let loadingSkeletonHeight: CGFloat = 300
    private var isLoadingForm = false

    private let redirectBrowser = RedirectBrowser()
    private var webViewInstanceKey: Int32 = 0

    private var heightConstraint: NSLayoutConstraint?
    private var widthConstraint: NSLayoutConstraint?
    private var positionConstraints: [NSLayoutConstraint] = []
    /// Shell edge constraints that ride above the keyboard: the bottom pin (`.bottom` position and
    /// full-center) and the centerY pin (`.center` position).
    private var bottomEdgeConstraint: NSLayoutConstraint?
    private var centerYConstraint: NSLayoutConstraint?
    /// Current keyboard overlap with this view, in points (0 when hidden).
    private var keyboardHeight: CGFloat = 0
    private var maxDialogHeight: CGFloat = .greatestFiniteMagnitude
    private var contentHeight: CGFloat = 0
    private var isFullHeightOverlay = false
    private var isFullCenter = false
    private var currentPosition = "middle-center"
    private var currentPayload: ShowFormPayload?

    private lazy var bridge: FormWebViewBridge = FormWebViewBridge(
        logTag: "Encatch",
        presentation: "modal",
        onClose: { [weak self] immediate in self?.close(immediate: immediate) },
        onHeightChange: { [weak self] height in self?.applyHeight(CGFloat(height)) },
        onForceFullHeight: { [weak self] force in self?.applyForceFullHeight(force) },
        onReady: { [weak self] in self?.hideSkeleton() },
        sendToWebView: { [weak self] message in self?.webView.sendToWebView(message) },
        redirectOpener: redirectBrowser,
        openExternal: { [weak self] url in self?.redirectBrowser.openExternal(url) }
    )

    public override func viewDidLoad() {
        super.viewDidLoad()
        modalPresentationStyle = .overFullScreen
        modalTransitionStyle = .crossDissolve

        view.backgroundColor = .clear
        overlayView.backgroundColor = .clear
        overlayView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(overlayView)
        NSLayoutConstraint.activate([
            overlayView.topAnchor.constraint(equalTo: view.topAnchor),
            overlayView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            overlayView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlayView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])

        // Frosted backdrop shown only when darkOverlay is off (RN's ModalBackdropBlur /
        // :android's FLAG_BLUR_BEHIND). Sits above the (then-transparent) overlay color view,
        // below the popup shell; never intercepts touches.
        backdropBlur.isUserInteractionEnabled = false
        backdropBlur.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(backdropBlur)
        NSLayoutConstraint.activate([
            backdropBlur.topAnchor.constraint(equalTo: view.topAnchor),
            backdropBlur.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            backdropBlur.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            backdropBlur.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])

        popupShell.clipsToBounds = false
        popupShell.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(popupShell)

        // Modal card elevation — matches the RN SDK's getModalPopupShadowStyle() / web-sdk's
        // `box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3)`. Opacity is toggled per position in
        // applyAppearance (full-center gets no shadow, like Android's elevation = 0 there).
        popupShell.layer.shadowColor = UIColor.black.cgColor
        popupShell.layer.shadowOffset = CGSize(width: 0, height: 20)
        popupShell.layer.shadowRadius = 60
        popupShell.layer.shadowOpacity = 0

        contentClip.clipsToBounds = true
        contentClip.translatesAutoresizingMaskIntoConstraints = false
        popupShell.addSubview(contentClip)
        NSLayoutConstraint.activate([
            contentClip.topAnchor.constraint(equalTo: popupShell.topAnchor),
            contentClip.bottomAnchor.constraint(equalTo: popupShell.bottomAnchor),
            contentClip.leadingAnchor.constraint(equalTo: popupShell.leadingAnchor),
            contentClip.trailingAnchor.constraint(equalTo: popupShell.trailingAnchor),
        ])

        webView.bridge = bridge
        webView.translatesAutoresizingMaskIntoConstraints = false
        contentClip.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: contentClip.topAnchor),
            webView.bottomAnchor.constraint(equalTo: contentClip.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: contentClip.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: contentClip.trailingAnchor),
        ])

        skeleton.translatesAutoresizingMaskIntoConstraints = false
        contentClip.addSubview(skeleton)
        NSLayoutConstraint.activate([
            skeleton.topAnchor.constraint(equalTo: contentClip.topAnchor),
            skeleton.bottomAnchor.constraint(equalTo: contentClip.bottomAnchor),
            skeleton.leadingAnchor.constraint(equalTo: contentClip.leadingAnchor),
            skeleton.trailingAnchor.constraint(equalTo: contentClip.trailingAnchor),
        ])

        NotificationCenter.default.addObserver(
            self, selector: #selector(keyboardWillChangeFrame(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification, object: nil
        )
        NotificationCenter.default.addObserver(
            self, selector: #selector(keyboardWillHide(_:)),
            name: UIResponder.keyboardWillHideNotification, object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    public func present(payload: ShowFormPayload, from presenter: UIViewController) {
        webViewInstanceKey += 1
        currentPayload = payload
        isLoadingForm = true
        let (activeMode, darkOverlay) = applyAppearance(payload: payload, presenter: presenter)
        applyBackdropBlur(enabled: !darkOverlay, activeMode: activeMode)
        skeleton.start(activeMode: activeMode)
        bridge.setFormPayload(payload)
        Encatch.shared.setFormVisible(true)

        let url = buildFormWebViewUrl(
            webHost: Encatch.shared.webHost,
            formId: payload.formId,
            instanceKey: webViewInstanceKey,
            debugMode: Encatch.shared.debugMode,
            presentation: "modal"
        )
        webView.loadFormUrl(url)

        presenter.present(self, animated: false) { [weak self] in
            self?.runEntranceAnimation(activeMode: activeMode)
        }
    }

    public override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        guard traitCollection.hasDifferentColorAppearance(comparedTo: previousTraitCollection),
              let payload = currentPayload else { return }
        applyThemeColors(payload: payload, position: currentPosition)
    }

    /// Sizes/positions the popup shell against [presenter]'s own window/scene screen bounds
    /// rather than `UIScreen.main.bounds` — `UIScreen.main` is unreliable (returns a near-zero or
    /// otherwise wrong size) for apps that host their own separate window/scene hierarchy (e.g.
    /// Compose Multiplatform's or a KMP host app's root view controller), even though it works
    /// fine for a plain single-window UIKit app. Falls back to `UIScreen.main.bounds` only if the
    /// presenter genuinely has no window yet (shouldn't happen in practice — `present(from:)` is
    /// always called with an already-key-windowed view controller).
    private func applyAppearance(payload: ShowFormPayload, presenter: UIViewController) -> (String, Bool) {
        let screenSize = presenter.view.window?.windowScene?.screen.bounds.size ?? UIScreen.main.bounds.size
        let screenWidthDp = Int(screenSize.width)
        let appearanceProperties = payload.formConfig.appearanceProperties

        let rawPosition = resolveSelectedPositionFromFormConfig(appearanceProperties)
        let position = normalizePosition(rawPosition, screenWidthDp: screenWidthDp)
        isFullCenter = position == "full-center"
        currentPosition = position

        let size = resolveInAppSizeFromFormConfig(appearanceProperties)
        let maxWidthDp = resolveInAppMaxWidthDp(size: size, position: position, screenWidthDp: screenWidthDp, horizontalInsetDp: 0)

        let maxHeightFraction = CGFloat(resolveMaxDialogHeightFraction(appearanceProperties))
        maxDialogHeight = isFullCenter ? screenSize.height : max(screenSize.height * maxHeightFraction, 100)
        contentHeight = 0
        isFullHeightOverlay = false

        applyPositionConstraints(position: position, maxWidthDp: CGFloat(maxWidthDp))
        applyHeight(0)

        return applyThemeColorsReturningMode(payload: payload, position: position)
    }

    private func applyPositionConstraints(position: String, maxWidthDp: CGFloat) {
        NSLayoutConstraint.deactivate(positionConstraints)
        positionConstraints.removeAll()
        if let widthConstraint { widthConstraint.isActive = false }
        bottomEdgeConstraint = nil
        centerYConstraint = nil

        let safeArea = view.safeAreaLayoutGuide
        if isFullCenter {
            let bottom = popupShell.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -keyboardHeight)
            bottomEdgeConstraint = bottom
            positionConstraints = [
                popupShell.topAnchor.constraint(equalTo: view.topAnchor),
                bottom,
                popupShell.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                popupShell.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            ]
        } else {
            // Flush against the safe area on every edge — no added margin. Mirrors :android's
            // EncatchFormDialog, which positions popupShell via Gravity inside a FrameLayout
            // padded only by the real system-bar insets (see EncatchFormDialog.kt's
            // setOnApplyWindowInsetsListener), with no extra manual inset on top of that. This
            // module previously added an extra 16pt constant on every side, which visibly
            // detached bottom/top-positioned cards from the actual screen edge — most obvious
            // for `position: "bottom"` when the form's overlay is transparent, since the gap
            // then reveals the host app's own UI underneath instead of a dimmed background.
            let alignment = getPositionLayout(position)
            var constraints: [NSLayoutConstraint] = []
            switch alignment.vertical {
            case .top: constraints.append(popupShell.topAnchor.constraint(equalTo: safeArea.topAnchor))
            // Bottom-positioned cards go flush to the literal screen edge, under the home
            // indicator — not inset to the safe area. A bottom sheet should touch the true
            // bottom of the screen; the WebView content itself is responsible for its own
            // bottom padding if it needs to clear the home indicator. The constant lifts the
            // card above the keyboard while it's up (see keyboardWillChangeFrame).
            case .bottom:
                let bottom = popupShell.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -keyboardHeight)
                bottomEdgeConstraint = bottom
                constraints.append(bottom)
            // Centered cards shift up by half the keyboard overlap, staying centered in the
            // space that remains above the keyboard.
            case .center:
                let centerY = popupShell.centerYAnchor.constraint(equalTo: safeArea.centerYAnchor, constant: -keyboardHeight / 2)
                centerYConstraint = centerY
                constraints.append(centerY)
            }
            switch alignment.horizontal {
            case .start: constraints.append(popupShell.leadingAnchor.constraint(equalTo: safeArea.leadingAnchor))
            case .end: constraints.append(popupShell.trailingAnchor.constraint(equalTo: safeArea.trailingAnchor))
            case .center: constraints.append(popupShell.centerXAnchor.constraint(equalTo: safeArea.centerXAnchor))
            }
            let width = popupShell.widthAnchor.constraint(equalToConstant: maxWidthDp)
            self.widthConstraint = width
            constraints.append(width)
            positionConstraints = constraints
        }
        NSLayoutConstraint.activate(positionConstraints)
    }

    /// Resolves and applies theme-derived colors only, without touching position/size/height —
    /// called on initial presentation and again from `traitCollectionDidChange`.
    @discardableResult
    private func applyThemeColorsReturningMode(payload: ShowFormPayload, position: String) -> (String, Bool) {
        let appearanceProperties = payload.formConfig.appearanceProperties
        let corners = resolveCornersFromFormConfig(appearanceProperties)
        let darkOverlay = resolveDarkOverlayFromFormConfig(appearanceProperties)

        let systemDark = isSystemDark(traitCollection)
        let systemScheme = resolveSystemColorScheme(isSystemDark: systemDark)
        let shareableMode = extractShareableMode(appearanceProperties)
        let activeMode = resolveActiveMode(shareableMode: payload.theme?.wireValue ?? shareableMode, systemScheme: systemScheme)

        let themeJson = extractThemeJsonForMode(appearanceProperties, mode: activeMode)
        let fallbackBg = activeMode == "dark" ? "#1a1a1a" : "#ffffff"
        let backgroundColorStr = getBackgroundColor(themeJson, fallback: fallbackBg)
        let fallbackArgb: Int32 = activeMode == "dark" ? Int32(bitPattern: 0xFF1A1A1A) : Int32(bitPattern: 0xFFFFFFFF)
        let backgroundArgb = parseCssColorToArgb(backgroundColorStr, fallbackArgb: fallbackArgb)

        let overlayColorStr = resolveModalOverlayBackgroundColor(appearanceProperties: appearanceProperties, activeMode: activeMode, darkOverlay: darkOverlay)
        let overlayArgb = parseCssColorToArgb(overlayColorStr, fallbackArgb: 0)

        let radii = getBorderRadii(position, corners: corners)
        popupShell.backgroundColor = uiColor(fromArgb: backgroundArgb)
        applyCorners(radii: radii, corners: corners)
        overlayView.backgroundColor = uiColor(fromArgb: overlayArgb)
        webView.backgroundColor = .clear

        return (activeMode, darkOverlay)
    }

    private func applyThemeColors(payload: ShowFormPayload, position: String) {
        let (activeMode, darkOverlay) = applyThemeColorsReturningMode(payload: payload, position: position)
        applyBackdropBlur(enabled: !darkOverlay, activeMode: activeMode)
    }

    /// Frosted backdrop when darkOverlay is off, at partial intensity via the paused-animator
    /// technique (a bare `UIBlurEffect` has no intensity knob) — matching RN's expo-blur
    /// intensity 52 with the active mode as the tint.
    private func applyBackdropBlur(enabled: Bool, activeMode: String) {
        backdropBlurAnimator?.stopAnimation(true)
        backdropBlurAnimator = nil
        backdropBlur.effect = nil
        guard enabled else { return }
        let animator = UIViewPropertyAnimator(duration: 1, curve: .linear) { [backdropBlur] in
            backdropBlur.effect = UIBlurEffect(style: activeMode == "dark" ? .dark : .light)
        }
        animator.fractionComplete = Self.backdropBlurIntensity
        animator.pausesOnCompletion = true
        backdropBlurAnimator = animator
    }

    private func applyCorners(radii: PopupBorderRadii, corners: CornerStyle) {
        let radiusDp = CGFloat(resolveCornerRadiusDp(corners))
        var maskedCorners: CACornerMask = []
        if radii.topLeftDp > 0 { maskedCorners.insert(.layerMinXMinYCorner) }
        if radii.topRightDp > 0 { maskedCorners.insert(.layerMaxXMinYCorner) }
        if radii.bottomLeftDp > 0 { maskedCorners.insert(.layerMinXMaxYCorner) }
        if radii.bottomRightDp > 0 { maskedCorners.insert(.layerMaxXMaxYCorner) }
        // Shell rounds its background (and shadow silhouette); contentClip actually clips the
        // WebView/skeleton to the same rounded shape.
        popupShell.layer.cornerRadius = radiusDp
        popupShell.layer.maskedCorners = maskedCorners
        contentClip.layer.cornerRadius = radiusDp
        contentClip.layer.maskedCorners = maskedCorners
        popupShell.layer.shadowOpacity = isFullCenter ? 0 : 0.3
    }

    private func runEntranceAnimation(activeMode: String) {
        let config = getAnimationConfig(currentPosition)
        popupShell.alpha = 0
        if config.type == "slide" {
            popupShell.transform = CGAffineTransform(
                translationX: CGFloat(config.txFractionPercent),
                y: CGFloat(config.tyFractionPercent)
            )
        } else {
            popupShell.transform = CGAffineTransform(scaleX: 0.8, y: 0.8)
        }
        UIView.animate(withDuration: 0.3, delay: 0, options: [.curveEaseOut]) {
            self.popupShell.alpha = 1
            self.popupShell.transform = .identity
        }
    }

    private func runExitAnimation(onDone: @escaping () -> Void) {
        let config = getAnimationConfig(currentPosition)
        UIView.animate(withDuration: 0.25, animations: {
            self.popupShell.alpha = 0
            if config.type == "slide" {
                self.popupShell.transform = CGAffineTransform(
                    translationX: CGFloat(config.txFractionPercent),
                    y: CGFloat(config.tyFractionPercent)
                )
            } else {
                self.popupShell.transform = CGAffineTransform(scaleX: 0.8, y: 0.8)
            }
        }, completion: { _ in onDone() })
    }

    private func hideSkeleton() {
        isLoadingForm = false
        skeleton.stop()
        // Drop the placeholder loading height if the form never reported a real one.
        if contentHeight <= 0 { applyHeight(contentHeight) }
    }

    /// The popup keeps its full-screen target size while the keyboard is up as long as it still
    /// fits above the keyboard; otherwise it shrinks to the space that remains (mirrors the
    /// React Native SDK's `usableHeight`/`maxDialogHeight` keyboard math in EncatchWebView.tsx).
    private var effectiveMaxDialogHeight: CGFloat {
        guard keyboardHeight > 0 else { return maxDialogHeight }
        let available = view.bounds.height - view.safeAreaInsets.top - keyboardHeight
        return min(maxDialogHeight, max(available, 100))
    }

    private func applyHeight(_ height: CGFloat) {
        contentHeight = height
        // While loading (skeleton up, no `form:height` from the bridge yet) hold a fixed
        // placeholder height so the shimmer skeleton is actually visible — without it the shell
        // sits at 0pt until the first height message and nothing appears during the load.
        let effectiveHeight = (isLoadingForm && height <= 0) ? Self.loadingSkeletonHeight : height
        let cap = effectiveMaxDialogHeight
        let target = (isFullHeightOverlay || isFullCenter) ? cap : min(effectiveHeight, cap)
        heightConstraint?.isActive = false
        heightConstraint = popupShell.heightAnchor.constraint(equalToConstant: max(target, 0))
        heightConstraint?.isActive = true
        view.layoutIfNeeded()
    }

    // MARK: Keyboard avoidance

    @objc private func keyboardWillChangeFrame(_ notification: Notification) {
        guard let endFrame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
        let frameInView = view.convert(endFrame, from: nil)
        let overlap = max(0, view.bounds.maxY - frameInView.minY)
        updateKeyboardHeight(overlap, notification: notification)
    }

    @objc private func keyboardWillHide(_ notification: Notification) {
        updateKeyboardHeight(0, notification: notification)
    }

    /// Lifts the shell above the keyboard and re-caps its height, animated alongside the
    /// keyboard's own duration/curve so the two move as one.
    private func updateKeyboardHeight(_ height: CGFloat, notification: Notification) {
        guard height != keyboardHeight, isViewLoaded else { return }
        keyboardHeight = height
        bottomEdgeConstraint?.constant = -height
        centerYConstraint?.constant = -height / 2

        let duration = notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double ?? 0.25
        let curveRaw = notification.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? UInt ?? 7
        UIView.animate(
            withDuration: duration,
            delay: 0,
            options: [UIView.AnimationOptions(rawValue: curveRaw << 16), .beginFromCurrentState]
        ) {
            self.applyHeight(self.contentHeight)
        }
    }

    private func applyForceFullHeight(_ force: Bool) {
        isFullHeightOverlay = force
        applyHeight(contentHeight)
    }

    private func close(immediate: Bool) {
        Encatch.shared.setFormVisible(false)
        if immediate {
            dismiss(animated: false)
        } else {
            runExitAnimation { [weak self] in self?.dismiss(animated: false) }
        }
    }

    public override func dismiss(animated: Bool, completion: (() -> Void)? = nil) {
        bridge.setFormPayload(nil)
        currentPayload = nil
        backdropBlurAnimator?.stopAnimation(true)
        backdropBlurAnimator = nil
        backdropBlur.effect = nil
        keyboardHeight = 0
        bottomEdgeConstraint?.constant = 0
        centerYConstraint?.constant = 0
        super.dismiss(animated: animated, completion: completion)
    }
}
#endif
