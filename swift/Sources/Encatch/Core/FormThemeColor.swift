import Foundation

/// Color resolution for form theming, ported from `form-webview-helpers.ts`. The web form's
/// theme JSON stores shadcn CSS variables (hex/rgb/rgba/hsl/hsla, occasionally oklch which we
/// can't render and fall back from) — these functions extract and normalize them, then
/// `parseCssColorToArgb` converts the normalized string into a packed ARGB `Int32` (compatible
/// with `UIColor`'s RGBA packing once unpacked via `uiColor(fromArgb:)`).

private let hexRegex = try! NSRegularExpression(pattern: "^#[0-9A-Fa-f]{3,8}$")
private let rgbFnRegex = try! NSRegularExpression(pattern: "^(rgb|rgba|hsl|hsla)\\(", options: [.caseInsensitive])
private let rgbaMatchRegex = try! NSRegularExpression(
    pattern: #"^rgba\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([\d.]+)\s*\)$"#,
    options: [.caseInsensitive]
)
private let rgbMatchRegex = try! NSRegularExpression(
    pattern: #"^rgb\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$"#,
    options: [.caseInsensitive]
)
private let hslaMatchRegex = try! NSRegularExpression(
    pattern: #"^hsla?\s*\(\s*([\d.]+)\s*,?\s*([\d.]+)%\s*,?\s*([\d.]+)%\s*(?:[,/]\s*([\d.]+))?\s*\)$"#,
    options: [.caseInsensitive]
)
private let hexMatchRegex = try! NSRegularExpression(pattern: "^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

/// Returns capture group strings (index 0 = whole match) for the first match of `regex` in `s`,
/// or nil if no match. Mirrors Kotlin `Regex.find(s)?.groupValues`.
private func groupValues(_ regex: NSRegularExpression, in s: String) -> [String]? {
    let nsrange = NSRange(s.startIndex..<s.endIndex, in: s)
    guard let match = regex.firstMatch(in: s, options: [], range: nsrange) else { return nil }
    var groups: [String] = []
    for i in 0..<match.numberOfRanges {
        let range = match.range(at: i)
        if range.location == NSNotFound {
            groups.append("")
        } else if let r = Range(range, in: s) {
            groups.append(String(s[r]))
        } else {
            groups.append("")
        }
    }
    return groups
}

private func matches(_ regex: NSRegularExpression, _ s: String) -> Bool {
    let nsrange = NSRange(s.startIndex..<s.endIndex, in: s)
    return regex.firstMatch(in: s, options: [], range: nsrange) != nil
}

public let DEFAULT_OVERLAY_RGBA = "rgba(0, 0, 0, 0.5)"

/// Matches shareable encatch.ts OVERLAY_OPACITY fallback for colors without explicit alpha.
public let OVERLAY_FALLBACK_ALPHA = 0.4

public func hexWithAlpha(_ hex: String, alphaHex: String = "4D") -> String {
    var h = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
    if h.count == 3 {
        h = h.map { "\($0)\($0)" }.joined()
    }
    switch h.count {
    case 6: return "#\(h)\(alphaHex)"
    case 8: return "#\(h)"
    default: return "#000000\(alphaHex)"
    }
}

/// Only hex and rgb(a)/hsl(a) function colors are safe to hand to a real color renderer.
private func isRenderableColor(_ value: String) -> Bool {
    let v = value.trimmingCharacters(in: .whitespaces)
    if matches(hexRegex, v) { return true }
    let nsrange = NSRange(v.startIndex..<v.endIndex, in: v)
    if let match = rgbFnRegex.firstMatch(in: v, options: [], range: nsrange) {
        return match.range.location == 0
    }
    return false
}

public func normalizeColorForNative(_ value: String?, fallback: String) -> String {
    guard let value, !value.trimmingCharacters(in: .whitespaces).isEmpty else { return fallback }
    let trimmed = value.trimmingCharacters(in: .whitespaces)
    return isRenderableColor(trimmed) ? trimmed : fallback
}

/// Extracts --background (falling back to --popover) from the shadcn-variables JSON string
/// stored in themes[mode].theme and normalizes it to a renderable color value.
public func getBackgroundColor(_ themeJson: String?, fallback: String) -> String {
    guard let themeJson, !themeJson.isEmpty, themeJson != "{}" else { return fallback }
    guard let vars = parseLenientJsonStringMap(themeJson) else { return fallback }
    let value = vars["--background"] ?? vars["--popover"]
    guard let value, !value.isEmpty else { return fallback }
    return normalizeColorForNative(value, fallback: fallback)
}

/// Resolves "dark"/"light" from the platform's current system appearance flag.
public func resolveSystemColorScheme(isSystemDark: Bool) -> String {
    isSystemDark ? "dark" : "light"
}

/// Reads `appearanceProperties.featureSettings.shareableMode`.
public func extractShareableMode(_ appearanceProperties: JSONValue?) -> String? {
    guard case .object(let root)? = appearanceProperties,
          case .object(let featureSettings)? = root["featureSettings"],
          case .string(let mode)? = featureSettings["shareableMode"]
    else { return nil }
    return mode
}

/// Reads `appearanceProperties.themes[mode].theme`.
public func extractThemeJsonForMode(_ appearanceProperties: JSONValue?, mode: String) -> String? {
    guard case .object(let root)? = appearanceProperties,
          case .object(let themes)? = root["themes"],
          case .object(let modeConfig)? = themes[mode],
          case .string(let theme)? = modeConfig["theme"]
    else { return nil }
    return theme
}

/// Resolves which theme mode ("light" | "dark") is active for the form.
public func resolveActiveMode(shareableMode: String?, systemScheme: String) -> String {
    switch shareableMode {
    case "light": return "light"
    case "dark": return "dark"
    default: return systemScheme == "dark" ? "dark" : "light"
    }
}

private struct ThemeModeConfig {
    var theme: String?
    var overlayColor: String?
}

/// Overlay base color from theme JSON — aligned with shareable encatch.ts getOverlayColorFromTheme().
private func getOverlayColorFromTheme(_ themeConfig: ThemeModeConfig?) -> String {
    if let overlayColor = themeConfig?.overlayColor {
        return overlayColor
    }
    let themeJson = themeConfig?.theme
    guard let themeJson, !themeJson.isEmpty, themeJson != "{}" else { return DEFAULT_OVERLAY_RGBA }
    guard let vars = parseLenientJsonStringMap(themeJson) else { return DEFAULT_OVERLAY_RGBA }
    return vars["overlayColor"] ?? vars["--encatch-overlay-color"] ?? vars["--overlay"] ?? vars["--popover"] ?? DEFAULT_OVERLAY_RGBA
}

/// Returns rgba(...) for the modal backdrop. Preserves explicit alpha in rgba(...)/#RRGGBBAA;
/// otherwise applies `fallbackAlpha` — same rules as shareable encatch.ts withAlpha().
public func colorWithAlpha(_ color: String, fallbackAlpha: Double = OVERLAY_FALLBACK_ALPHA) -> String {
    let a = min(max(fallbackAlpha, 0.0), 1.0)
    let s = color.trimmingCharacters(in: .whitespaces)

    if let m = groupValues(rgbaMatchRegex, in: s) {
        return "rgba(\(m[1]), \(m[2]), \(m[3]), \(m[4]))"
    }
    if let m = groupValues(rgbMatchRegex, in: s) {
        return "rgba(\(m[1]), \(m[2]), \(m[3]), \(a))"
    }
    if let m = groupValues(hexMatchRegex, in: s) {
        var hex = m[1]
        if hex.count == 3 {
            hex = hex.map { "\($0)\($0)" }.joined()
        }
        let r = Int(hex.prefix(2), radix: 16) ?? 0
        let g = Int(hex.dropFirst(2).prefix(2), radix: 16) ?? 0
        let b = Int(hex.dropFirst(4).prefix(2), radix: 16) ?? 0
        let alpha: Double
        if hex.count == 8 {
            alpha = Double(Int(hex.dropFirst(6).prefix(2), radix: 16) ?? 255) / 255.0
        } else {
            alpha = a
        }
        return "rgba(\(r), \(g), \(b), \(alpha))"
    }
    return "rgba(0, 0, 0, \(a))"
}

/// Modal backdrop color when darkOverlay is enabled; transparent when disabled. Native UI layers
/// always keep touches captured by the modal shell regardless of overlay visibility.
public func resolveModalOverlayBackgroundColor(
    appearanceProperties: JSONValue?,
    activeMode: String,
    darkOverlay: Bool
) -> String {
    guard darkOverlay else { return "transparent" }

    var theme: String?
    var overlayColor: String?
    if case .object(let root)? = appearanceProperties,
       case .object(let themes)? = root["themes"],
       case .object(let modeConfig)? = themes[activeMode] {
        if case .string(let t)? = modeConfig["theme"] { theme = t }
        if case .string(let o)? = modeConfig["overlayColor"] { overlayColor = o }
    }
    let themeConfig = ThemeModeConfig(theme: theme, overlayColor: overlayColor)
    let base = getOverlayColorFromTheme(themeConfig)
    return normalizeColorForNative(colorWithAlpha(base), fallback: DEFAULT_OVERLAY_RGBA)
}

/// Parses a hex/rgb/rgba/hsl/hsla CSS color string into a packed ARGB `Int32`; `fallbackArgb` on
/// failure.
public func parseCssColorToArgb(_ color: String, fallbackArgb: Int32) -> Int32 {
    if color == "transparent" { return 0x0000_0000 }
    let s = color.trimmingCharacters(in: .whitespaces)

    if matches(hexRegex, s) {
        var hex = s.hasPrefix("#") ? String(s.dropFirst()) : s
        if hex.count == 3 {
            hex = hex.map { "\($0)\($0)" }.joined()
        }
        switch hex.count {
        case 6:
            guard let rgb = Int32(hex, radix: 16) else { return fallbackArgb }
            return Int32(bitPattern: (UInt32(0xFF) << 24)) | rgb
        case 8:
            guard let rgb = UInt32(hex.prefix(6), radix: 16), let alpha = UInt32(hex.suffix(2), radix: 16) else {
                return fallbackArgb
            }
            return Int32(bitPattern: (alpha << 24) | rgb)
        default:
            return fallbackArgb
        }
    }

    if let m = groupValues(rgbaMatchRegex, in: s) {
        let r = UInt32(min(max(Int(m[1]) ?? 0, 0), 255))
        let g = UInt32(min(max(Int(m[2]) ?? 0, 0), 255))
        let b = UInt32(min(max(Int(m[3]) ?? 0, 0), 255))
        let aFrac = min(max(Double(m[4]) ?? 1.0, 0.0), 1.0)
        let a = UInt32((aFrac * 255).rounded())
        return Int32(bitPattern: (a << 24) | (r << 16) | (g << 8) | b)
    }
    if let m = groupValues(rgbMatchRegex, in: s) {
        let r = UInt32(min(max(Int(m[1]) ?? 0, 0), 255))
        let g = UInt32(min(max(Int(m[2]) ?? 0, 0), 255))
        let b = UInt32(min(max(Int(m[3]) ?? 0, 0), 255))
        return Int32(bitPattern: (UInt32(0xFF) << 24) | (r << 16) | (g << 8) | b)
    }
    if let m = groupValues(hslaMatchRegex, in: s) {
        let h = Double(m[1]) ?? 0.0
        let sat = Double(m[2]) ?? 0.0
        let l = Double(m[3]) ?? 0.0
        let aRaw = m.count > 4 && !m[4].isEmpty ? Double(m[4]) ?? 1.0 : 1.0
        let a = min(max(aRaw, 0.0), 1.0)
        let (r, g, b) = hslToRgb(h: h, s: sat / 100.0, l: l / 100.0)
        let alpha = UInt32((a * 255).rounded())
        return Int32(bitPattern: (alpha << 24) | (UInt32(r) << 16) | (UInt32(g) << 8) | UInt32(b))
    }

    return fallbackArgb
}

private func hslToRgb(h: Double, s: Double, l: Double) -> (Int, Int, Int) {
    if s == 0.0 {
        let v = Int((l * 255).rounded())
        return (v, v, v)
    }
    let q = l < 0.5 ? l * (1 + s) : l + s - l * s
    let p = 2 * l - q
    let hk = h.truncatingRemainder(dividingBy: 360) / 360.0

    func hueToRgb(_ pp: Double, _ qq: Double, _ t0: Double) -> Double {
        var t = t0
        if t < 0 { t += 1 }
        if t > 1 { t -= 1 }
        if t < 1.0 / 6 { return pp + (qq - pp) * 6 * t }
        if t < 1.0 / 2 { return qq }
        if t < 2.0 / 3 { return pp + (qq - pp) * (2.0 / 3 - t) * 6 }
        return pp
    }

    let r = Int((hueToRgb(p, q, hk + 1.0 / 3) * 255).rounded())
    let g = Int((hueToRgb(p, q, hk) * 255).rounded())
    let b = Int((hueToRgb(p, q, hk - 1.0 / 3) * 255).rounded())
    return (r, g, b)
}

/// Lenient `"--key": "value"` string-map parse of a shadcn CSS-variables JSON blob. Nil on parse failure.
private func parseLenientJsonStringMap(_ themeJson: String) -> [String: String]? {
    guard case .object(let object)? = JSONValue.parse(themeJson) else { return nil }
    var result: [String: String] = [:]
    for (key, value) in object {
        if case .string(let s) = value {
            result[key] = s
        }
    }
    return result
}
