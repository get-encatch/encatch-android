#if canImport(UIKit)
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
// `@objc` annotations below exist solely so Kotlin/Native's cinterop (via `EncatchBridge.swift`)
// can see and drive this view's `formId`/`minHeight` from CMP/KMP's iOS targets — see
// `ObjCBridge/EncatchBridge.swift`'s file-level doc comment for the full rationale.
@objc(EncatchInlineFormView)
public final class EncatchInlineFormView: UIView {

    @objc public var formId: String? {
        didSet {
            if let slotId { InlineSlotRegistry.shared.updateInlineSlot(slotId, formId: formId) }
        }
    }

    /// Minimum height floor (points) after the first `form:resize`. Defaults to 0.
    @objc public var minHeight: CGFloat = 0

    /// Called when an in-form overlay (QnA with AI, Scheduler) opens or closes.
    public var onOverlayOpenChange: ((Bool) -> Void)?

    /// Called whenever the view's self-sized height changes (skeleton placeholder, every
    /// `form:resize`, overlay freeze/unfreeze, clear). Auto Layout hosts don't need this — the
    /// view sizes itself via its own height constraint — but SwiftUI/manual-layout hosts can
    /// bind it to their own frame instead of hardcoding a height.
    @objc public var onHeightChange: ((CGFloat) -> Void)?

    private var slotId: String?
    private var unsubscribe: (() -> Void)?

