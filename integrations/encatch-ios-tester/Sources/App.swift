import SwiftUI
import Encatch

/// Standalone iOS tester app, modeled on `encatch-android-tester`: a runtime Setup screen so one
/// build works for any tester/environment, then Login/Home/Events/Inline/Settings screens
/// exercising the SDK's public API, plus CTA-driven in-app navigation (Billing / route-not-found).
@main
struct EncatchTesterApp: App {
    init() {
        EncatchFormHost.install()
    }

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
