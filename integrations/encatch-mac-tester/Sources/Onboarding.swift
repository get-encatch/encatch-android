import SwiftUI
import Encatch

/// Setup → Login → EditProfile as one fixed-size sheet over the always-visible `RootShell`,
/// instead of the iPhone tester's full-window screen swaps — mirrors how Xcode/Migration
/// Assistant present one-time setup without hiding the app's own chrome. Ported field-for-field
/// from `encatch-ios-tester`'s `SetupView`/`LoginView`/`EditProfileView`, restyled onto
/// `Form(.formStyle(.grouped))` + system controls per `MacTheme.swift`'s rationale.
struct OnboardingSheet: View {
    @ObservedObject var state: TesterState
    let step: OnboardingStep

    var body: some View {
        VStack(spacing: 0) {
            switch step {
            case .setup: SetupStep(state: state)
            case .login: LoginStep(state: state)
            case .editProfile(let username): EditProfileStep(state: state, username: username)
            }
        }
        .frame(width: 480, height: 560)
    }
}

private struct SetupStep: View {
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
        VStack(spacing: 0) {
            VStack(spacing: 8) {
                BrandMark()
                Text("Encatch Mac Tester").font(.title2.weight(.semibold))
                Text("Enter your API key and default form id. Saved locally on this Mac — the same build works for any tester or environment.")
                    .font(.callout)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 380)
            }
            .padding(.top, 28)
            .padding(.bottom, 12)

            Form {
                Section("Environment") {
                    Picker("Environment", selection: $environment) {
                        ForEach(TesterEnvironment.allCases, id: \.self) { env in
                            Text(env.label).tag(env)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    LabeledContent("Base URL", value: environment.apiBaseUrl)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Section("Credentials") {
                    TextField("API key", text: $apiKey)
                        .textFieldStyle(.roundedBorder)
                    TextField("Default form id", text: $formId)
                        .textFieldStyle(.roundedBorder)
                    TextField("Interceptor test form id (optional)", text: $interceptorFormId)
                        .textFieldStyle(.roundedBorder)
                }
            }
            .formStyle(.grouped)

            Spacer(minLength: 0)

            HStack {
                Spacer()
                Button("Save & Continue") {
                    state.saveSetupAndInit(
                        environment: environment,
                        apiKey: apiKey.trimmed,
                        formId: formId.trimmed,
                        interceptorFormId: interceptorFormId.trimmed
                    )
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(apiKey.trimmed.isEmpty || formId.trimmed.isEmpty)
                .keyboardShortcut(.defaultAction)
            }
            .padding()
        }
    }
}

private struct LoginStep: View {
    @ObservedObject var state: TesterState
    @State private var showNewUserForm = false
    @State private var newUsername = ""
    @State private var newEmail = ""
    @State private var newDisplayName = ""

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 6) {
                Text("Log in").font(.title2.weight(.semibold))
                Text("Mock login — calls Encatch.shared.identifyUser(userName:). Saved users are local to this tester, independent of the SDK.")
                    .font(.callout)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 400)
            }
            .padding(.top, 24)
            .padding(.bottom, 12)

            Form {
                if !state.savedUsers.isEmpty {
                    Section("Saved users") {
                        ForEach(state.savedUsers) { user in
                            Button(action: { state.selectUser(user) }) {
                                HStack(spacing: 10) {
                                    InitialsAvatar(name: user.displayName.isEmpty ? user.username : user.displayName, size: 28)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(user.username).font(.body)
                                        if !user.displayName.isEmpty || !user.email.isEmpty {
                                            Text([user.displayName, user.email].filter { !$0.isEmpty }.joined(separator: " · "))
                                                .font(.caption)
                                                .foregroundColor(.secondary)
                                        }
                                    }
                                    Spacer()
                                    if state.selectedUsername == user.username {
                                        Image(systemName: "checkmark.circle.fill").foregroundColor(.accentColor)
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                Section {
                    if showNewUserForm {
                        TextField("Username", text: $newUsername).textFieldStyle(.roundedBorder)
                        TextField("Email", text: $newEmail).textFieldStyle(.roundedBorder)
                        TextField("Display name", text: $newDisplayName).textFieldStyle(.roundedBorder)
                        HStack {
                            Button("Save user") {
                                state.saveNewUser(TestUser(username: newUsername.trimmed, email: newEmail.trimmed, displayName: newDisplayName.trimmed))
                                showNewUserForm = false
                                newUsername = ""; newEmail = ""; newDisplayName = ""
                            }
                            .disabled(newUsername.trimmed.isEmpty)
                            Button("Cancel") { showNewUserForm = false }
                        }
                    } else {
                        Button("New user…") { showNewUserForm = true }
                    }
                }

                if let username = state.selectedUsername {
                    Section {
                        Button("Edit profile before sign in") {
                            state.onboardingStep = .editProfile(username: username)
                        }
                    }
                }
            }
            .formStyle(.grouped)

            Spacer(minLength: 0)

            HStack {
                Button("Change API Key & Setup") { state.clearSetup() }
                    .foregroundColor(.secondary)
                Spacer()
                Button("Identify User") { state.identify() }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(state.selectedUsername == nil)
                    .keyboardShortcut(.defaultAction)
            }
            .padding()
        }
    }
}

private struct EditProfileStep: View {
    @ObservedObject var state: TesterState
    let username: String
    @State private var email: String
    @State private var displayName: String

    init(state: TesterState, username: String) {
        self.state = state
        self.username = username
        let existing = state.savedUsers.first { $0.username == username }
        _email = State(initialValue: existing?.email ?? "")
        _displayName = State(initialValue: existing?.displayName ?? "")
    }

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 6) {
                InitialsAvatar(name: displayName.isEmpty ? username : displayName, size: 56)
                Text("Edit Profile").font(.title2.weight(.semibold))
                Text(username).font(.callout).foregroundColor(.secondary)
            }
            .padding(.top, 24)
            .padding(.bottom, 12)

            Form {
                Section {
                    TextField("Email", text: $email).textFieldStyle(.roundedBorder)
                    TextField("Display name", text: $displayName).textFieldStyle(.roundedBorder)
                }
            }
            .formStyle(.grouped)

            Spacer(minLength: 0)

            HStack {
                Button("Back") { state.onboardingStep = .login }
                    .foregroundColor(.secondary)
                Spacer()
                Button("Save & Identify") {
                    state.updateUser(TestUser(username: username, email: email.trimmed, displayName: displayName.trimmed))
                    state.identify()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .keyboardShortcut(.defaultAction)
            }
            .padding()
        }
    }
}
