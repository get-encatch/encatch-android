import SwiftUI
import UIKit
import EncatchKmpTester

struct SetupView: View {
    @ObservedObject var state: TesterState
    @State private var apiKey = ""
    @State private var formId = ""
    @State private var baseUrl = ""
    @State private var webHost = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Encatch KMP Tester — Setup").font(.title2).bold()
                Text("Enter your own API key and default form id. Saved locally on this device — this same build works for any tester or environment.")
                    .font(.footnote)
                    .foregroundColor(.secondary)

                TextField("API key *", text: $apiKey).textFieldStyle(.roundedBorder)
                TextField("Default form id (feedback config) *", text: $formId).textFieldStyle(.roundedBorder)
                TextField("API base URL (optional)", text: $baseUrl)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)
                TextField("Web host (optional)", text: $webHost)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)

                Button("Save & continue") {
                    state.saveSetupAndInit(
                        apiKey: apiKey.trimmed,
                        formId: formId.trimmed,
                        baseUrl: baseUrl.trimmed,
                        webHost: webHost.trimmed
                    )
                }
                .buttonStyle(.borderedProminent)
                .disabled(apiKey.trimmed.isEmpty || formId.trimmed.isEmpty)
            }
            .padding()
        }
    }
}

struct LoginView: View {
    @ObservedObject var state: TesterState
    @State private var userName: String

    init(state: TesterState) {
        self.state = state
        _userName = State(initialValue: state.prefs.userName ?? "")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Log in").font(.title2).bold()
            Text("Mock login — calls TesterController.shared.identify(userName:).")
                .font(.footnote)
                .foregroundColor(.secondary)
            TextField("Username", text: $userName).textFieldStyle(.roundedBorder)
            Button("Log in") { state.logIn(userName: userName.trimmed) }
                .buttonStyle(.borderedProminent)
                .disabled(userName.trimmed.isEmpty)
            Spacer()
        }
        .padding()
    }
}

struct HomeView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Home").font(.title2).bold()
            Text("Last event: \(state.lastEvent)").font(.footnote).foregroundColor(.secondary)

            Button("Show form (modal)") { state.showModalForm() }
                .buttonStyle(.borderedProminent)

            HStack(spacing: 16) {
                Button("Events") { state.screen = .events }
                Button("Inline") { state.screen = .inline }
                Button("Settings") { state.screen = .settings }
            }
            .padding(.top, 8)

            Spacer()
        }
        .padding()
        .onAppear { state.trackHomeViewed() }
    }
}

struct EventsView: View {
    @ObservedObject var state: TesterState
    private let events = ["button_clicked", "feature_used", "purchase_started", "survey_viewed"]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Events").font(.title2).bold()
            ForEach(events, id: \.self) { name in
                Button(name) { state.track(name) }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button("Back") { state.screen = .home }
            Spacer()
        }
        .padding()
        .onAppear { state.trackScreen("Events") }
    }
}

private struct InlineFormRepresentable: UIViewRepresentable {
    let formId: String?

    func makeUIView(context: Context) -> UIView {
        InlineFormFactoryKt.makeInlineFormView(formId: formId)
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

struct InlineView: View {
    @ObservedObject var state: TesterState
    @State private var wildcardFormId = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Inline forms").font(.title2).bold()

                Text("Exact (claims \"\(state.prefs.formId ?? "")\")").font(.subheadline)
                InlineFormRepresentable(formId: state.prefs.formId)
                    .frame(height: 280)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4)))
                Button("Show exact inline form") { state.showModalForm() }.buttonStyle(.bordered)

                Text("Wildcard (catches any form id not exactly claimed elsewhere)")
                    .font(.subheadline)
                    .padding(.top, 12)
                InlineFormRepresentable(formId: nil)
                    .frame(height: 280)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.4)))
                TextField("Form id", text: $wildcardFormId).textFieldStyle(.roundedBorder)
                Button("Show in wildcard slot") { state.showForm(wildcardFormId.trimmed) }
                    .buttonStyle(.bordered)
                    .disabled(wildcardFormId.trimmed.isEmpty)

                Button("Back") { state.screen = .home }.padding(.top, 12)
            }
            .padding()
        }
        .onAppear { state.trackScreen("Inline") }
    }
}

struct SettingsView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Settings").font(.title2).bold()
            Text("Form id: \(state.prefs.formId ?? "")")
            Text("API base URL: \(state.prefs.apiBaseUrl ?? "(default)")")
            Text("Web host: \(state.prefs.webHost ?? "(default)")")

            Button("Log out") { state.logOut() }.buttonStyle(.borderedProminent)
            Button("Clear saved setup") { state.clearSetup() }.buttonStyle(.bordered)
            Button("Back") { state.screen = .home }
            Spacer()
        }
        .padding()
        .onAppear { state.trackScreen("Settings") }
    }
}

struct BillingView: View {
    let route: String
    @ObservedObject var state: TesterState

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Billing").font(.title2).bold()
            Text("Reached via CTA app_navigate route: \"\(route)\"")
            Button("Back to home") { state.screen = .home }.buttonStyle(.borderedProminent)
            Spacer()
        }
        .padding()
        .onAppear { state.trackScreen("Billing") }
    }
}

struct RouteNotFoundView: View {
    let route: String
    @ObservedObject var state: TesterState

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Route not found").font(.title2).bold()
            Text("The CTA requested an unmapped route: \"\(route)\"")
            Button("Go back") { state.screen = .home }.buttonStyle(.borderedProminent)
            Spacer()
        }
        .padding()
        .onAppear { state.trackScreen("RouteNotFound") }
    }
}
