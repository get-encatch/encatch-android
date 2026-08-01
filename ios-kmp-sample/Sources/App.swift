import SwiftUI
import EncatchKmpSample

/// Standalone host app for Variant 5 (KMP host sample app, iOS side): links only
/// `EncatchKmpSample.xcframework` — no `swift/` package — for the same reason
/// `ios-compose-sample` does (see HANDOFF.md's "Real architectural finding"): linking a second
/// Kotlin/Native framework that also embeds `:core` would duplicate its singletons. The entire
/// screen (status text + buttons) is built in Kotlin/Native directly
/// (`KmpSampleViewController.kt`) — this Swift file only hosts it.
private let mockServerBaseURL = ProcessInfo.processInfo.environment["MOCK_SERVER_BASE_URL"] ?? "http://127.0.0.1:8089"

@main
struct EncatchKmpSampleApp: App {
    var body: some Scene {
        WindowGroup {
            KmpRootView()
                .ignoresSafeArea()
        }
    }
}

struct KmpRootView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        KmpSampleViewControllerKt.KmpSampleViewController(mockServerBaseUrl: mockServerBaseURL)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
