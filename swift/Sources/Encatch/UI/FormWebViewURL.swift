import Foundation

/// Builds the hosted-form WebView URL, mirrors `buildFormWebViewUrl` from
/// `form-webview-helpers.ts` (and `:android`'s identically-named, Android-`Uri`-based helper —
/// this is a plain-Foundation reimplementation since `:android`'s version isn't shared via
/// `:core`).
func buildFormWebViewUrl(webHost: String, formId: String, instanceKey: Int32, debugMode: Bool, presentation: String = "modal") -> String {
    var components = URLComponents(string: "\(webHost)/s/react-native-sdk-form")!
    var items = [
        URLQueryItem(name: "formId", value: formId),
        URLQueryItem(name: "ts", value: String(instanceKey)),
    ]
    if debugMode { items.append(URLQueryItem(name: "debug", value: "true")) }
    if presentation == "inline" { items.append(URLQueryItem(name: "presentation", value: "inline")) }
    components.queryItems = items
    return components.url?.absoluteString ?? "\(webHost)/s/react-native-sdk-form"
}
