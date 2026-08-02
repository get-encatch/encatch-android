import Foundation

/// Appearance/layout resolution, ported from `form-webview-helpers.ts`. Pure functions operating
/// on the form's `appearanceProperties` JSON — shared by every UI layer so position/corner/
/// animation semantics can't drift between them.

// ============================================================================
// Corner / size / position enums
// ============================================================================

public enum CornerStyle: Equatable { case sharp, soft, round }

public enum InAppSize: Equatable { case compact, standard, spacious }

/// Matches iframe-manager mobile breakpoint (window width < 600dp).
public let IN_APP_MOBILE_BREAKPOINT_DP = 600

public struct PopupBorderRadii: Equatable {
    public var topLeftDp: Int
    public var topRightDp: Int
    public var bottomLeftDp: Int
    public var bottomRightDp: Int

    public init(topLeftDp: Int, topRightDp: Int, bottomLeftDp: Int, bottomRightDp: Int) {
        self.topLeftDp = topLeftDp
        self.topRightDp = topRightDp
        self.bottomLeftDp = bottomLeftDp
        self.bottomRightDp = bottomRightDp
    }
}

public enum VerticalAnchor: Equatable { case top, center, bottom }
public enum HorizontalAnchor: Equatable { case start, center, end }

/// Platform-neutral alignment — UIKit code maps this to `UIStackView`/constraint anchors.
public struct PositionAlignment: Equatable {
    public var vertical: VerticalAnchor
    public var horizontal: HorizontalAnchor

    public init(vertical: VerticalAnchor, horizontal: HorizontalAnchor) {
        self.vertical = vertical
        self.horizontal = horizontal
    }
}

public struct AnimationConfig: Equatable {
    public var type: String
    public var txFractionPercent: Int
    public var tyFractionPercent: Int

    public init(type: String, txFractionPercent: Int, tyFractionPercent: Int) {
        self.type = type
        self.txFractionPercent = txFractionPercent
        self.tyFractionPercent = tyFractionPercent
    }
}

private extension JSONValue {
    func obj(_ key: String) -> JSONValue? {
        guard case .object(let object) = self else { return nil }
        return object[key]
    }

    func str(_ key: String) -> String? {
        guard case .object(let object) = self, case .string(let value)? = object[key] else { return nil }
        return value
    }

    func bool(_ key: String) -> Bool? {
        guard case .object(let object) = self, case .bool(let value)? = object[key] else { return nil }
        return value
    }

    func number(_ key: String) -> Double? {
        guard case .object(let object) = self, case .number(let value)? = object[key] else { return nil }
        return value
    }
}

/// Maps corners preset to dp (24dp = 1.5rem for round), aligned with App.svelte resolveRadius().
public func resolveCornerRadiusDp(_ corners: CornerStyle) -> Int {
    switch corners {
    case .sharp: return 2
    case .round: return 24
    case .soft: return 10
    }
}

/// Reads appearance.appearance.corners with legacy featureSettings.corners fallback.
public func resolveCornersFromFormConfig(_ appearanceProperties: JSONValue?) -> CornerStyle {
    let value = appearanceProperties?.obj("appearance")?.str("corners")
        ?? appearanceProperties?.obj("featureSettings")?.str("corners")
    switch value {
    case "sharp": return .sharp
    case "round": return .round
    default: return .soft
    }
}

/// Reads inApp.size with legacy featureSettings.inAppSize fallback.
public func resolveInAppSizeFromFormConfig(_ appearanceProperties: JSONValue?) -> InAppSize {
    let value = appearanceProperties?.obj("inApp")?.str("size")
        ?? appearanceProperties?.obj("featureSettings")?.str("inAppSize")
    switch value {
    case "compact": return .compact
    case "spacious": return .spacious
    default: return .standard
    }
}

/// Reads inApp.position with legacy selectedPosition fallback.
public func resolveSelectedPositionFromFormConfig(_ appearanceProperties: JSONValue?) -> String {
    appearanceProperties?.obj("inApp")?.str("position")
        ?? appearanceProperties?.str("selectedPosition")
        ?? "middle-center"
}

/// Reads inApp.darkOverlay with legacy featureSettings.darkOverlay fallback.
public func resolveDarkOverlayFromFormConfig(_ appearanceProperties: JSONValue?) -> Bool {
    (appearanceProperties?.obj("inApp")?.bool("darkOverlay")
        ?? appearanceProperties?.obj("featureSettings")?.bool("darkOverlay")) == true
}

