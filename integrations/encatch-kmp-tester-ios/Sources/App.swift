import SwiftUI

/// Standalone iOS tester app for `:kmp-sdk`, modeled on `encatch-ios-tester`. No interceptor
/// screen here — `:kmp-sdk`'s `EncatchConfig` has no `onBeforeShowForm` yet (see
/// `encatch-kmp-tester/src/commonMain/.../TesterController.kt`'s doc comment), a known gap
/// relative to the android/ios-native testers.
@main
struct EncatchKmpTesterApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

struct RootView: View {
    @StateObject private var state = TesterState()

    var body: some View {
        Group {
            switch state.screen {
            case .setup:
                SetupView(state: state)
            case .login:
                LoginView(state: state)
            case .home:
                HomeView(state: state)
            case .events:
                EventsView(state: state)
            case .inline:
                InlineView(state: state)
            case .settings:
                SettingsView(state: state)
            case .billing(let route):
                BillingView(route: route, state: state)
            case .routeNotFound(let route):
                RouteNotFoundView(route: route, state: state)
            }
        }
        .onAppear { state.start() }
    }
}
