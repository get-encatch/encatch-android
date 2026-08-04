import SwiftUI
import UIKit
import EncatchKmpTester

struct SetupView: View {
    @ObservedObject var state: TesterState
    @State private var environment: TesterEnvironment
    @State private var apiKey = ""
    @State private var formId = ""
    @State private var interceptorFormId = ""

    init(state: TesterState) {
        self.state = state
        _environment = State(initialValue: state.prefs.environment)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Encatch KMP Tester — Setup").font(.title2).bold()
                Text("Enter your own API key and default form id. Saved locally on this device — this same build works for any tester or environment.")
                    .font(.footnote)
                    .foregroundColor(.secondary)

                Text("Environment").font(.subheadline).bold()
                Picker("Environment", selection: $environment) {
                    ForEach(TesterEnvironment.allCases, id: \.self) { env in
                        Text(env.label).tag(env)
                    }
                }
                .pickerStyle(.segmented)
                Text("\(environment.apiBaseUrl) · \(environment.webHost)")
                    .font(.caption)
                    .foregroundColor(.secondary)

                TextField("API key *", text: $apiKey).textFieldStyle(.roundedBorder)
                TextField("Default form id (feedback config) *", text: $formId).textFieldStyle(.roundedBorder)
                TextField("Interceptor test form id (optional)", text: $interceptorFormId).textFieldStyle(.roundedBorder)

                Button("Save & continue") {
                    state.saveSetupAndInit(
                        environment: environment,
                        apiKey: apiKey.trimmed,
                        formId: formId.trimmed,
                        interceptorFormId: interceptorFormId.trimmed
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
    @State private var showNewUserForm = false
    @State private var newUsername = ""
    @State private var newEmail = ""
    @State private var newDisplayName = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Log in").font(.title2).bold()
                Text("Mock login — calls TesterController.shared.identify(userName:). Saved users are local to this tester, independent of the SDK.")
                    .font(.footnote)
                    .foregroundColor(.secondary)

                Text("Saved users").font(.subheadline).bold()
                if state.savedUsers.isEmpty {
                    Text("No saved users yet.").font(.footnote).foregroundColor(.secondary)
                }
                ForEach(state.savedUsers) { user in
                    Button(action: { state.selectUser(user) }) {
                        VStack(alignment: .leading) {
                            Text(user.username).bold()
                            if !user.displayName.isEmpty || !user.email.isEmpty {
                                Text([user.displayName, user.email].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.caption)
                            }
                            if state.selectedUsername == user.username {
                                Text("Selected").font(.caption).foregroundColor(.accentColor)
                            }
                        }
                        .padding(8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(.secondarySystemBackground))
                        .cornerRadius(8)
                    }
                    .buttonStyle(.plain)
                }

                if !showNewUserForm {
                    Button("+ New user") { showNewUserForm = true }
                } else {
                    TextField("Username", text: $newUsername).textFieldStyle(.roundedBorder)
                    TextField("Email", text: $newEmail).textFieldStyle(.roundedBorder)
                    TextField("Display name", text: $newDisplayName).textFieldStyle(.roundedBorder)
                    HStack {
                        Button("Save user") {
                            state.saveNewUser(TestUser(username: newUsername.trimmed, email: newEmail.trimmed, displayName: newDisplayName.trimmed))
                            showNewUserForm = false
                            newUsername = ""; newEmail = ""; newDisplayName = ""
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(newUsername.trimmed.isEmpty)
                        Button("Cancel") { showNewUserForm = false }
                    }
                }

                if let selected = state.selectedUsername {
                    Button("Edit profile before sign in") { state.screen = .editProfile(username: selected) }
                }

                Button("Identify user") { state.identify() }
                    .buttonStyle(.borderedProminent)
                    .disabled(state.selectedUsername == nil)

                Button("Change API key & setup") { state.clearSetup() }
                Spacer()
            }
            .padding()
        }
    }
}

struct EditProfileView: View {
    let username: String
    @ObservedObject var state: TesterState
    @State private var email: String
    @State private var displayName: String

