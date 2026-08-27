import SwiftUI
import Encatch

/// macOS-native sibling to `encatch-ios-tester`, running the same `swift` Swift Package
/// under Mac Catalyst, with a genuinely Mac-native architecture (sidebar nav instead of tabs,
/// Settings as a sidebar destination rather than a standalone Preferences window, etc.).
@main
struct EncatchMacTesterApp: App {
    @StateObject private var state = TesterState()

    init() {
        EncatchFormHost.install()
    }

    var body: some Scene {
        WindowGroup {
            RootShell(state: state)
                .frame(minWidth: 900, minHeight: 600)
                .preferredColorScheme(state.currentTheme.colorScheme)
                .onAppear { state.start() }
        }
        .defaultSize(width: 1100, height: 720)
        .windowResizability(.contentSize)
        .commands {
            CommandGroup(replacing: .appSettings) {
                // This is a tester app, not a shipped product — Settings lives in the sidebar
                // (see SidebarDestination.settings in RootShell.swift) rather than a standalone
                // Preferences window, so Cmd+, just selects that destination.
                Button("Preferences…") { state.sidebarSelection = .settings }
                    .keyboardShortcut(",", modifiers: .command)
            }
            // No multi-window document model in this app — leaving the stock "New Window"
            // enabled with nothing meaningful to do is a common "not a real Mac app" tell.
            CommandGroup(replacing: .newItem) { }
            CommandMenu("Tester") {
                Button("Show Form") { state.showModalForm() }
                    .keyboardShortcut("f", modifiers: [.command, .shift])
                Button("Prefill Answers & Show Form…") { state.showPrefillSheet = true }
                Divider()
                Button("Cycle Theme") { state.cycleTheme() }
                    .keyboardShortcut("t", modifiers: [.command, .shift])
                Divider()
                Button("Change API Key & Setup…") { state.clearSetup() }
            }
        }
    }
}

extension Theme {
    /// Maps the SDK's theme enum (used to color its own WebView-hosted forms) onto SwiftUI's
    /// `.preferredColorScheme`, so cycling the tester's theme button also updates the host app's
    /// own window appearance, not just the SDK content inside it. `nil` for `.system` lets the
    /// window follow the Mac's own appearance setting instead of forcing one.
    var colorScheme: ColorScheme? {
        switch self {
        case .light: return .light
        case .dark: return .dark
        case .system: return nil
        }
    }
}
