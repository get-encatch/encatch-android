import EncatchCore
import UIKit

/// Modal overlay hosting `EncatchWebView`, mirroring `:android`'s `EncatchFormDialog`:
/// position/size/corners/background/overlay resolved from the form's `appearanceProperties`,
/// height capped at `maxDialogHeightPercentInApp` (default 80%) of the screen, dismissible via
/// the bridge's `onClose`. Live light/dark-mode reactivity comes for free from
/// `traitCollectionDidChange` — no manual system-appearance observer needed, unlike Android.
public final class EncatchFormViewController: UIViewController {

    private let webView = EncatchWebView()
    private let popupShell = UIView()
    private let overlayView = UIView()
    private let skeleton = UIActivityIndicatorView(style: .medium)

    private let scope = UiSupportKt.createUiCoroutineScope()
    private let redirectBrowser = RedirectBrowser()
    private var webViewInstanceKey: Int32 = 0

    private var heightConstraint: NSLayoutConstraint?
    private var widthConstraint: NSLayoutConstraint?
    private var positionConstraints: [NSLayoutConstraint] = []
    private var maxDialogHeight: CGFloat = .greatestFiniteMagnitude
    private var contentHeight: CGFloat = 0
    private var isFullHeightOverlay = false
    private var isFullCenter = false
    private var currentPosition = "middle-center"
    private var currentPayload: ShowFormPayload?

