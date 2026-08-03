import Foundation

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

/// Local persistence for the setup screen, so one build works for any tester/environment.
final class TesterPrefs {
    private let defaults = UserDefaults.standard

    private enum Keys {
        static let apiKey = "encatch_kmp_tester_api_key"
        static let formId = "encatch_kmp_tester_form_id"
        static let apiBaseUrl = "encatch_kmp_tester_api_base_url"
        static let webHost = "encatch_kmp_tester_web_host"
        static let interceptorFormId = "encatch_kmp_tester_interceptor_form_id"
        static let userName = "encatch_kmp_tester_user_name"
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
