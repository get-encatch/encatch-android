import SwiftUI
import Encatch

private let trackEventPresets = ["button_clicked", "feature_used", "purchase_started", "survey_viewed", "home_viewed"]
private let trackScreenPresets = ["/home", "/dashboard", "/settings", "/dashboard/encatch-test"]

/// Port of `encatch-ios-tester`'s `EventsTabView` — presets are listed upfront as a grouped list
/// (tap a row to fire it immediately), not tucked behind a `Menu` dropdown, so every available
/// preset is visible without an extra click. Each row uses `PresetRowButtonStyle` for hover +
/// press feedback and a trailing chevron, since a bare `.plain`-styled row gives no visual cue
/// under Catalyst that it's actionable.
struct EventsView: View {
    @ObservedObject var state: TesterState
    @State private var customEvent = "test_event"
    @State private var customScreen = "/dashboard/encatch-test"

    var body: some View {
        Form {
            Section("trackEvent Presets") {
                ForEach(trackEventPresets, id: \.self) { preset in
                    Button(action: { state.track(preset) }) {
                        PresetRowLabel(icon: "bolt", text: preset)
                    }
                    .buttonStyle(PresetRowButtonStyle())
                }
            }

            Section("Custom Event") {
                HStack {
                    TextField("event_name", text: $customEvent)
                        .textFieldStyle(.roundedBorder)
                    Button("Fire") { state.track(customEvent.trimmed) }
                        .buttonStyle(.borderedProminent)
                        .disabled(customEvent.trimmed.isEmpty)
                }
            }

            Section("trackScreen Presets") {
                ForEach(trackScreenPresets, id: \.self) { preset in
                    Button(action: { state.trackScreen(preset) }) {
                        PresetRowLabel(icon: "rectangle.on.rectangle", text: preset)
                    }
                    .buttonStyle(PresetRowButtonStyle())
                }
            }

            Section("Custom Screen") {
                HStack {
                    TextField("/path", text: $customScreen)
                        .textFieldStyle(.roundedBorder)
                    Button("Track") { state.trackScreen(customScreen.trimmed) }
                        .buttonStyle(.borderedProminent)
                        .disabled(customScreen.trimmed.isEmpty)
                }
            }
        }
        .formStyle(.grouped)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity, alignment: .center)
        .onAppear { state.trackScreen("Events") }
    }
}

private struct PresetRowLabel: View {
    let icon: String
    let text: String

    var body: some View {
        HStack {
            Image(systemName: icon).foregroundColor(.accentColor).frame(width: 18)
            Text(text)
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundColor(.secondary)
        }
    }
}

/// Hover highlight + press-darken feedback for a full-width list row — `.buttonStyle(.plain)`
/// alone gives no visual cue under Catalyst that a row is clickable.
private struct PresetRowButtonStyle: ButtonStyle {
    @State private var isHovering = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.vertical, 6)
            .padding(.horizontal, 8)
            .contentShape(Rectangle())
            .background(
                RoundedRectangle(cornerRadius: 6)
                    .fill(backgroundColor(pressed: configuration.isPressed))
            )
            .onHover { isHovering = $0 }
    }

    private func backgroundColor(pressed: Bool) -> Color {
        if pressed { return Color.accentColor.opacity(0.25) }
        if isHovering { return Color.accentColor.opacity(0.12) }
        return .clear
    }
}
