import Foundation

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

/// Environment presets for the Setup screen — a pure tester-app convenience, not an SDK concept.
enum TesterEnvironment: String, CaseIterable {
    case dev, uat, prod

    var label: String {
        switch self {
        case .dev: return "Dev"
        case .uat: return "UAT"
        case .prod: return "Prod"
        }
    }

    var apiBaseUrl: String {
        switch self {
        case .dev: return "https://api.dev.encatch.com"
        case .uat: return "https://api.uat.encatch.com"
        case .prod: return "https://api.encatch.com"
        }
    }

    var webHost: String {
        switch self {
        case .dev: return "https://form.dev.encatch.com"
        case .uat: return "https://form.uat.encatch.com"
        case .prod: return "https://form.encatch.com"
        }
    }
}

/// Local persistence for the setup screen, so one build works for any tester/environment.
final class TesterPrefs {
    private let defaults = UserDefaults.standard

    private enum Keys {
        static let apiKey = "encatch_tester_api_key"
        static let formId = "encatch_tester_form_id"
        static let apiBaseUrl = "encatch_tester_api_base_url"
        static let webHost = "encatch_tester_web_host"
        static let interceptorFormId = "encatch_tester_interceptor_form_id"
        static let environment = "encatch_tester_environment"
        static let userName = "encatch_tester_user_name"
    }

    var apiKey: String? {
        get { defaults.string(forKey: Keys.apiKey) }
        set { defaults.set(newValue, forKey: Keys.apiKey) }
    }

    var formId: String? {
        get { defaults.string(forKey: Keys.formId) }
        set { defaults.set(newValue, forKey: Keys.formId) }
    }

    var apiBaseUrl: String? {
        get { defaults.string(forKey: Keys.apiBaseUrl) }
        set { defaults.set(newValue, forKey: Keys.apiBaseUrl) }
    }

    var webHost: String? {
        get { defaults.string(forKey: Keys.webHost) }
        set { defaults.set(newValue, forKey: Keys.webHost) }
    }

    var interceptorFormId: String? {
        get { defaults.string(forKey: Keys.interceptorFormId) }
        set { defaults.set(newValue, forKey: Keys.interceptorFormId) }
    }

    var environment: TesterEnvironment {
        get { TesterEnvironment(rawValue: defaults.string(forKey: Keys.environment) ?? "") ?? .prod }
        set { defaults.set(newValue.rawValue, forKey: Keys.environment) }
    }

    var userName: String? {
        get { defaults.string(forKey: Keys.userName) }
        set { defaults.set(newValue, forKey: Keys.userName) }
    }

    var isSetupComplete: Bool {
        !(apiKey ?? "").isEmpty && !(formId ?? "").isEmpty
    }

    func clear() {
        [Keys.apiKey, Keys.formId, Keys.apiBaseUrl, Keys.webHost, Keys.interceptorFormId, Keys.userName]
            .forEach { defaults.removeObject(forKey: $0) }
    }
}

/// A locally-saved test identity — independent of SDK identify, lets testers switch users without retyping.
struct TestUser: Codable, Identifiable {
    var username: String
    var email: String = ""
    var displayName: String = ""
    var id: String { username }
}

/// UserDefaults-backed JSON list of `TestUser`s, so testers can save/select/edit multiple identities.
final class TestUsersStore {
    private let defaults = UserDefaults.standard
    private let key = "encatch_tester_saved_users"

    func list() -> [TestUser] {
        guard let data = defaults.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([TestUser].self, from: data)) ?? []
    }

    func add(_ user: TestUser) {
        var updated = list().filter { $0.username != user.username }
        updated.append(user)
        save(updated)
    }

    func update(_ user: TestUser) {
        let updated = list().map { $0.username == user.username ? user : $0 }
        save(updated)
    }

    private func save(_ users: [TestUser]) {
        guard let data = try? JSONEncoder().encode(users) else { return }
        defaults.set(data, forKey: key)
    }
}
