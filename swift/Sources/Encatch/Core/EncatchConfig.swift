import Foundation

public let DEFAULT_API_BASE_URL = "https://api.encatch.com"
public let DEFAULT_WEB_HOST = "https://form.encatch.com"

/// Optional interceptor called before any form is shown (manual or automatic).
/// If it returns false, the SDK form will not open; the host app can show a
/// custom widget using the payload. Prefills are cleared when false.
public typealias BeforeShowFormInterceptor = @Sendable (ShowFormInterceptorPayload) async -> Bool

public struct EncatchConfig: Sendable {
    /// Base URL used for all API calls. Defaults to `DEFAULT_API_BASE_URL`.
    public var apiBaseUrl: String
    /// Base URL used to load the hosted form WebView page. Defaults to `DEFAULT_WEB_HOST`.
    public var webHost: String
    /// Default theme for forms. Defaults to `.system`.
    public var theme: Theme
    /// When true, the form overlay is displayed full-screen.
    public var isFullScreen: Bool
    /// Enable verbose SDK logging.
    public var debugMode: Bool
    /// Override app version (default: auto-detected from the host app's bundle).
    public var appVersion: String?
    public var onBeforeShowForm: BeforeShowFormInterceptor?

    public init(
        apiBaseUrl: String = DEFAULT_API_BASE_URL,
        webHost: String = DEFAULT_WEB_HOST,
        theme: Theme = .system,
        isFullScreen: Bool = false,
        debugMode: Bool = false,
        appVersion: String? = nil,
        onBeforeShowForm: BeforeShowFormInterceptor? = nil
    ) {
        self.apiBaseUrl = apiBaseUrl
        self.webHost = webHost
        self.theme = theme
        self.isFullScreen = isFullScreen
        self.debugMode = debugMode
        self.appVersion = appVersion
        self.onBeforeShowForm = onBeforeShowForm
    }
}
