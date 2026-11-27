import Foundation

public let DEFAULT_API_BASE_URL = "https://api.encatch.com"
public let DEFAULT_WEB_HOST = "https://form.encatch.com"

/// Optional interceptor called before any form is shown (manual or automatic).
/// If it returns false, the SDK form will not open; the host app can show a
/// custom widget using the payload. Prefills are cleared when false.
public typealias BeforeShowFormInterceptor = @Sendable (ShowFormInterceptorPayload) async -> Bool

/// Passed to `EncatchConfig.onSignatureExpired`: the user whose secure-identify signature expired.
public struct SignatureExpiredInfo: Sendable, Equatable {
    public let userName: String
    public init(userName: String) { self.userName = userName }
}

/// See `EncatchConfig.onSignatureExpired`.
public typealias SignatureExpiredListener = @Sendable (SignatureExpiredInfo) async throws -> Void

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
    /// Keep the identified user after an app restart (the default). When false, the user (name,
    /// user id, secure signature, survey history) lives in memory for the current app run only: the
    /// next launch starts as a visitor until the app identifies again, and user data an earlier run
    /// saved is deleted at `init`. The device id, a saved `stopSession()` and the locale and country
    /// preferences are still saved.
    public var persistUserAcrossRestarts: Bool
    public var onBeforeShowForm: BeforeShowFormInterceptor?
    /// Called when the current user's secure-identify signature has expired, so the app can get a
    /// fresh one from its own server and call `identifyUser` again with it. A rejected new signature
    /// calls it once per user until an identify for them succeeds (a signature that keeps being
    /// rejected doesn't loop); the stored signature expiring always does, so at most twice. Called
    /// after the SDK has handled the reply, and only while that user is still the current one. Not on
    /// the main actor; an error it throws is logged and otherwise ignored.
    ///
    /// The SDK only learns of the expiry when a request the API checks goes out (today identify-user:
    /// `identifyUser` and `setUserProperties`); it can't warn at launch. The signed time
    /// (`generatedDateTimeInUtc`) must be epoch milliseconds (13 digits) from a server clock within
    /// 5 minutes of real time.
    public var onSignatureExpired: SignatureExpiredListener?

    public init(
        apiBaseUrl: String = DEFAULT_API_BASE_URL,
        webHost: String = DEFAULT_WEB_HOST,
        theme: Theme = .system,
        isFullScreen: Bool = false,
        debugMode: Bool = false,
        appVersion: String? = nil,
        persistUserAcrossRestarts: Bool = true,
        onBeforeShowForm: BeforeShowFormInterceptor? = nil
    ) {
        self.apiBaseUrl = apiBaseUrl
        self.webHost = webHost
        self.theme = theme
        self.isFullScreen = isFullScreen
        self.debugMode = debugMode
        self.appVersion = appVersion
        self.persistUserAcrossRestarts = persistUserAcrossRestarts
        self.onBeforeShowForm = onBeforeShowForm
    }

    /// The same, with `onSignatureExpired`. A separate initializer with the label required, so an
    /// existing `EncatchConfig(...) { payload in ... }` trailing closure still means `onBeforeShowForm`
    /// in every Swift language mode.
    @_disfavoredOverload
    public init(
        apiBaseUrl: String = DEFAULT_API_BASE_URL,
        webHost: String = DEFAULT_WEB_HOST,
        theme: Theme = .system,
        isFullScreen: Bool = false,
        debugMode: Bool = false,
        appVersion: String? = nil,
        persistUserAcrossRestarts: Bool = true,
        onBeforeShowForm: BeforeShowFormInterceptor? = nil,
        onSignatureExpired: @escaping SignatureExpiredListener
    ) {
        self.init(
            apiBaseUrl: apiBaseUrl,
            webHost: webHost,
            theme: theme,
            isFullScreen: isFullScreen,
            debugMode: debugMode,
            appVersion: appVersion,
            persistUserAcrossRestarts: persistUserAcrossRestarts,
            onBeforeShowForm: onBeforeShowForm
        )
        self.onSignatureExpired = onSignatureExpired
    }
}
