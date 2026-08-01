import EncatchCore
import UIKit

/// Renders the Encatch form inline within the host view hierarchy — no modal, no overlay. Mirrors
/// `:android`'s `EncatchInlineFormView`.
///
/// Routing (resolved by `InlineSlotRegistry` before this view receives anything):
///  - Exact match: set `formId` to catch `showForm("slug")` for that id only.
///  - Wildcard: leave `formId` nil to catch any form not claimed by an exact slot.
///  - Fallback: when no inline slot is registered, `EncatchFormViewController` takes over.
///
/// Slot registration is tied to this view's window attach/detach lifecycle.
public final class EncatchInlineFormView: UIView {

    public var formId: String? {
        didSet {
            if let slotId { InlineSlotRegistry.shared.updateInlineSlot(slotId: slotId, formId: formId) }
        }
    }

    /// Minimum height floor (points) after the first `form:resize`. Defaults to 0.
    public var minHeight: CGFloat = 0

    /// Called when an in-form overlay (QnA with AI, Scheduler) opens or closes.
    public var onOverlayOpenChange: ((Bool) -> Void)?

    private var slotId: String?
    private var unsubscribe: (() -> Void)?

    private var webView: EncatchWebView?
    private var skeleton: UIActivityIndicatorView?
    private var webViewInstanceKey: Int32 = 0

    private let scope = UiSupportKt.createUiCoroutineScope()
    private let redirectBrowser = RedirectBrowser()
    private var heightConstraint: NSLayoutConstraint?
    private var contentHeight: CGFloat = 0
    private var overlayActive = false
    private var overlayFrozenHeight: CGFloat?
    private var currentPayload: ShowFormPayload?

