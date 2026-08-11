import SwiftUI

/// Standalone iOS tester app for `:kmp-sdk`, modeled on `encatch-ios-tester`.
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
            case .editProfile(let username):
                EditProfileView(username: username, state: state)
            case .main:
                MainTabView(state: state)
            case .billing(let route):
                BillingView(route: route, state: state)
            case .routeNotFound(let route):
                RouteNotFoundView(route: route, state: state)
            }
        }
        .onAppear { state.start() }
    }
}
