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

    /// Fractional blur strength when darkOverlay is off. Deliberately much lighter than the
    /// React Native SDK's `MODAL_BACKDROP_BLUR_INTENSITY` (52/100) — at that strength the host
    /// UI behind the modal is barely recognizable; a subtle frost reads better.
    private static let backdropBlurIntensity: CGFloat = 0.15
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

    /// Auto-closes the modal if `form:ready` never arrives (silent load hang: no error callback,
    /// no bridge messages). Without an escape hatch the close button — which lives inside the
    /// web page — can never appear, trapping the user behind the overlay until app kill.
    private var readyWatchdog: Timer?
    private static let readyWatchdogTimeout: TimeInterval = 20

    /// Native fail-safe close (✕) shown while loading: appears 1s after present so a slow or
    /// dead form page can always be dismissed by hand, independent of the web page's own close
    /// button; fades away once real form content arrives.
    private let loadingCloseButton = UIButton(type: .system)
    private static let loadingCloseButtonDelay: TimeInterval = 1

    /// When the form's closeButton setting is enabled, a tap on the overlay area outside the
    /// card also closes the modal (per-present, from the show-form response).
    private var closeOnOverlayTap = false
    private var maxDialogHeight: CGFloat = .greatestFiniteMagnitude
    private var contentHeight: CGFloat = 0
    private var isFullHeightOverlay = false
    private var isFullCenter = false
    private var currentPosition = "middle-center"
    private var currentPayload: ShowFormPayload?

    private lazy var bridge: FormWebViewBridge = FormWebViewBridge(
        logTag: "Encatch",
        presentation: "modal",
        // The bridge invokes these from WKWebView's script-message handler, which is always the
        // main thread — assumeIsolated states that for Swift 5.10, whose isolation inference
        // can't prove it (newer compilers accept the bare calls).
        onClose: { [weak self] immediate in MainActor.assumeIsolated { self?.close(immediate: immediate) } },
        onHeightChange: { [weak self] height in MainActor.assumeIsolated { self?.applyHeight(CGFloat(height), animated: true) } },
        onForceFullHeight: { [weak self] force in MainActor.assumeIsolated { self?.applyForceFullHeight(force) } },
        onReady: { [weak self] in MainActor.assumeIsolated { self?.hideSkeleton() } },
        sendToWebView: { [weak self] message in MainActor.assumeIsolated { self?.webView.sendToWebView(message) } },
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
        webView.onUnrecoverableFailure = { [weak self] reason in
            NSLog("[Encatch] closing modal form: \(reason)")
            self?.close(immediate: true)
        }
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

        loadingCloseButton.setImage(UIImage(systemName: "xmark"), for: .normal)
        loadingCloseButton.layer.cornerRadius = 14
        loadingCloseButton.isHidden = true
        loadingCloseButton.alpha = 0
        loadingCloseButton.addTarget(self, action: #selector(loadingCloseTapped), for: .touchUpInside)
        loadingCloseButton.translatesAutoresizingMaskIntoConstraints = false
        popupShell.addSubview(loadingCloseButton)
        NSLayoutConstraint.activate([
            loadingCloseButton.topAnchor.constraint(equalTo: popupShell.topAnchor, constant: 10),
            loadingCloseButton.trailingAnchor.constraint(equalTo: popupShell.trailingAnchor, constant: -10),
            loadingCloseButton.widthAnchor.constraint(equalToConstant: 28),
            loadingCloseButton.heightAnchor.constraint(equalToConstant: 28),
        ])

        // overlayView spans the whole screen behind popupShell, so a tap landing on it is by
        // definition outside the card; taps on the card hit popupShell/webView and never reach it.
        overlayView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(overlayTapped)))

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

        readyWatchdog?.invalidate()
        readyWatchdog = Timer.scheduledTimer(withTimeInterval: Self.readyWatchdogTimeout, repeats: false) { [weak self] _ in
            NSLog("[Encatch] closing modal form: form:ready not received within \(Self.readyWatchdogTimeout)s")
            self?.close(immediate: true)
        }

        // Theme the fail-safe ✕ against the shell's themed background, then reveal it after a
        // grace period if the form still hasn't produced content.
        let inkColor: UIColor = activeMode == "dark" ? .white : .black
        loadingCloseButton.tintColor = inkColor.withAlphaComponent(0.6)
        loadingCloseButton.backgroundColor = .clear
        loadingCloseButton.isHidden = true
        loadingCloseButton.alpha = 0
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.loadingCloseButtonDelay) { [weak self] in
            guard let self, self.isLoadingForm else { return }
            self.loadingCloseButton.isHidden = false
            self.popupShell.bringSubviewToFront(self.loadingCloseButton)
            UIView.animate(withDuration: 0.2) { self.loadingCloseButton.alpha = 1 }
        }

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

        closeOnOverlayTap = resolveCloseButtonFromFormConfig(appearanceProperties)

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
        // Called from the bridge's onReady — which the 0.3s didFinish fallback can fire even
        // when the page HTML loaded but the form JS never booted (observed live: post-load
        // WebContent at 16MB / 0% CPU, no form:height ever sent). A "ready" without any
        // reported height means nothing is actually rendered: keep the skeleton pulsing and
        // the watchdog armed instead of collapsing to an invisible 0pt card behind the
        // overlay. `finishLoading()` runs from applyHeight when real content arrives.
        guard contentHeight > 0 else { return }
        finishLoading()
    }

    private func finishLoading() {
        readyWatchdog?.invalidate()
        readyWatchdog = nil
        isLoadingForm = false
        skeleton.stop()
        // The real form has its own close button — retire the fail-safe ✕.
        UIView.animate(withDuration: 0.2) {
            self.loadingCloseButton.alpha = 0
        } completion: { _ in
            self.loadingCloseButton.isHidden = true
        }
    }

    @objc private func loadingCloseTapped() {
        close(immediate: false)
    }

    @objc private func overlayTapped() {
        guard closeOnOverlayTap else { return }
        close(immediate: false)
    }

    /// The popup keeps its full-screen target size while the keyboard is up as long as it still
    /// fits above the keyboard; otherwise it shrinks to the space that remains (mirrors the
    /// React Native SDK's `usableHeight`/`maxDialogHeight` keyboard math in EncatchWebView.tsx).
    private var effectiveMaxDialogHeight: CGFloat {
        guard keyboardHeight > 0 else { return maxDialogHeight }
        let available = view.bounds.height - view.safeAreaInsets.top - keyboardHeight
        return min(maxDialogHeight, max(available, 100))
    }

    private func applyHeight(_ height: CGFloat, animated: Bool = false) {
        contentHeight = height
        if height > 0, isLoadingForm { finishLoading() }
        // While no real `form:height` has arrived, hold a fixed placeholder height so the
        // shimmer skeleton is visible and the shell can never collapse to an invisible 0pt
        // card behind the overlay.
        let effectiveHeight = height <= 0 ? Self.loadingSkeletonHeight : height
        let cap = effectiveMaxDialogHeight
        let target = (isFullHeightOverlay || isFullCenter) ? cap : min(effectiveHeight, cap)
        heightConstraint?.isActive = false
        heightConstraint = popupShell.heightAnchor.constraint(equalToConstant: max(target, 0))
        heightConstraint?.isActive = true
        // Bridge-driven resizes (placeholder→real height, step changes) animate so the shell
        // glides instead of snapping. Keyboard-driven calls pass animated: false because they
        // already run inside the keyboard notification's own animation block.
        if animated, view.window != nil {
            UIView.animate(withDuration: 0.3, delay: 0, options: [.curveEaseOut, .beginFromCurrentState]) {
                self.view.layoutIfNeeded()
            }
        } else {
            view.layoutIfNeeded()
        }
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
        readyWatchdog?.invalidate()
        readyWatchdog = nil
        super.dismiss(animated: animated, completion: completion)
    }
}
#endif
