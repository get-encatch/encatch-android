#if canImport(UIKit)
import UIKit

/// UIKit-specific adapters for the platform-neutral appearance types shared via `Core/`
/// (`FormAppearance.swift`/`FormThemeColor.swift`) — mirrors `:android`'s `FormAppearanceAndroid.kt`.

extension PositionAlignment {
    var verticalAnchor: NSLayoutConstraint.Attribute {
        switch vertical {
        case .top: return .top
        case .bottom: return .bottom
        case .center: return .centerY
        }
    }

    var horizontalAnchor: NSLayoutConstraint.Attribute {
        switch horizontal {
        case .start: return .leading
        case .end: return .trailing
        case .center: return .centerX
        }
    }
}

/// Converts a packed ARGB `Int32` (as produced by `parseCssColorToArgb`) into a `UIColor`.
func uiColor(fromArgb argb: Int32) -> UIColor {
    let value = UInt32(bitPattern: argb)
    let alpha = CGFloat((value >> 24) & 0xFF) / 255.0
    let red = CGFloat((value >> 16) & 0xFF) / 255.0
    let green = CGFloat((value >> 8) & 0xFF) / 255.0
    let blue = CGFloat(value & 0xFF) / 255.0
    return UIColor(red: red, green: green, blue: blue, alpha: alpha)
}

func isSystemDark(_ traitCollection: UITraitCollection) -> Bool {
    traitCollection.userInterfaceStyle == .dark
}
#endif
