import SwiftUI
import EncatchComposeSample

/// Standalone host app for Variant 4 (Compose Multiplatform, iOS side): links only
/// `EncatchComposeSample.xcframework` — no `swift/` package — since double-linking both would
/// statically embed `:core` twice into one process, producing two disconnected `Encatch`
/// singletons (see HANDOFF.md / EncatchNativeInlineFormView.kt for the real crash this caused
/// when first tried as a second screen inside `ios-sample`). A real Compose Multiplatform
/// customer would integrate the same way: one UI strategy per app.
private let mockServerBaseURL = ProcessInfo.processInfo.environment["MOCK_SERVER_BASE_URL"] ?? "http://127.0.0.1:8089"

@main
struct EncatchComposeSampleApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeRootView()
                .ignoresSafeArea()
        }
    }
}

struct ComposeRootView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        ComposeSampleViewControllerKt.ComposeSampleViewController(mockServerBaseUrl: mockServerBaseURL)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
