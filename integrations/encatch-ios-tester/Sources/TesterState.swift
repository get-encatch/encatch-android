import Foundation
import Encatch

/// Screen state + all SDK calls the tester UI makes, mirroring `encatch-android-tester`'s
/// `MainActivity`/`TesterPrefs` split.
final class TesterState: ObservableObject {
    @Published var screen: Screen
    @Published var lastEvent = "No events yet"
    @Published var interceptedFormId: String?

    let prefs = TesterPrefs()
    private var interceptorResume: ((Bool) -> Void)?

    init() {
        screen = prefs.isSetupComplete ? .login : .setup
    }

    /// Registered once for the process lifetime, same as a real host app would at startup.
    func start() {
        _ = Encatch.shared.on { [weak self] eventType, payload in
            DispatchQueue.main.async {
                guard let self else { return }
                self.lastEvent = "\(eventType.wireValue) (formId=\(payload.formId ?? "nil"))"
                guard eventType == .formCtaTriggered else { return }
                let action = self.stringValue(payload.data?["action"])
                let route = self.stringValue(payload.data?["route"])
                guard action == "app_navigate" else { return }
                if route == "billing" || route == "billing/upgrade" {
                    self.screen = .billing(route: route ?? "billing")
                } else {
                    self.screen = .routeNotFound(route: route ?? "(none)")
                }
            }
        }
    }

    private func stringValue(_ value: JSONValue?) -> String? {
        if case .string(let s)? = value { return s }
        return nil
    }

    func saveSetupAndInit(apiKey: String, formId: String, baseUrl: String, webHost: String, interceptorFormId: String) {
        prefs.apiKey = apiKey
        prefs.formId = formId
        prefs.apiBaseUrl = baseUrl.isEmpty ? nil : baseUrl
        prefs.webHost = webHost.isEmpty ? nil : webHost
        prefs.interceptorFormId = interceptorFormId.isEmpty ? nil : interceptorFormId

        Task {
            let config = EncatchConfig(
                apiBaseUrl: prefs.apiBaseUrl ?? DEFAULT_API_BASE_URL,
                webHost: prefs.webHost ?? DEFAULT_WEB_HOST,
                debugMode: true,
                // Demonstrates the blocked-form / native-replacement pattern: any showForm() call
                // for the configured interceptor form id is held until the tester answers the
                // InterceptorSheet.
                onBeforeShowForm: { [weak self] payload in
                    guard let self, payload.formId == self.prefs.interceptorFormId else { return true }
                    return await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
                        DispatchQueue.main.async {
                            self.interceptedFormId = payload.formId
                            self.interceptorResume = { allow in continuation.resume(returning: allow) }
                        }
                    }
                }
            )
            do {
                try await Encatch.shared.initialize(apiKey: apiKey, config: config)
            } catch {
                print("Encatch.initialize failed: \(error)")
            }
            await MainActor.run { self.screen = .login }
        }
    }

    func resolveInterceptor(allow: Bool) {
        interceptorResume?(allow)
        interceptorResume = nil
        interceptedFormId = nil
    }

    func logIn(userName: String) {
        prefs.userName = userName
        Task {
            try? await Encatch.shared.identifyUser(userName: userName)
            await MainActor.run { self.screen = .home }
        }
    }

    func showModalForm() {
        guard let formId = prefs.formId else { return }
        showForm(formId)
    }

    func showInterceptorForm() {
        guard let formId = prefs.interceptorFormId else { return }
        showForm(formId)
    }

    func showForm(_ formId: String) {
        Task { try? await Encatch.shared.showForm(formId) }
    }

    func track(_ eventName: String) {
        Task { try? await Encatch.shared.trackEvent(eventName) }
    }

    func trackScreen(_ name: String) {
        Task { try? await Encatch.shared.trackScreen(name) }
    }

    func trackHomeViewed() {
        Task {
            try? await Encatch.shared.trackScreen("Home")
            try? await Encatch.shared.trackEvent("home_viewed")
        }
    }

    func logOut() {
        Task {
            try? await Encatch.shared.resetUser()
            await MainActor.run { self.screen = .login }
        }
    }

    func clearSetup() {
        Task {
            try? await Encatch.shared.clearAll()
            prefs.clear()
            await MainActor.run { self.screen = .setup }
        }
    }
}
