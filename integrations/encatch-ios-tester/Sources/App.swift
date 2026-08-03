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
        .sheet(isPresented: Binding(
            get: { state.interceptedFormId != nil },
            set: { isPresented in
                if !isPresented { state.resolveInterceptor(allow: false) }
            }
        )) {
            if let formId = state.interceptedFormId {
                InterceptorSheet(formId: formId, onResult: state.resolveInterceptor)
            }
        }
        .onAppear { state.start() }
    }
}
