import Foundation
import EncatchKmpTester

/// Screen state + all SDK calls, mirroring `encatch-ios-tester`'s `TesterState`. The difference:
/// every SDK call here goes through `TesterController.shared` (Kotlin, compiled into
/// `EncatchKmpTester.framework`) instead of `Encatch.shared` (pure Swift) — Kotlin/Native exports
/// `TesterController`'s suspend functions as completion-handler methods, which Swift's importer
/// bridges automatically to `async throws`, so this reads just like calling a native Swift API.
final class TesterState: ObservableObject {
    @Published var screen: Screen
    @Published var lastEvent = "No events yet"

    let prefs = TesterPrefs()
    private var unsubscribe: (() -> Void)?

    init() {
        screen = prefs.isSetupComplete ? .login : .setup
    }

    /// Registered once for the process lifetime, same as a real host app would at startup.
    func start() {
        unsubscribe = TesterController.shared.onEvent { [weak self] eventWireValue, formId, action, route in
            DispatchQueue.main.async {
                guard let self else { return }
                self.lastEvent = "\(eventWireValue) (formId=\(formId ?? "nil"))"
                guard action == "app_navigate" else { return }
                if route == "billing" || route == "billing/upgrade" {
                    self.screen = .billing(route: route ?? "billing")
                } else {
                    self.screen = .routeNotFound(route: route ?? "(none)")
                }
            }
        }
    }

    func saveSetupAndInit(apiKey: String, formId: String, baseUrl: String, webHost: String) {
        prefs.apiKey = apiKey
        prefs.formId = formId
        prefs.apiBaseUrl = baseUrl.isEmpty ? nil : baseUrl
        prefs.webHost = webHost.isEmpty ? nil : webHost

        Task {
            do {
                try await TesterController.shared.doInitSdk(apiKey: apiKey, baseUrl: prefs.apiBaseUrl, webHost: prefs.webHost)
            } catch {
                print("TesterController.doInitSdk failed: \(error)")
            }
            await MainActor.run { self.screen = .login }
        }
    }

    func logIn(userName: String) {
        prefs.userName = userName
        Task {
            try? await TesterController.shared.identify(userName: userName)
            await MainActor.run { self.screen = .home }
        }
    }

    func showModalForm() {
        guard let formId = prefs.formId else { return }
        showForm(formId)
    }

    func showForm(_ formId: String) {
        Task { try? await TesterController.shared.showForm(formId: formId) }
    }

    func track(_ eventName: String) {
        Task { try? await TesterController.shared.trackEvent(name: eventName) }
    }

    func trackScreen(_ name: String) {
        Task { try? await TesterController.shared.trackScreen(name: name) }
    }

    func trackHomeViewed() {
        Task {
            try? await TesterController.shared.trackScreen(name: "Home")
            try? await TesterController.shared.trackEvent(name: "home_viewed")
        }
    }

    func logOut() {
        Task {
            try? await TesterController.shared.resetUser()
            await MainActor.run { self.screen = .login }
        }
    }

    func clearSetup() {
        Task {
            try? await TesterController.shared.clearAll()
            prefs.clear()
            await MainActor.run { self.screen = .setup }
        }
    }
}