    public override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        heightConstraint = heightAnchor.constraint(equalToConstant: 0)
        heightConstraint?.isActive = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    public override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow != nil, slotId == nil {
            slotId = InlineSlotRegistry.shared.registerInlineSlot(formId: formId)
            unsubscribe = EncatchInternalEmitter.shared.on { [weak self] event in self?.handle(event: event) }
        } else if newWindow == nil, let currentSlotId = slotId {
            InlineSlotRegistry.shared.unregisterInlineSlot(slotId: currentSlotId)
            slotId = nil
            unsubscribe?()
            unsubscribe = nil
            if bridge?.formPayload != nil { Encatch.shared.setFormVisible(visible: false) }
            clearForm()
        }
    }

    private var bridge: FormWebViewBridge?

    private func handle(event: InternalEvent?) {
        switch event {
        case let showForm as InternalEvent.ShowForm:
            if showForm.payload.presentation == "inline", showForm.payload.inlineSlotId == slotId {
                loadForm(payload: showForm.payload)
            } else if bridge?.formPayload != nil {
                Encatch.shared.setFormVisible(visible: false)
                clearForm()
            }
        case is InternalEvent.DismissForm:
            if bridge?.formPayload != nil {
                Encatch.shared.setFormVisible(visible: false)
                clearForm()
            }
        default:
            break
        }
    }

    private func loadForm(payload: ShowFormPayload) {
        subviews.forEach { $0.removeFromSuperview() }
        webViewInstanceKey += 1
        contentHeight = 0
        overlayFrozenHeight = nil
        overlayActive = false
        currentPayload = payload

        let newWebView = EncatchWebView()
        let newBridge = FormWebViewBridge(
            scope: scope,
            logTag: "Encatch",
            presentation: "inline",
            onClose: { [weak self] _ in
                Encatch.shared.setFormVisible(visible: false)
                self?.clearForm()
            },
            onHeightChange: { [weak self] height in self?.applyHeight(CGFloat(height.intValue)) },
            onForceFullHeight: { [weak self] force in self?.applyForceFullHeight(force.boolValue) },
            onReady: { [weak self] in self?.skeleton?.stopAnimating(); self?.skeleton?.removeFromSuperview(); self?.skeleton = nil },
            sendToWebView: { [weak self] message in self?.webView?.sendToWebView(message) },
            redirectOpener: redirectBrowser,
            openExternal: { [weak self] url in self?.redirectBrowser.openExternal(url) },
        )
        newWebView.bridge = newBridge
        newWebView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(newWebView)
        NSLayoutConstraint.activate([
            newWebView.topAnchor.constraint(equalTo: topAnchor),
            newWebView.bottomAnchor.constraint(equalTo: bottomAnchor),
            newWebView.leadingAnchor.constraint(equalTo: leadingAnchor),
            newWebView.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])

        newBridge.setFormPayload(payload: payload)
        webView = newWebView
        bridge = newBridge

        applyInlineAppearance(payload: payload)

        let loadingIndicator = UIActivityIndicatorView(style: .medium)
        loadingIndicator.translatesAutoresizingMaskIntoConstraints = false
        addSubview(loadingIndicator)
        NSLayoutConstraint.activate([
            loadingIndicator.centerXAnchor.constraint(equalTo: centerXAnchor),
            loadingIndicator.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
        loadingIndicator.startAnimating()
        skeleton = loadingIndicator

        applyHeight(300)
        Encatch.shared.setFormVisible(visible: true)

        let url = buildFormWebViewUrl(
            webHost: Encatch.shared.webHost,
            formId: payload.formId,
            instanceKey: webViewInstanceKey,
            debugMode: Encatch.shared.debugMode,
            presentation: "inline",
        )
        newWebView.loadFormUrl(url)
    }

    private func applyInlineAppearance(payload: ShowFormPayload) {
        let appearanceProperties = FormAppearanceKt.asJsonObjectOrNull(element: payload.formConfig.appearanceProperties)
        let corners = FormAppearanceKt.resolveCornersFromFormConfig(appearanceProperties: appearanceProperties)

        let systemScheme = FormThemeColorKt.resolveSystemColorScheme(isSystemDark: isSystemDark(traitCollection))
        let shareableMode = FormThemeColorKt.extractShareableMode(appearanceProperties: appearanceProperties)
        let activeMode = FormThemeColorKt.resolveActiveMode(shareableMode: payload.theme?.wireValue ?? shareableMode, systemScheme: systemScheme)

        let themeJson = FormThemeColorKt.extractThemeJsonForMode(appearanceProperties: appearanceProperties, mode: activeMode)
        let fallbackBg = activeMode == "dark" ? "#1a1a1a" : "#ffffff"
        let backgroundColorStr = FormThemeColorKt.getBackgroundColor(themeJson: themeJson, fallback: fallbackBg)
        let fallbackArgb: Int32 = activeMode == "dark" ? Int32(bitPattern: 0xFF1A1A1A) : Int32(bitPattern: 0xFFFFFFFF)
        let backgroundArgb = FormThemeColorKt.parseCssColorToArgb(color: backgroundColorStr, fallbackArgb: fallbackArgb)

        let radii = FormAppearanceKt.getInlineBorderRadii(corners: corners)
        let radiusPoints = CGFloat(FormAppearanceKt.resolveCornerRadiusDp(corners: corners))
        backgroundColor = uiColor(fromArgb: backgroundArgb)
        layer.cornerRadius = radii.topLeftDp > 0 ? radiusPoints : 0
        webView?.backgroundColor = .clear
    }

    private func clearForm() {
        webView = nil
        bridge = nil
        skeleton = nil
        contentHeight = 0
        overlayFrozenHeight = nil
        overlayActive = false
        currentPayload = nil
        subviews.forEach { $0.removeFromSuperview() }
        applyHeight(0)
    }

    private func resolveContentHeight(_ height: CGFloat) -> CGFloat {
        minHeight > 0 ? max(height, minHeight) : height
    }

    private func applyHeight(_ height: CGFloat) {
        if overlayActive { return }
        let resolved = resolveContentHeight(height)
        contentHeight = resolved
        heightConstraint?.constant = resolved
    }

    private func applyForceFullHeight(_ force: Bool) {
        overlayActive = force
        if force {
            let base = contentHeight > 0 ? resolveContentHeight(contentHeight) : (minHeight > 0 ? minHeight : 300)
            let maxHeight = (window?.bounds.height ?? UIScreen.main.bounds.height) * 0.8
            let frozen = min(base, maxHeight)
            overlayFrozenHeight = frozen
            heightConstraint?.constant = frozen
        } else {
            overlayFrozenHeight = nil
            heightConstraint?.constant = contentHeight > 0 ? contentHeight : (minHeight > 0 ? minHeight : 300)
        }
        onOverlayOpenChange?(force)
    }

    public override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        guard traitCollection.hasDifferentColorAppearance(comparedTo: previousTraitCollection),
              let payload = currentPayload else { return }
        applyInlineAppearance(payload: payload)
    }
}