    init(username: String, state: TesterState) {
        self.username = username
        self.state = state
        let existing = state.savedUsers.first { $0.username == username }
        _email = State(initialValue: existing?.email ?? "")
        _displayName = State(initialValue: existing?.displayName ?? "")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Edit profile").font(.title2).bold()
            Text("Username: \(username)").foregroundColor(.secondary)
            TextField("Email", text: $email).textFieldStyle(.roundedBorder)
            TextField("Display name", text: $displayName).textFieldStyle(.roundedBorder)
            Button("Save & identify") {
                state.updateUser(TestUser(username: username, email: email.trimmed, displayName: displayName.trimmed))
                state.screen = .login
            }
            .buttonStyle(.borderedProminent)
            Button("Back") { state.screen = .login }
            Spacer()
        }
        .padding()
    }
}

struct HeaderActions: View {
    @ObservedObject var state: TesterState

    var body: some View {
        HStack(spacing: 16) {
            Button(state.currentTheme.uppercased()) { state.cycleTheme() }
            Button("Logout") { state.logOut() }
        }
    }
}

struct MainTabView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                Group {
                    switch state.tab {
                    case .home: HomeTabView(state: state)
                    case .events: EventsTabView(state: state)
                    case .settings: SettingsTabView(state: state)
                    case .inlineAny: InlineAnyTabView(state: state)
                    case .inlineExact: InlineExactTabView(state: state)
                    }
                }
                InterceptorCarousel(
                    items: state.blockedForms,
                    onOpen: { item in state.openedForm = item },
                    onDismiss: { formId in state.dismissBlockedForm(formId) }
                )
            }
            .navigationTitle(state.tab.label)
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { HeaderActions(state: state) } }
            .sheet(item: $state.openedForm) { item in
                NativeFormModal(item: item) {
                    state.dismissBlockedForm(item.formId)
                    state.openedForm = nil
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            TesterTabBar(selected: $state.tab)
        }
    }
}

struct TesterTabBar: View {
    @Binding var selected: TesterTab

    var body: some View {
        HStack {
            ForEach(TesterTab.allCases, id: \.self) { tab in
                Button(action: { selected = tab }) {
                    Text(tab.label)
                        .font(.caption)
                        .fontWeight(selected == tab ? .bold : .regular)
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .padding(.vertical, 8)
        .background(.thinMaterial)
    }
}

struct HomeTabView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let userName = state.prefs.userName {
                    HStack {
                        Text("Signed in as \(userName)")
                        Button("Edit profile") { state.screen = .editProfile(username: userName) }
                    }
                }
                Text("Last event: \(state.lastEvent)").font(.footnote).foregroundColor(.secondary)

                Button("Show Form") { state.showModalForm() }.buttonStyle(.borderedProminent)
                Button("Show Form (prefilled)") { state.showPrefilledForm() }.buttonStyle(.borderedProminent)

                if let interceptorFormId = state.prefs.interceptorFormId, !interceptorFormId.isEmpty {
                    Button("Show Form (interceptor test)") { state.showInterceptorForm() }.buttonStyle(.borderedProminent)
                }
                Spacer()
            }
            .padding()
        }
        .onAppear { state.trackHomeViewed() }
    }
}

private let trackEventPresets = ["button_clicked", "feature_used", "purchase_started", "survey_viewed", "home_viewed"]
private let trackScreenPresets = ["/home", "/dashboard", "/settings", "/dashboard/encatch-test"]

