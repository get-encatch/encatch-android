import Encatch
import Foundation

/// Secure identify against the mock-server (E8, onSignatureExpired): it signs like the app's own
/// server would (`/__mock/sign`, a time `ageMinutes` old) and records the onSignatureExpired calls
/// (`/__mock/hooks`). Only with the Mock environment (`baseUrl` nil otherwise: nothing is sent); a
/// failure is logged and gives nil.
enum MockSignature {
    /// The mock-server's base URL, set at init with the Mock environment only.
    nonisolated(unsafe) static var baseUrl: String?

    static func sign(_ userName: String, ageMinutes: Int) async -> SecureOptions? {
        guard let base = baseUrl else { return nil }
        // a query value: only unreserved characters left as they are (`+`, `&`, `=` encoded too)
        let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        guard let name = userName.addingPercentEncoding(withAllowedCharacters: unreserved),
              let url = URL(string: "\(base)/__mock/sign?userName=\(name)&ageMinutes=\(ageMinutes)")
        else { return nil }
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let signature = json["signature"] as? String,
              let time = json["generatedDateTimeInUtc"] as? String
        else {
            print("[EncatchTester] mock sign failed for \(base)")
            return nil
        }
        return SecureOptions(signature: signature, generatedDateTimeInUtc: time)
    }

    static func reportExpired(_ userName: String) async {
        guard let base = baseUrl, let url = URL(string: "\(base)/__mock/hooks") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["hook": "onSignatureExpired", "userName": userName])
        if (try? await URLSession.shared.data(for: request)) == nil { print("[EncatchTester] mock hooks report failed") }
    }
}
