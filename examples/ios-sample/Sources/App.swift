import SwiftUI
import Encatch

/// Mock server default (matches `mock-server/src/main/kotlin/com/encatch/mockserver/Main.kt`'s
/// DEFAULT_PORT). iOS Simulator shares the host Mac's network namespace, so `127.0.0.1` reaches
/// a server started with `./gradlew :mock-server:run` directly — no emulator-style host alias
/// needed (unlike Android's `10.0.2.2`). Override via the `MOCK_SERVER_BASE_URL` environment
/// variable (set by the UI test target / orchestrator) for a non-default port.
private let mockServerBaseURL = ProcessInfo.processInfo.environment["MOCK_SERVER_BASE_URL"] ?? "http://127.0.0.1:8089"

/// The SwiftUI integration the Swift README describes: `EncatchProvider` initializes the SDK and
/// installs the modal form host, views read `EncatchContext`, `EncatchInlineForm` sizes itself.
@main
struct EncatchSampleApp: App {
    var body: some Scene {
        WindowGroup {
            EncatchProvider(
                apiKey: "sample-api-key",
                config: EncatchConfig(apiBaseUrl: mockServerBaseURL, webHost: mockServerBaseURL, debugMode: true)
            ) {
                ContentView()
            }
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var encatch: EncatchContext

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Encatch iOS Sample")
                    .font(.title)
                    .bold()

                Text("Initialized: \(encatch.isInitialized ? "true" : "false")")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .accessibilityIdentifier("statusText")

                Text(encatch.isIdentified ? "Identified: \(encatch.userName ?? "")" : "Identified: no")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .accessibilityIdentifier("identityText")

                HStack {
                    Button("Sign in") { encatch.identifyUser(userName: "sample-user") }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("signInButton")
                    Button("Sign out") { encatch.resetUser() }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("signOutButton")
                }

                Button("Show modal form") { encatch.showForm("ios-modal-form-id") }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("showModalButton")

                Text("Inline form slot:")
                    .font(.headline)
                    .padding(.top, 8)

                // sizes itself: 0 until a form is shown, then the survey's height
                EncatchInlineForm(formId: "ios-inline-form-id")
                    .background(Color(.systemGray6))
                    .accessibilityIdentifier("inlineForm")

                Button("Show inline form") { encatch.showForm("ios-inline-form-id") }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("showInlineButton")
            }
            .padding()
        }
        .encatchTrackScreen("Sample")
    }
}