    private var webView: EncatchWebView?
    private var skeleton: FormWebViewSkeletonView?
    private var webViewInstanceKey: Int32 = 0

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
            unsubscribe = EncatchInternalEmitter.shared.on { [weak self] event in
                // See EncatchFormHost's twin comment — emit(...) isn't guaranteed to run on the
                // main thread, and this touches UIKit/WebKit.
                DispatchQueue.main.async { self?.handle(event: event) }
            }
        } else if newWindow == nil, let currentSlotId = slotId {
            InlineSlotRegistry.shared.unregisterInlineSlot(currentSlotId)
            slotId = nil
            unsubscribe?()
            unsubscribe = nil
            if bridge?.formPayload != nil { Encatch.shared.setFormVisible(false) }
            clearForm()
        }
    }

    private var bridge: FormWebViewBridge?

    private func handle(event: InternalEvent) {
        switch event {
        case .showForm(let payload):
            if payload.presentation == "inline", payload.inlineSlotId == slotId {
                loadForm(payload: payload)
            } else if bridge?.formPayload != nil {
                Encatch.shared.setFormVisible(false)
                clearForm()
            }
        case .dismissForm:
            if bridge?.formPayload != nil {
                Encatch.shared.setFormVisible(false)
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
            logTag: "Encatch",
            presentation: "inline",
            // The bridge invokes these from WKWebView's script-message handler, which is always
            // the main thread — assumeIsolated states that for Swift 5.10, whose isolation
            // inference can't prove it (see EncatchFormViewController's twin comment).
            onClose: { [weak self] _ in
                Encatch.shared.setFormVisible(false)
                MainActor.assumeIsolated { self?.clearForm() }
            },
            onHeightChange: { [weak self] height in MainActor.assumeIsolated { self?.applyHeight(CGFloat(height)) } },
            onForceFullHeight: { [weak self] force in MainActor.assumeIsolated { self?.applyForceFullHeight(force) } },
            onReady: { [weak self] in
                MainActor.assumeIsolated {
                    // Fade the skeleton over the rendered form, then remove it — removing
                    // immediately would cut the crossfade and make the handoff feel jerky.
                    guard let self, let fadingSkeleton = self.skeleton else { return }
                    self.skeleton = nil
                    fadingSkeleton.stop()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                        fadingSkeleton.removeFromSuperview()
                    }
                }
            },
            sendToWebView: { [weak self] message in MainActor.assumeIsolated { self?.webView?.sendToWebView(message) } },
            redirectOpener: redirectBrowser,
            openExternal: { [weak self] url in self?.redirectBrowser.openExternal(url) }
        )
        newWebView.bridge = newBridge
        newWebView.onUnrecoverableFailure = { [weak self] reason in
            NSLog("[Encatch] clearing inline form: \(reason)")
            Encatch.shared.setFormVisible(false)
            self?.clearForm()
        }
        newWebView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(newWebView)
        NSLayoutConstraint.activate([
            newWebView.topAnchor.constraint(equalTo: topAnchor),
            newWebView.bottomAnchor.constraint(equalTo: bottomAnchor),
            newWebView.leadingAnchor.constraint(equalTo: leadingAnchor),
            newWebView.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])

        newBridge.setFormPayload(payload)
        webView = newWebView
        bridge = newBridge

        let activeMode = applyInlineAppearance(payload: payload)

        // Shimmer placeholder (same as :android's FormWebViewSkeleton) until `form:ready`.
        let loadingSkeleton = FormWebViewSkeletonView()
        loadingSkeleton.translatesAutoresizingMaskIntoConstraints = false
        addSubview(loadingSkeleton)
        NSLayoutConstraint.activate([
            loadingSkeleton.topAnchor.constraint(equalTo: topAnchor),
            loadingSkeleton.bottomAnchor.constraint(equalTo: bottomAnchor),
            loadingSkeleton.leadingAnchor.constraint(equalTo: leadingAnchor),
            loadingSkeleton.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
        loadingSkeleton.start(activeMode: activeMode)
        skeleton = loadingSkeleton

        applyHeight(300)
        Encatch.shared.setFormVisible(true)

        let url = buildFormWebViewUrl(
            webHost: Encatch.shared.webHost,
            formId: payload.formId,
            instanceKey: webViewInstanceKey,
            debugMode: Encatch.shared.debugMode,
            presentation: "inline"
        )
        newWebView.loadFormUrl(url)
    }

    /// Returns the resolved active mode ("light"/"dark") so callers can theme the skeleton.
    @discardableResult
    private func applyInlineAppearance(payload: ShowFormPayload) -> String {
        let appearanceProperties = payload.formConfig.appearanceProperties
        let corners = resolveCornersFromFormConfig(appearanceProperties)

        let systemScheme = resolveSystemColorScheme(isSystemDark: isSystemDark(traitCollection))
        let shareableMode = extractShareableMode(appearanceProperties)
        let activeMode = resolveActiveMode(shareableMode: payload.theme?.wireValue ?? shareableMode, systemScheme: systemScheme)

        let themeJson = extractThemeJsonForMode(appearanceProperties, mode: activeMode)
        let fallbackBg = activeMode == "dark" ? "#1a1a1a" : "#ffffff"
        let backgroundColorStr = getBackgroundColor(themeJson, fallback: fallbackBg)
        let fallbackArgb: Int32 = activeMode == "dark" ? Int32(bitPattern: 0xFF1A1A1A) : Int32(bitPattern: 0xFFFFFFFF)
        let backgroundArgb = parseCssColorToArgb(backgroundColorStr, fallbackArgb: fallbackArgb)

        let radii = getInlineBorderRadii(corners: corners)
        let radiusPoints = CGFloat(resolveCornerRadiusDp(corners))
        backgroundColor = uiColor(fromArgb: backgroundArgb)
        layer.cornerRadius = radii.topLeftDp > 0 ? radiusPoints : 0
        webView?.backgroundColor = .clear
        return activeMode
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
        setShellHeight(resolved, animated: true)
    }

    private func applyForceFullHeight(_ force: Bool) {
        overlayActive = force
        if force {
            let base = contentHeight > 0 ? resolveContentHeight(contentHeight) : (minHeight > 0 ? minHeight : 300)
            let maxHeight = (window?.bounds.height ?? UIScreen.main.bounds.height) * 0.8
            let frozen = min(base, maxHeight)
            overlayFrozenHeight = frozen
            setShellHeight(frozen)
        } else {
            overlayFrozenHeight = nil
            setShellHeight(contentHeight > 0 ? contentHeight : (minHeight > 0 ? minHeight : 300))
        }
        onOverlayOpenChange?(force)
    }

    /// Single funnel for every height change: drives the Auto Layout constraint, refreshes
    /// `intrinsicContentSize` for hosts that size by intrinsics, and notifies `onHeightChange`.
    /// Bridge-driven resizes animate so Auto Layout hosts glide instead of snapping (SwiftUI
    /// hosts animate on their side via the `onHeightChange` value).
    private func setShellHeight(_ height: CGFloat, animated: Bool = false) {
        guard heightConstraint?.constant != height else { return }
        heightConstraint?.constant = height
        invalidateIntrinsicContentSize()
        if animated, window != nil {
            UIView.animate(withDuration: 0.3, delay: 0, options: [.curveEaseOut, .beginFromCurrentState]) {
                (self.superview ?? self).layoutIfNeeded()
            }
        }
        onHeightChange?(height)
    }

    public override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: heightConstraint?.constant ?? 0)
    }

    public override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        guard traitCollection.hasDifferentColorAppearance(comparedTo: previousTraitCollection),
              let payload = currentPayload else { return }
        applyInlineAppearance(payload: payload)
    }
}
#endif