/// Reads featureSettings.maxDialogHeightPercentInApp, defaulting to 0.8 (80%).
public func resolveMaxDialogHeightFraction(_ appearanceProperties: JSONValue?) -> Double {
    if let raw = appearanceProperties?.obj("featureSettings")?.number("maxDialogHeightPercentInApp") {
        return raw / 100.0
    }
    return 0.8
}

public func isMobileLayout(screenWidthDp: Int) -> Bool {
    screenWidthDp < IN_APP_MOBILE_BREAKPOINT_DP
}

/// Collapse left/right anchors to center on mobile — matches iframe-manager normalizePosition().
public func normalizePosition(_ position: String, screenWidthDp: Int) -> String {
    if position == "full-center" || position == "full" { return "full-center" }
    if !isMobileLayout(screenWidthDp: screenWidthDp) { return position }
    if position.hasPrefix("top") { return "top-center" }
    if position.hasPrefix("bottom") { return "bottom-center" }
    return "middle-center"
}

public func isCenterAlignedPosition(_ position: String) -> Bool {
    position.hasSuffix("-center") || position == "center"
}

/// Popup shell max-width in dp — aligned with iframe-manager getInAppMaxWidth().
public func resolveInAppMaxWidthDp(
    size: InAppSize,
    position: String,
    screenWidthDp: Int,
    horizontalInsetDp: Int = 0
) -> Int {
    let available = max(screenWidthDp - horizontalInsetDp * 2, 100)
    if position == "full-center" { return available }

    let centered = isCenterAlignedPosition(position)
    let presetWidth: Int
    if centered {
        switch size {
        case .compact: presetWidth = 480
        case .spacious: presetWidth = 720
        case .standard: presetWidth = 600
        }
    } else {
        switch size {
        case .compact: presetWidth = 320
        case .spacious: presetWidth = 500
        case .standard: presetWidth = 400
        }
    }
    return min(presetWidth, available)
}

public func getPositionLayout(_ position: String) -> PositionAlignment {
    var vertical = VerticalAnchor.center
    var horizontal = HorizontalAnchor.center

    if position.hasPrefix("top") {
        vertical = .top
    } else if position.hasPrefix("bottom") {
        vertical = .bottom
    }

    if position.hasSuffix("left") {
        horizontal = .start
    } else if position.hasSuffix("right") {
        horizontal = .end
    }

    return PositionAlignment(vertical: vertical, horizontal: horizontal)
}

/// Per-corner radii for the modal shell. Edges that touch the screen stay square (0);
/// other corners use the resolved corners preset radius.
public func getBorderRadii(_ position: String, corners: CornerStyle = .soft) -> PopupBorderRadii {
    if position == "full-center" || position == "full" {
        return PopupBorderRadii(topLeftDp: 0, topRightDp: 0, bottomLeftDp: 0, bottomRightDp: 0)
    }

    let radius = resolveCornerRadiusDp(corners)
    let touchesTop = position.contains("top")
    let touchesBottom = position.contains("bottom")
    let touchesLeft = position.hasSuffix("left")
    let touchesRight = position.hasSuffix("right")

    return PopupBorderRadii(
        topLeftDp: (touchesTop || touchesLeft) ? 0 : radius,
        topRightDp: (touchesTop || touchesRight) ? 0 : radius,
        bottomLeftDp: (touchesBottom || touchesLeft) ? 0 : radius,
        bottomRightDp: (touchesBottom || touchesRight) ? 0 : radius
    )
}

/// Uniform radii for inline embeds — matches web-sdk iframe-manager inline innerWrapper.
public func getInlineBorderRadii(corners: CornerStyle = .soft) -> PopupBorderRadii {
    let radius = resolveCornerRadiusDp(corners)
    return PopupBorderRadii(topLeftDp: radius, topRightDp: radius, bottomLeftDp: radius, bottomRightDp: radius)
}

public func getAnimationConfig(_ position: String) -> AnimationConfig {
    if position.hasPrefix("top") { return AnimationConfig(type: "slide", txFractionPercent: 0, tyFractionPercent: -100) }
    if position.hasPrefix("bottom") { return AnimationConfig(type: "slide", txFractionPercent: 0, tyFractionPercent: 100) }
    if position.hasSuffix("left") { return AnimationConfig(type: "slide", txFractionPercent: -100, tyFractionPercent: 0) }
    if position.hasSuffix("right") { return AnimationConfig(type: "slide", txFractionPercent: 100, tyFractionPercent: 0) }
    return AnimationConfig(type: "scale", txFractionPercent: 0, tyFractionPercent: 0)
}

public func dpToPxInt(dp: Int, density: Float) -> Int {
    Int((Float(dp) * density).rounded())
}
