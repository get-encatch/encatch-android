import SwiftUI

/// Mac-native presentation helpers — deliberately NOT a port of `encatch-ios-tester`'s
/// `Theme.swift`. That file is an explicitly mobile-consumer design language (its own doc
/// comment: "styled after the modern Uber rider app... pill-shaped buttons"); reusing it verbatim
/// under Catalyst is exactly what would make this app read as a stretched iPhone screen. Instead
/// this leans on stock macOS system controls — `.borderedProminent`/`.bordered` buttons,
/// `.roundedBorder` fields, `Form(.formStyle(.grouped))` + `Section` for grouped layout,
/// `LabeledContent` for key/value rows — so most screens need no custom style at all. The few
/// pieces below are the ones with no direct system equivalent.
enum MacTheme {
    /// Uber safety green kept only for the Logs destination's success/error status coloring —
    /// everywhere else prefers `Color.accentColor` (the user's own system accent color) over a
    /// hardcoded brand color, since that's a genuinely Mac-native signal an iOS app wouldn't have.
    static let success = Color.green
    static let failure = Color.red
}

/// Circle avatar with the user's initials, used on the Login step of onboarding — kept from the
/// iOS app's concept but recolored to the system accent color instead of a hardcoded ink value.
struct InitialsAvatar: View {
    let name: String
    var size: CGFloat = 36

    private var initials: String {
        let parts = name.split(separator: " ").prefix(2).compactMap { $0.first }
        return parts.isEmpty ? "?" : String(parts).uppercased()
    }

    var body: some View {
        Text(initials)
            .font(.system(size: size * 0.4, weight: .semibold))
            .foregroundColor(.white)
            .frame(width: size, height: size)
            .background(Color.accentColor)
            .clipShape(Circle())
    }
}

/// Glyph-on-circle hero mark for the onboarding sheet's first step — same idea as the iOS app's
/// `BrandMark`, recolored to the system accent color.
struct BrandMark: View {
    var size: CGFloat = 48

    var body: some View {
        Image(systemName: "bubble.left.and.text.bubble.right.fill")
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundColor(.white)
            .frame(width: size, height: size)
            .background(Color.accentColor)
            .clipShape(Circle())
    }
}
