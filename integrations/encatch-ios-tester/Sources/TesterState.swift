import Foundation
import Encatch

/// Screen state + all SDK calls the tester UI makes, mirroring `encatch-android-tester`'s
/// `MainActivity`/`TesterPrefs` split.
final class TesterState: ObservableObject {
    @Published var screen: Screen
    @Published var tab: TesterTab = .home
    @Published var lastEvent = "No events yet"
    @Published var savedUsers: [TestUser] = []
    @Published var selectedUsername: String?
    @Published var currentTheme: Theme = .system
    @Published var blockedForms: [BlockedFormItem] = []
    @Published var openedForm: BlockedFormItem?

    let prefs = TesterPrefs()
    let usersStore = TestUsersStore()

    init() {
        screen = prefs.isSetupComplete ? .login : .setup
        selectedUsername = prefs.userName
        savedUsers = usersStore.list()
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

    func saveSetupAndInit(environment: TesterEnvironment, apiKey: String, formId: String, interceptorFormId: String) {
        prefs.environment = environment
        prefs.apiKey = apiKey
        prefs.formId = formId
        prefs.apiBaseUrl = environment.apiBaseUrl
        prefs.webHost = environment.webHost
        prefs.interceptorFormId = interceptorFormId.isEmpty ? nil : interceptorFormId

        Task {
            let config = EncatchConfig(
                apiBaseUrl: environment.apiBaseUrl,
                webHost: environment.webHost,
                debugMode: true,
                // Unconditionally blocks the configured interceptor form id and queues it for the
                // InterceptorCarousel — demonstrates fully replacing the SDK's modal with a
                // custom-rendered native form.
                onBeforeShowForm: { [weak self] payload in
                    guard let self, payload.formId == self.prefs.interceptorFormId else { return true }
                    let title = self.formTitle(from: payload.formConfig) ?? payload.formId
                    await MainActor.run {
                        self.blockedForms.append(BlockedFormItem(
                            formId: payload.formId,
                            title: title,
                            questionnaireFields: payload.formConfig.questionnaireFields
                        ))
                    }
                    return false
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

    private func formTitle(from formConfig: ShowFormResponse) -> String? {
        if case .string(let title)? = formConfig.formConfiguration?["formTitle"] { return title }
        return nil
    }

    func dismissBlockedForm(_ formId: String) {
        blockedForms.removeAll { $0.formId == formId }
    }

    func selectUser(_ user: TestUser) {
        selectedUsername = user.username
    }

    func saveNewUser(_ user: TestUser) {
        usersStore.add(user)
        savedUsers = usersStore.list()
        selectedUsername = user.username
    }

    func updateUser(_ user: TestUser) {
        usersStore.update(user)
        savedUsers = usersStore.list()
        if selectedUsername == user.username {
            Task { try? await Encatch.shared.identifyUser(userName: user.username, traits: user.toTraits()) }
        }
    }

    func identify() {
        guard let username = selectedUsername else { return }
        let user = savedUsers.first { $0.username == username }
        Task {
            try? await Encatch.shared.identifyUser(userName: username, traits: user?.toTraits())
            prefs.userName = username
            await MainActor.run {
                self.screen = .main
                self.tab = .home
            }
        }
    }

    func cycleTheme() {
        currentTheme = {
            switch currentTheme {
            case .system: return .light
            case .light: return .dark
            case .dark: return .system
            }
        }()
        Encatch.shared.setTheme(currentTheme)
    }

    func showModalForm() {
        guard let formId = prefs.formId else { return }
        showForm(formId)
    }

    func showPrefilledForm() {
        guard let formId = prefs.formId else { return }
        Encatch.shared.addToResponse(questionId: "prefill-question", value: "hello")
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

    func setLocaleFrFr() {
        Encatch.shared.setLocale("fr-FR")
    }

    func setCountryFr() {
        Encatch.shared.setCountry("FR")
    }

    func logOut() {
        Task {
            try? await Encatch.shared.resetUser()
            await MainActor.run { self.screen = .login }
        }
    }

    func clearSetup() {
        Task {
            try? await Encatch.shared.resetUser()
            prefs.clear()
            await MainActor.run { self.screen = .setup }
        }
    }
}

private extension TestUser {
    func toTraits() -> UserTraits? {
        var fields: [String: JSONValue] = [:]
        if !email.isEmpty { fields["email"] = .string(email) }
        if !displayName.isEmpty { fields["display_name"] = .string(displayName) }
        return fields.isEmpty ? nil : UserTraits(set: fields)
    }
}