struct EventsTabView: View {
    @ObservedObject var state: TesterState
    @State private var customEvent = "test_event"
    @State private var customScreen = "/dashboard/encatch-test"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("trackEvent presets").font(.subheadline).bold()
                ForEach(trackEventPresets, id: \.self) { name in
                    Button(name) { state.track(name) }
                        .buttonStyle(.bordered)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                TextField("Custom event", text: $customEvent).textFieldStyle(.roundedBorder)
                Button("Fire") { state.track(customEvent.trimmed) }.disabled(customEvent.trimmed.isEmpty)

                Text("trackScreen presets").font(.subheadline).bold().padding(.top, 12)
                ForEach(trackScreenPresets, id: \.self) { path in
                    Button(path) { state.trackScreen(path) }
                        .buttonStyle(.bordered)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                TextField("Custom screen", text: $customScreen).textFieldStyle(.roundedBorder)
                Button("Track") { state.trackScreen(customScreen.trimmed) }.disabled(customScreen.trimmed.isEmpty)
            }
            .padding()
        }
        .onAppear { state.trackScreen("Events") }
    }
}

private struct InlineFormRepresentable: UIViewRepresentable {
    let formId: String?
    @Binding var height: CGFloat

    func makeUIView(context: Context) -> UIView {
        InlineFormFactoryKt.makeInlineFormView(formId: formId, onHeightChange: { [binding = $height] newHeight in
            DispatchQueue.main.async { binding.wrappedValue = CGFloat(truncating: newHeight) }
        })
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// Auto-height container: follows the SDK view's own reported height (0 when idle, skeleton
/// placeholder while loading, then live form:resize values) instead of pinning a fixed frame.
private struct InlineFormSlot: View {
    let formId: String?
    @State private var height: CGFloat = 0

    var body: some View {
        InlineFormRepresentable(formId: formId, height: $height)
            .frame(height: max(height, 64))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(height > 64 ? 0 : 0.4)))
            .animation(.easeOut(duration: 0.2), value: height)
    }
}

struct InlineExactTabView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Claims \"\(state.prefs.formId ?? "")\" — only renders inline when that exact form id is shown.")
                    .font(.footnote)
                Button("Show Exact Form (renders inline below)") { state.showModalForm() }.buttonStyle(.borderedProminent)
                InlineFormSlot(formId: state.prefs.formId)
            }
            .padding()
        }
        .onAppear { state.trackScreen("InlineExact") }
    }
}

struct InlineAnyTabView: View {
    @ObservedObject var state: TesterState
    @State private var wildcardFormId = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Catches any form id not exactly claimed elsewhere.").font(.footnote)
                TextField("Form id", text: $wildcardFormId).textFieldStyle(.roundedBorder)
                Button("Show Form (renders inline below)") { state.showForm(wildcardFormId.trimmed) }
                    .buttonStyle(.borderedProminent)
                    .disabled(wildcardFormId.trimmed.isEmpty)
                Button("Trigger unmatched form → modal fallback") { state.showForm("modal-fallback-demo") }
                InlineFormSlot(formId: nil)
            }
            .padding()
        }
        .onAppear { state.trackScreen("InlineAny") }
    }
}

struct SettingsTabView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Environment: \(state.prefs.environment.label)")
            Text("Form id: \(state.prefs.formId ?? "")")
            Text("API base URL: \(state.prefs.apiBaseUrl ?? "(default)")")
            Text("Web host: \(state.prefs.webHost ?? "(default)")")
            Text("Interceptor form id: \(state.prefs.interceptorFormId ?? "(none)")")

            Button("Set Locale → fr-FR") { state.setLocaleFrFr() }.buttonStyle(.borderedProminent)
            Button("Set Country → FR") { state.setCountryFr() }.buttonStyle(.borderedProminent)
            Button("Change API key & setup") { state.clearSetup() }
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
            Button("Back to home") { state.screen = .main }.buttonStyle(.borderedProminent)
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
            Button("Go back") { state.screen = .main }.buttonStyle(.borderedProminent)
            Spacer()
        }
        .padding()
        .onAppear { state.trackScreen("RouteNotFound") }
    }
}
