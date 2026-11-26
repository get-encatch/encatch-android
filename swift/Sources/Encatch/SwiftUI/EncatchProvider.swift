#if canImport(SwiftUI)
import SwiftUI

/// Initializes the SDK and gives the views inside it an `EncatchContext`, the counterpart of React
/// Native's and Flutter's `EncatchProvider`:
///
/// ```swift
/// @main
/// struct MyApp: App {
///     var body: some Scene {
///         WindowGroup {
///             EncatchProvider(apiKey: "YOUR_API_KEY") {
///                 ContentView()
///             }
///         }
///     }
/// }
/// ```
///
/// It initializes the SDK once per API key (showing the provider again doesn't re-initialize it),
/// installs the modal form host on iOS, and publishes the SDK's state to every view inside through
/// `@EnvironmentObject var encatch: EncatchContext`. Screens are not tracked automatically: use
/// `.encatchTrackScreen("Home")` on each screen.
///
/// Unlike React Native's and Flutter's providers it starts no session at launch: as everywhere in
/// the native SDKs, the session (and its ping) starts when an identify succeeds or the app calls
/// `Encatch.shared.startSession()`.
@available(iOS 15.0, macOS 12.0, *)
public struct EncatchProvider<Content: View>: View {
    private let apiKey: String
    private let config: EncatchConfig?
    private let content: Content
    @StateObject private var context = EncatchContext()

    public init(apiKey: String, config: EncatchConfig? = nil, @ViewBuilder content: () -> Content) {
        self.apiKey = apiKey
        self.config = config
        self.content = content()
    }

    public var body: some View {
        content
            .environmentObject(context)
            .environment(\.encatchContext, context)
            .task(id: apiKey) { await context.initialize(apiKey: apiKey, config: config) }
    }
}

@available(iOS 15.0, macOS 12.0, *)
extension View {
    /// Tracks this screen with `Encatch.trackScreen` each time it appears (the counterpart of React
    /// Native's automatic screen tracking, for apps without a navigation library hook). Inside an
    /// `EncatchProvider`, a screen that appears before the SDK is initialized is tracked once it is.
    public func encatchTrackScreen(_ screenName: String) -> some View {
        modifier(EncatchTrackScreenModifier(screenName: screenName))
    }
}

@available(iOS 15.0, macOS 12.0, *)
private struct EncatchTrackScreenModifier: ViewModifier {
    let screenName: String
    @Environment(\.encatchContext) private var context

    func body(content: Content) -> some View {
        // .task: runs each time the view appears (or the name changes), cancelled when it disappears
        content.task(id: screenName) {
            if let context {
                await context.trackScreenWhenInitialized(screenName)
            } else {
                try? await Encatch.shared.trackScreen(screenName)
            }
        }
    }
}

/// The provider's context, for modifiers that work with or without a provider.
private struct EncatchContextKey: EnvironmentKey {
    static let defaultValue: EncatchContext? = nil
}

extension EnvironmentValues {
    var encatchContext: EncatchContext? {
        get { self[EncatchContextKey.self] }
        set { self[EncatchContextKey.self] = newValue }
    }
}
#endif
