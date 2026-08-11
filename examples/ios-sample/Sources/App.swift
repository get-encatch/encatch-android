import SwiftUI
import Encatch

/// Mock server default (matches `mock-server/src/main/kotlin/com/encatch/mockserver/Main.kt`'s
/// DEFAULT_PORT). iOS Simulator shares the host Mac's network namespace, so `127.0.0.1` reaches
/// a server started with `./gradlew :mock-server:run` directly — no emulator-style host alias
/// needed (unlike Android's `10.0.2.2`). Override via the `MOCK_SERVER_BASE_URL` environment
/// variable (set by the UI test target / orchestrator) for a non-default port.
private let mockServerBaseURL = ProcessInfo.processInfo.environment["MOCK_SERVER_BASE_URL"] ?? "http://127.0.0.1:8089"

@main
struct EncatchSampleApp: App {
    init() {
        EncatchFormHost.install()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    @State private var status = "Not initialized"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Encatch iOS Sample")
                .font(.title)
                .bold()

            Text(status)
                .font(.footnote)
                .foregroundColor(.secondary)
                .accessibilityIdentifier("statusText")

            Button("Init SDK") {
                Task {
                    do {
                        let config = EncatchConfig(
                            apiBaseUrl: mockServerBaseURL,
                            webHost: mockServerBaseURL,
                            theme: Theme.system,
                            isFullScreen: false,
                            debugMode: true,
                            appVersion: nil,
                            onBeforeShowForm: nil,
                        )
                        try await Encatch.shared.initialize(apiKey: "sample-api-key", config: config)
                        status = "Initialized: \(Encatch.shared.isInitialized)"
                    } catch {
                        status = "initialize() failed: \(EncatchError(error).localizedDescription)"
                    }
                }
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("initButton")

            Button("Show modal form") {
                Task {
                    do {
                        try await Encatch.shared.showForm("ios-modal-form-id")
                    } catch {
                        status = "showForm() failed: \(EncatchError(error).localizedDescription)"
                    }
                }
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("showModalButton")

            Text("Inline form slot:")
                .font(.headline)
                .padding(.top, 8)

            InlineFormRepresentable()
                .frame(height: 260)
                .background(Color(.systemGray6))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4)))

            Button("Show inline form") {
                Task {
                    do {
                        try await Encatch.shared.showForm("ios-inline-form-id")
                    } catch {
                        status = "showForm() failed: \(EncatchError(error).localizedDescription)"
                    }
                }
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("showInlineButton")

            Spacer()
        }
        .padding()
    }
}

struct InlineFormRepresentable: UIViewRepresentable {
    func makeUIView(context: Context) -> EncatchInlineFormView {
        let view = EncatchInlineFormView()
        view.formId = "ios-inline-form-id"
        return view
    }

    func updateUIView(_ uiView: EncatchInlineFormView, context: Context) {}
}
