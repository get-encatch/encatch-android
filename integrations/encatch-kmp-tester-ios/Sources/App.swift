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
