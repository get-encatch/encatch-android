import SwiftUI

/// Shared visual language for the tester app, styled after the modern Uber rider app: monochrome
/// black/white palette that inverts cleanly in dark mode, bold typography, pill-shaped buttons
/// and chips (the "Where to?" idiom), and softly rounded flat gray tiles. Pure presentation — no
/// SDK calls live here. Everything targets iOS 15.
enum TesterTheme {
    /// Primary "ink": black in light mode, white in dark mode (Uber's monochrome brand color).
    static let accent = Color(.label)
    static let accentSoft = Color(.label).opacity(0.08)
    /// Uber's safety green — used sparingly for positive/selected states.
    static let green = Color(red: 0.02, green: 0.76, blue: 0.40)
    static let cornerRadius: CGFloat = 16
}

// MARK: - Surfaces

/// Flat gray card, Uber-style: no border, no shadow, squared corners.
struct CardBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: TesterTheme.cornerRadius, style: .continuous))
    }
}

extension View {
    func card() -> some View { modifier(CardBackground()) }
}

/// Plain system background — Uber screens are white/black, not grouped gray.
struct ScreenBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(.systemBackground).ignoresSafeArea())
    }
}

extension View {
    func screenBackground() -> some View { modifier(ScreenBackground()) }
}

extension View {
    /// Adds bottom scroll room equal to the keyboard height, so a `ScrollView` can be scrolled far
    /// enough that fields near the bottom of a form clear the keyboard instead of staying hidden
    /// behind it. Apply to a `ScrollView`'s content alongside `TesterState.keyboardHeight`.
    func avoidsKeyboard(_ height: CGFloat) -> some View {
        safeAreaInset(edge: .bottom) { Color.clear.frame(height: height) }
    }
}

// MARK: - Buttons

/// Solid ink pill button: black on white (inverts in dark mode), fully rounded, bold label.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.bold))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(isEnabled ? Color(.label) : Color(.systemGray4))
            .foregroundColor(Color(.systemBackground))
            .clipShape(Capsule())
            .opacity(configuration.isPressed ? 0.8 : 1)
            .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
    }
}

/// Gray fill pill button with ink label — Uber's secondary action.
struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color(.secondarySystemBackground))
            .foregroundColor(Color(.label))
            .clipShape(Capsule())
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

/// Quiet full-width row button for tertiary actions (e.g. "Change API key & setup").
struct QuietButtonStyle: ButtonStyle {
    var role: Color = .secondary

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .foregroundColor(role)
            .opacity(configuration.isPressed ? 0.5 : 1)
    }
}

/// Compact pill chip for preset buttons on the Events tab.
struct ChipButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Color(.secondarySystemBackground))
            .foregroundColor(.primary)
            .clipShape(Capsule())
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

// MARK: - Small pieces

/// Bold section title above a card — Uber uses strong headlines, not gray small-caps.
struct SectionHeader: View {
    let title: String
    var icon: String? = nil

    var body: some View {
        Text(title)
            .font(.headline.weight(.bold))
            .foregroundColor(.primary)
            .padding(.horizontal, 2)
    }
}

/// Field caption inside a card, above a text field.
struct FieldLabel: View {
    let text: String
    var required = false

    var body: some View {
        HStack(spacing: 2) {
            Text(text).font(.footnote.weight(.semibold)).foregroundColor(.primary)
            if required { Text("*").font(.footnote.weight(.bold)).foregroundColor(.primary) }
        }
    }
}

/// Flat gray filled text-field, softly rounded like Uber's "Where to?" input.
struct FilledFieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// Circle avatar with the user's initials — ink on light gray, monochrome.
struct InitialsAvatar: View {
    let name: String
    var size: CGFloat = 40

    private var initials: String {
        let parts = name.split(separator: " ").prefix(2).compactMap { $0.first }
        if parts.isEmpty { return "?" }
        return String(parts).uppercased()
    }

    var body: some View {
        Text(initials)
            .font(.system(size: size * 0.4, weight: .bold))
            .foregroundColor(Color(.systemBackground))
            .frame(width: size, height: size)
            .background(Color(.label))
            .clipShape(Circle())
    }
}

/// Key–value row used on Settings / detail cards.
struct InfoRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).font(.subheadline).foregroundColor(.secondary)
            Spacer(minLength: 16)
            Text(value)
                .font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
        }
        .padding(.vertical, 2)
    }
}

/// Brand mark used on Setup/Login heroes: white glyph on an ink circle, Uber-flat.
struct BrandMark: View {
    var size: CGFloat = 56

    var body: some View {
        Image(systemName: "bubble.left.and.text.bubble.right.fill")
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundColor(Color(.systemBackground))
            .frame(width: size, height: size)
            .background(Color(.label))
            .clipShape(Circle())
    }
}