    private lazy var bridge: FormWebViewBridge = FormWebViewBridge(
        scope: scope,
        logTag: "Encatch",
        presentation: "modal",
        onClose: { [weak self] immediate in self?.close(immediate: immediate.boolValue) },
        onHeightChange: { [weak self] height in self?.applyHeight(CGFloat(height.intValue)) },
        onForceFullHeight: { [weak self] force in self?.applyForceFullHeight(force.boolValue) },
        onReady: { [weak self] in self?.hideSkeleton() },
        sendToWebView: { [weak self] message in self?.webView.sendToWebView(message) },
        redirectOpener: redirectBrowser,
        openExternal: { [weak self] url in self?.redirectBrowser.openExternal(url) },
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

        popupShell.clipsToBounds = true
        popupShell.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(popupShell)

        webView.bridge = bridge
        webView.translatesAutoresizingMaskIntoConstraints = false
        popupShell.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: popupShell.topAnchor),
            webView.bottomAnchor.constraint(equalTo: popupShell.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: popupShell.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: popupShell.trailingAnchor),
        ])

        skeleton.translatesAutoresizingMaskIntoConstraints = false
        popupShell.addSubview(skeleton)
        NSLayoutConstraint.activate([
            skeleton.centerXAnchor.constraint(equalTo: popupShell.centerXAnchor),
            skeleton.centerYAnchor.constraint(equalTo: popupShell.centerYAnchor),
        ])
    }

    public func present(payload: ShowFormPayload, from presenter: UIViewController) {
        webViewInstanceKey += 1
        currentPayload = payload
        let (activeMode, _) = applyAppearance(payload: payload)
        skeleton.startAnimating()
        bridge.setFormPayload(payload: payload)
        Encatch.shared.setFormVisible(visible: true)

        let url = buildFormWebViewUrl(
            webHost: Encatch.shared.webHost,
            formId: payload.formId,
            instanceKey: webViewInstanceKey,
            debugMode: Encatch.shared.debugMode,
            presentation: "modal",
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
        _ = applyThemeColors(payload: payload, position: currentPosition)
    }

    private func applyAppearance(payload: ShowFormPayload) -> (String, Bool) {
        let screenSize = UIScreen.main.bounds.size
        let screenWidthDp = Int32(screenSize.width)
        let appearanceProperties = payload.formConfig.appearanceProperties

        let rawPosition = FormAppearanceKt.resolveSelectedPositionFromFormConfig(appearanceProperties: appearanceProperties)
        let position = FormAppearanceKt.normalizePosition(position: rawPosition, screenWidthDp: screenWidthDp)
        isFullCenter = position == "full-center"
        currentPosition = position

        let size = FormAppearanceKt.resolveInAppSizeFromFormConfig(appearanceProperties: appearanceProperties)
        let maxWidthDp = FormAppearanceKt.resolveInAppMaxWidthDp(size: size, position: position, screenWidthDp: screenWidthDp, horizontalInsetDp: 0)

        let maxHeightFraction = CGFloat(FormAppearanceKt.resolveMaxDialogHeightFraction(appearanceProperties: appearanceProperties))
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

        let safeArea = view.safeAreaLayoutGuide
        if isFullCenter {
            positionConstraints = [
                popupShell.topAnchor.constraint(equalTo: view.topAnchor),
                popupShell.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                popupShell.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                popupShell.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            ]
        } else {
            let alignment = FormAppearanceKt.getPositionLayout(position: position)
            var constraints: [NSLayoutConstraint] = []
            switch alignment.vertical {
            case .top: constraints.append(popupShell.topAnchor.constraint(equalTo: safeArea.topAnchor, constant: 16))
            case .bottom: constraints.append(popupShell.bottomAnchor.constraint(equalTo: safeArea.bottomAnchor, constant: -16))
            default: constraints.append(popupShell.centerYAnchor.constraint(equalTo: safeArea.centerYAnchor))
            }
            switch alignment.horizontal {
            case .start: constraints.append(popupShell.leadingAnchor.constraint(equalTo: safeArea.leadingAnchor, constant: 16))
            case .end: constraints.append(popupShell.trailingAnchor.constraint(equalTo: safeArea.trailingAnchor, constant: -16))
            default: constraints.append(popupShell.centerXAnchor.constraint(equalTo: safeArea.centerXAnchor))
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
        let corners = FormAppearanceKt.resolveCornersFromFormConfig(appearanceProperties: appearanceProperties)
        let darkOverlay = FormAppearanceKt.resolveDarkOverlayFromFormConfig(appearanceProperties: appearanceProperties)

        let systemDark = isSystemDark(traitCollection)
        let systemScheme = FormThemeColorKt.resolveSystemColorScheme(isSystemDark: systemDark)
        let shareableMode = FormThemeColorKt.extractShareableMode(appearanceProperties: appearanceProperties)
        let activeMode = FormThemeColorKt.resolveActiveMode(shareableMode: payload.theme?.wireValue ?? shareableMode, systemScheme: systemScheme)

        let themeJson = FormThemeColorKt.extractThemeJsonForMode(appearanceProperties: appearanceProperties, mode: activeMode)
        let fallbackBg = activeMode == "dark" ? "#1a1a1a" : "#ffffff"
        let backgroundColorStr = FormThemeColorKt.getBackgroundColor(themeJson: themeJson, fallback: fallbackBg)
        let fallbackArgb: Int32 = activeMode == "dark" ? Int32(bitPattern: 0xFF1A1A1A) : Int32(bitPattern: 0xFFFFFFFF)
        let backgroundArgb = FormThemeColorKt.parseCssColorToArgb(color: backgroundColorStr, fallbackArgb: fallbackArgb)

        let overlayColorStr = FormThemeColorKt.resolveModalOverlayBackgroundColor(appearanceProperties: appearanceProperties, activeMode: activeMode, darkOverlay: darkOverlay)
        let overlayArgb = FormThemeColorKt.parseCssColorToArgb(color: overlayColorStr, fallbackArgb: 0)

        let radii = FormAppearanceKt.getBorderRadii(position: position, corners: corners)
        popupShell.backgroundColor = uiColor(fromArgb: backgroundArgb)
        applyCorners(radii: radii, corners: corners)
        overlayView.backgroundColor = uiColor(fromArgb: overlayArgb)
        webView.backgroundColor = .clear

        return (activeMode, darkOverlay)
    }

    private func applyThemeColors(payload: ShowFormPayload, position: String) {
        _ = applyThemeColorsReturningMode(payload: payload, position: position)
    }

    private func applyCorners(radii: PopupBorderRadii, corners: CornerStyle) {
        let radiusDp = CGFloat(FormAppearanceKt.resolveCornerRadiusDp(corners: corners))
        var maskedCorners: CACornerMask = []
        if radii.topLeftDp > 0 { maskedCorners.insert(.layerMinXMinYCorner) }
        if radii.topRightDp > 0 { maskedCorners.insert(.layerMaxXMinYCorner) }
        if radii.bottomLeftDp > 0 { maskedCorners.insert(.layerMinXMaxYCorner) }
        if radii.bottomRightDp > 0 { maskedCorners.insert(.layerMaxXMaxYCorner) }
        popupShell.layer.cornerRadius = radiusDp
        popupShell.layer.maskedCorners = maskedCorners
    }

    private func runEntranceAnimation(activeMode: String) {
        let config = FormAppearanceKt.getAnimationConfig(position: currentPosition)
        popupShell.alpha = 0
        if config.type == "slide" {
            popupShell.transform = CGAffineTransform(
                translationX: CGFloat(config.txFractionPercent),
                y: CGFloat(config.tyFractionPercent),
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
        let config = FormAppearanceKt.getAnimationConfig(position: currentPosition)
        UIView.animate(withDuration: 0.25, animations: {
            self.popupShell.alpha = 0
            if config.type == "slide" {
                self.popupShell.transform = CGAffineTransform(
                    translationX: CGFloat(config.txFractionPercent),
                    y: CGFloat(config.tyFractionPercent),
                )
            } else {
                self.popupShell.transform = CGAffineTransform(scaleX: 0.8, y: 0.8)
            }
        }, completion: { _ in onDone() })
    }

    private func hideSkeleton() {
        skeleton.stopAnimating()
    }

    private func applyHeight(_ height: CGFloat) {
        contentHeight = height
        let target = (isFullHeightOverlay || isFullCenter) ? maxDialogHeight : min(height, maxDialogHeight)
        heightConstraint?.isActive = false
        heightConstraint = popupShell.heightAnchor.constraint(equalToConstant: max(target, 0))
        heightConstraint?.isActive = true
        view.layoutIfNeeded()
    }

    private func applyForceFullHeight(_ force: Bool) {
        isFullHeightOverlay = force
        applyHeight(contentHeight)
    }

    private func close(immediate: Bool) {
        Encatch.shared.setFormVisible(visible: false)
        if immediate {
            dismiss(animated: false)
        } else {
            runExitAnimation { [weak self] in self?.dismiss(animated: false) }
        }
    }

    public override func dismiss(animated: Bool, completion: (() -> Void)? = nil) {
        bridge.setFormPayload(payload: nil)
        currentPayload = nil
        super.dismiss(animated: animated, completion: completion)
    }
}
