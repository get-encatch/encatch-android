import Foundation
import SwiftUI
import EncatchKmpTester

/// Screen state + all SDK calls, mirroring `encatch-ios-tester`'s `TesterState`. The difference:
/// every SDK call here goes through `TesterController.shared` (Kotlin, compiled into
/// `EncatchKmpTester.framework`) instead of `Encatch.shared` (pure Swift) — Kotlin/Native exports
/// `TesterController`'s suspend functions as completion-handler methods, which Swift's importer
/// bridges automatically to `async throws`, so this reads just like calling a native Swift API.
final class TesterState: ObservableObject {
    @Published var screen: Screen
    @Published var tab: TesterTab = .home
    @Published var lastEvent = "No events yet"
    @Published var savedUsers: [TestUser] = []
    @Published var selectedUsername: String?
    @Published var currentTheme = "SYSTEM"
    @Published var blockedForms: [BlockedFormItem] = []
    @Published var openedForm: BlockedFormItem?
    /// Rolling capture of every SDK HTTP call (newest first), fed by TesterController's
    /// flattened setOnNetworkLog passthrough. Only populates in debugMode.
    @Published var networkLogs: [NetworkLogItem] = []
    /// Current keyboard height, tracked so form screens can extend their scrollable area past it
    /// — plain SwiftUI `ScrollView`s don't grow their scroll range to compensate for the keyboard,
    /// so without this, fields near the bottom of a form are unreachable while the keyboard is up.
    @Published var keyboardHeight: CGFloat = 0

    let prefs = TesterPrefs()
    let usersStore = TestUsersStore()
    private var unsubscribe: (() -> Void)?

    init() {
        screen = prefs.isSetupComplete ? .login : .setup
        selectedUsername = prefs.userName
        savedUsers = usersStore.list()
    }

    /// Registered once for the process lifetime, same as a real host app would at startup.
    func start() {
        observeKeyboard()

        // Auto-init on relaunch: with setup already complete the app skips the Setup screen,
        // so init must happen here instead of saveSetupAndInit (identify/showForm silently
        // no-op un-initialized). Parity with encatch-ios-tester's start().
        if prefs.isSetupComplete {
            Task { await initializeSdk() }
        }

        TesterController.shared.setOnNetworkLog { [weak self] status, endpointName, durationMs, fullText in
            DispatchQueue.main.async {
                guard let self else { return }
                self.networkLogs.insert(
                    NetworkLogItem(
                        status: Int(truncating: status),
                        name: endpointName,
                        durationMs: Int(truncating: durationMs),
                        fullText: fullText
                    ),
                    at: 0
                )
                if self.networkLogs.count > 200 { self.networkLogs.removeLast() }
            }
        }

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

    func saveSetupAndInit(environment: TesterEnvironment, apiKey: String, formId: String, interceptorFormId: String) {
        prefs.environment = environment
        prefs.apiKey = apiKey
        prefs.formId = formId
        prefs.apiBaseUrl = environment.apiBaseUrl
        prefs.webHost = environment.webHost
        prefs.interceptorFormId = interceptorFormId.isEmpty ? nil : interceptorFormId

        Task {
            await initializeSdk()
            await MainActor.run { self.screen = .login }
        }
    }

    /// Shared by `saveSetupAndInit` (fresh Setup entry) and `start()` (a plain relaunch that
    /// skips Setup because it was already completed in a prior session).
    private func initializeSdk() async {
        do {
            try await TesterController.shared.doInitSdk(
                apiKey: prefs.apiKey ?? "",
                baseUrl: prefs.apiBaseUrl ?? prefs.environment.apiBaseUrl,
                webHost: prefs.webHost ?? prefs.environment.webHost,
                interceptorFormId: prefs.interceptorFormId,
                // Unconditionally blocks the configured interceptor form id and queues it for
                // the InterceptorCarousel — demonstrates fully replacing the SDK's modal with a
                // custom-rendered native form. Plain (non-async) callback — see
                // TesterController.kt's doc comment for why.
                onIntercept: { [weak self] formId, formConfigJson, completion in
                    DispatchQueue.main.async {
                        self?.blockedForms.append(BlockedFormItem(formId: formId, title: formId, formConfigJson: formConfigJson))
                        _ = completion(KotlinBoolean(bool: false))
                    }
                }
            )
        } catch {
            print("TesterController.doInitSdk failed: \(error)")
        }
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
            Task { try? await TesterController.shared.identify(userName: user.username, email: user.email, displayName: user.displayName) }
        }
    }

    func identify() {
        guard let username = selectedUsername else { return }
        let user = savedUsers.first { $0.username == username }
        Task {
            try? await TesterController.shared.identify(userName: username, email: user?.email, displayName: user?.displayName)
            prefs.userName = username
            await MainActor.run {
                self.screen = .main
                self.tab = .home
            }
        }
    }

    func cycleTheme() {
        currentTheme = TesterController.shared.cycleTheme()
    }

    func showModalForm() {
        guard let formId = prefs.formId else { return }
        showForm(formId)
    }

    func showPrefilledForm() {
        guard let formId = prefs.formId else { return }
        Task { try? await TesterController.shared.showPrefilledForm(formId: formId, questionId: "prefill-question", value: "hello") }
    }

    func showInterceptorForm() {
        guard let formId = prefs.interceptorFormId else { return }
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

    /// Last locale/country applied via the Settings modal — echoed back in the Localization
    /// card so the click has visible feedback (the SDK setters are silent; they only affect
    /// the NEXT showForm).
    @Published var appliedLocale: String?
    @Published var appliedCountry: String?

    func setLocale(_ locale: String) {
        TesterController.shared.setLocale(locale: locale)
        appliedLocale = locale
    }

    func setCountry(_ country: String) {
        TesterController.shared.setCountry(country: country)
        appliedCountry = country
    }

    func logOut() {
        Task {
            try? await TesterController.shared.resetUser()
            await MainActor.run { self.screen = .login }
        }
    }

    func clearSetup() {
        Task {
            try? await TesterController.shared.resetUser()
            prefs.clear()
            await MainActor.run { self.screen = .setup }
        }
    }

    /// Extends `keyboardHeight` on show/hide so form screens can add matching bottom scroll room
    /// (see `View.avoidsKeyboard` in Theme.swift) — SwiftUI's `ScrollView` doesn't do this itself,
    /// so without it a field near the bottom of a long form has nowhere to scroll to once the
    /// keyboard covers it.
    private func observeKeyboard() {
        NotificationCenter.default.addObserver(
            forName: UIResponder.keyboardWillChangeFrameNotification, object: nil, queue: .main
        ) { [weak self] notification in
            guard let self, let endFrame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
            let screenHeight = UIScreen.main.bounds.height
            let height = max(0, screenHeight - endFrame.origin.y)
            withAnimation(.easeOut(duration: 0.25)) { self.keyboardHeight = height }
        }
        NotificationCenter.default.addObserver(
            forName: UIResponder.keyboardWillHideNotification, object: nil, queue: .main
        ) { [weak self] _ in
            withAnimation(.easeOut(duration: 0.25)) { self?.keyboardHeight = 0 }
        }
    }
}
