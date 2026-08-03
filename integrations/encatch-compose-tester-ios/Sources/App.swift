import SwiftUI
import EncatchComposeTester

/// Standalone host app for the `:compose-sdk` tester — links only `EncatchComposeTester.xcframework`
/// (no `ios-native` package directly, no `swift/`): the whole UI, including every screen and the
/// `TesterPrefs` setup persistence, lives in `encatch-compose-tester`'s shared `commonMain`
/// Compose code. This file only hosts the root `UIViewController` it produces — mirrors
/// `ios-compose-sample`'s `App.swift`.
@main
struct EncatchComposeTesterApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeRootView()
                .ignoresSafeArea()
        }
    }
}

struct ComposeRootView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        ComposeTesterViewControllerKt.ComposeTesterViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
