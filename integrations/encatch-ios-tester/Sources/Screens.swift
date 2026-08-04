import SwiftUI
import Encatch

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
            VStack(alignment: .leading, spacing: 20) {
                VStack(spacing: 10) {
                    BrandMark()
                    Text("Encatch Tester").font(.title2.weight(.bold))
                    Text("Enter your API key and default form id. Saved locally on this device — the same build works for any tester or environment.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 24)

                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "Environment", icon: "server.rack")
                    VStack(alignment: .leading, spacing: 10) {
                        Picker("Environment", selection: $environment) {
                            ForEach(TesterEnvironment.allCases, id: \.self) { env in
                                Text(env.label).tag(env)
                            }
                        }
                        .pickerStyle(.segmented)
                        HStack(spacing: 6) {
                            Image(systemName: "link").font(.caption2)
                            Text("\(environment.apiBaseUrl) · \(environment.webHost)").font(.caption)
                        }
                        .foregroundColor(.secondary)
                    }
                    .card()
                }

                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "Credentials", icon: "key.fill")
                    VStack(alignment: .leading, spacing: 14) {
                        VStack(alignment: .leading, spacing: 6) {
                            FieldLabel(text: "API key", required: true)
                            TextField("en_dev_…", text: $apiKey)
                                .textFieldStyle(FilledFieldStyle())
                                .autocapitalization(.none)
                                .disableAutocorrection(true)
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            FieldLabel(text: "Default form id (feedback config)", required: true)
                            TextField("form id", text: $formId)
                                .textFieldStyle(FilledFieldStyle())
                                .autocapitalization(.none)
                                .disableAutocorrection(true)
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            FieldLabel(text: "Interceptor test form id (optional)")
                            TextField("form id", text: $interceptorFormId)
                                .textFieldStyle(FilledFieldStyle())
                                .autocapitalization(.none)
                                .disableAutocorrection(true)
                        }
                    }
                    .card()
                }

                Button("Save & continue") {
                    state.saveSetupAndInit(
                        environment: environment,
                        apiKey: apiKey.trimmed,
                        formId: formId.trimmed,
                        interceptorFormId: interceptorFormId.trimmed
                    )
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(apiKey.trimmed.isEmpty || formId.trimmed.isEmpty)
            }
            .padding()
        }
        .screenBackground()
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
            VStack(alignment: .leading, spacing: 20) {
                VStack(spacing: 10) {
                    BrandMark()
                    Text("Log in").font(.title2.weight(.bold))
                    Text("Mock login — calls Encatch.shared.identifyUser(userName:). Saved users are local to this tester, independent of the SDK.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 8)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 24)

                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "Saved users", icon: "person.2.fill")
                    VStack(spacing: 8) {
                        if state.savedUsers.isEmpty {
                            HStack(spacing: 8) {
                                Image(systemName: "person.crop.circle.badge.questionmark")
                                    .foregroundColor(.secondary)
                                Text("No saved users yet — add one below.")
                                    .font(.footnote)
                                    .foregroundColor(.secondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 6)
                        }
                        ForEach(state.savedUsers) { user in
                            Button(action: { state.selectUser(user) }) {
                                HStack(spacing: 12) {
                                    InitialsAvatar(name: user.displayName.isEmpty ? user.username : user.displayName)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(user.username).font(.subheadline.weight(.semibold))
                                        if !user.displayName.isEmpty || !user.email.isEmpty {
                                            Text([user.displayName, user.email].filter { !$0.isEmpty }.joined(separator: " · "))
                                                .font(.caption)
                                                .foregroundColor(.secondary)
                                                .lineLimit(1)
                                        }
                                    }
                                    Spacer()
                                    Image(systemName: state.selectedUsername == user.username
                                          ? "checkmark.circle.fill" : "circle")
                                        .font(.title3)
                                        .foregroundColor(state.selectedUsername == user.username
                                                         ? TesterTheme.accent : Color(.systemGray3))
                                }
                                .padding(10)
                                .background(state.selectedUsername == user.username
                                            ? TesterTheme.accentSoft : Color(.tertiarySystemFill))
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                            .buttonStyle(.plain)
                        }

                        if !showNewUserForm {
                            Button(action: { showNewUserForm = true }) {
                                Label("New user", systemImage: "plus.circle.fill")
                                    .font(.subheadline.weight(.medium))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                            }
                            .foregroundColor(TesterTheme.accent)
                        } else {
                            VStack(alignment: .leading, spacing: 10) {
                                TextField("Username", text: $newUsername)
                                    .textFieldStyle(FilledFieldStyle())
                                    .autocapitalization(.none)
                                    .disableAutocorrection(true)
                                TextField("Email", text: $newEmail)
                                    .textFieldStyle(FilledFieldStyle())
                                    .keyboardType(.emailAddress)
                                    .autocapitalization(.none)
                                    .disableAutocorrection(true)
                                TextField("Display name", text: $newDisplayName)
                                    .textFieldStyle(FilledFieldStyle())
                                HStack(spacing: 10) {
                                    Button("Save user") {
                                        state.saveNewUser(TestUser(username: newUsername.trimmed, email: newEmail.trimmed, displayName: newDisplayName.trimmed))
                                        showNewUserForm = false
                                        newUsername = ""; newEmail = ""; newDisplayName = ""
                                    }
                                    .buttonStyle(SecondaryButtonStyle())
                                    .disabled(newUsername.trimmed.isEmpty)
                                    Button("Cancel") { showNewUserForm = false }
                                        .buttonStyle(QuietButtonStyle())
                                }
                            }
                            .padding(.top, 4)
                        }
                    }
                    .card()
                }

                VStack(spacing: 10) {
                    if let selected = state.selectedUsername {
                        Button(action: { state.screen = .editProfile(username: selected) }) {
                            Label("Edit profile before sign in", systemImage: "pencil")
                        }
                        .buttonStyle(SecondaryButtonStyle())
                    }

                    Button("Identify user") { state.identify() }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(state.selectedUsername == nil)

                    Button("Change API key & setup") { state.clearSetup() }
                        .buttonStyle(QuietButtonStyle())
                }
            }
            .padding()
        }
        .screenBackground()
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
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(spacing: 10) {
                    InitialsAvatar(name: displayName.isEmpty ? username : displayName, size: 64)
                    Text("Edit profile").font(.title2.weight(.bold))
                    Text("@\(username)").font(.subheadline).foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 24)

                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "Profile traits", icon: "person.text.rectangle")
                    VStack(alignment: .leading, spacing: 14) {
                        VStack(alignment: .leading, spacing: 6) {
                            FieldLabel(text: "Email")
                            TextField("name@example.com", text: $email)
                                .textFieldStyle(FilledFieldStyle())
                                .keyboardType(.emailAddress)
                                .autocapitalization(.none)
                                .disableAutocorrection(true)
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            FieldLabel(text: "Display name")
                            TextField("Display name", text: $displayName)
                                .textFieldStyle(FilledFieldStyle())
                        }
                    }
                    .card()
                }

                Button("Save & identify") {
                    state.updateUser(TestUser(username: username, email: email.trimmed, displayName: displayName.trimmed))
                    state.screen = .login
                }
                .buttonStyle(PrimaryButtonStyle())

                Button("Back") { state.screen = .login }
                    .buttonStyle(QuietButtonStyle())
            }
            .padding()
        }
        .screenBackground()
    }
}

struct HeaderActions: View {
    @ObservedObject var state: TesterState

    private var themeIcon: String {
        switch state.currentTheme {
        case .system: return "circle.lefthalf.filled"
        case .light: return "sun.max.fill"
        case .dark: return "moon.fill"
        }
    }

    var body: some View {
        HStack(spacing: 14) {
            Button(action: { state.cycleTheme() }) {
                HStack(spacing: 4) {
                    Image(systemName: themeIcon)
                    Text(state.currentTheme.rawValue.capitalized).font(.caption.weight(.medium))
                }
            }
            Button(action: { state.logOut() }) {
                Image(systemName: "rectangle.portrait.and.arrow.right")
            }
        }
        .foregroundColor(TesterTheme.accent)
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
                    case .logs: LogsTabView(state: state)
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
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { HeaderActions(state: state) } }
            .sheet(item: $state.openedForm) { item in
                NativeFormModal(item: item) {
                    state.dismissBlockedForm(item.formId)
                    state.openedForm = nil
                }
            }
        }
        .navigationViewStyle(.stack)
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
                    VStack(spacing: 3) {
                        Image(systemName: tab.icon)
                            .font(.system(size: 18, weight: selected == tab ? .semibold : .regular))
                        Text(tab.label)
                            .font(.system(size: 10, weight: selected == tab ? .semibold : .regular))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundColor(selected == tab ? TesterTheme.accent : .secondary)
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .padding(.top, 8)
        .padding(.bottom, 6)
        .background(.thinMaterial)
        .overlay(Divider(), alignment: .top)
    }
}

struct HomeTabView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let userName = state.prefs.userName {
                    HStack(spacing: 12) {
                        InitialsAvatar(name: userName, size: 44)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Signed in as").font(.caption).foregroundColor(.secondary)
                            Text(userName).font(.headline)
                        }
                        Spacer()
                        Button("Edit profile") { state.screen = .editProfile(username: userName) }
                            .font(.subheadline.weight(.medium))
                            .foregroundColor(TesterTheme.accent)
                    }
                    .card()
                }

                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "Last SDK event", icon: "bolt.fill")
                    HStack(spacing: 8) {
                        Image(systemName: "waveform.path.ecg")
                            .foregroundColor(TesterTheme.accent)
                        Text(state.lastEvent)
                            .font(.footnote.monospaced())
                            .foregroundColor(.secondary)
                            .lineLimit(2)
                    }
                    .card()
                }

                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "Forms", icon: "doc.text.fill")
                    VStack(spacing: 10) {
                        Button(action: { state.showModalForm() }) {
                            Label("Show Form", systemImage: "rectangle.portrait.on.rectangle.portrait.fill")
                        }
                        .buttonStyle(PrimaryButtonStyle())

                        Button(action: { state.showPrefilledForm() }) {
                            Label("Show Form (prefilled)", systemImage: "text.badge.checkmark")
                        }
                        .buttonStyle(SecondaryButtonStyle())

                        if let interceptorFormId = state.prefs.interceptorFormId, !interceptorFormId.isEmpty {
                            Button(action: { state.showInterceptorForm() }) {
                                Label("Show Form (interceptor test)", systemImage: "hand.raised.fill")
                            }
                            .buttonStyle(SecondaryButtonStyle())
                        }
                    }
                    .card()
                }
            }
            .padding()
        }
        .screenBackground()
        .onAppear { state.trackHomeViewed() }
    }
}

private let trackEventPresets = ["button_clicked", "feature_used", "purchase_started", "survey_viewed", "home_viewed"]
private let trackScreenPresets = ["/home", "/dashboard", "/settings", "/dashboard/encatch-test"]

/// Simple left-aligned wrapping layout for preset chips (iOS 15 — no `Layout` protocol).
private struct ChipGrid: View {
    let items: [String]
    let action: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(row, id: \.self) { item in
                        Button(item) { action(item) }.buttonStyle(ChipButtonStyle())
                    }
                }
            }
        }
    }

    // Rough two-per-row chunking keeps chips readable without measuring text.
    private var rows: [[String]] {
        stride(from: 0, to: items.count, by: 2).map { Array(items[$0..<min($0 + 2, items.count)]) }
    }
}

struct EventsTabView: View {
    @ObservedObject var state: TesterState
    @State private var customEvent = "test_event"
    @State private var customScreen = "/dashboard/encatch-test"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "trackEvent presets", icon: "bolt.fill")
                    VStack(alignment: .leading, spacing: 12) {
                        ChipGrid(items: trackEventPresets) { state.track($0) }
                        Divider()
                        FieldLabel(text: "Custom event")
                        HStack(spacing: 10) {
                            TextField("event_name", text: $customEvent)
                                .textFieldStyle(FilledFieldStyle())
                                .autocapitalization(.none)
                                .disableAutocorrection(true)
                            Button("Fire") { state.track(customEvent.trimmed) }
                                .buttonStyle(SecondaryButtonStyle())
                                .frame(width: 84)
                                .disabled(customEvent.trimmed.isEmpty)
                        }
                    }
                    .card()
                }

                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "trackScreen presets", icon: "rectangle.on.rectangle")
                    VStack(alignment: .leading, spacing: 12) {
                        ChipGrid(items: trackScreenPresets) { state.trackScreen($0) }
                        Divider()
                        FieldLabel(text: "Custom screen")
                        HStack(spacing: 10) {
                            TextField("/path", text: $customScreen)
                                .textFieldStyle(FilledFieldStyle())
                                .autocapitalization(.none)
                                .disableAutocorrection(true)
                            Button("Track") { state.trackScreen(customScreen.trimmed) }
                                .buttonStyle(SecondaryButtonStyle())
                                .frame(width: 84)
                                .disabled(customScreen.trimmed.isEmpty)
                        }
                    }
                    .card()
                }
            }
            .padding()
        }
        .screenBackground()
        .onAppear { state.trackScreen("Events") }
    }
}

private struct InlineFormRepresentable: UIViewRepresentable {
    let formId: String?
    @Binding var height: CGFloat

    func makeUIView(context: Context) -> EncatchInlineFormView {
        let view = EncatchInlineFormView()
        view.formId = formId
        view.onHeightChange = { [binding = $height] newHeight in
            DispatchQueue.main.async { binding.wrappedValue = newHeight }
        }
        return view
    }

    func updateUIView(_ uiView: EncatchInlineFormView, context: Context) {}
}

/// Container for the inline SDK view. Auto-height: tracks the SDK's own reported height (0 when
/// idle, skeleton placeholder while loading, then live `form:resize` values), showing a slim
/// dashed drop-zone when no form is rendered.
private struct InlineFormSlot: View {
    let formId: String?
    @State private var height: CGFloat = 0

    var body: some View {
        InlineFormRepresentable(formId: formId, height: $height)
            .frame(height: max(height, 64))
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: TesterTheme.cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: TesterTheme.cornerRadius, style: .continuous)
                    .strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                    .foregroundColor(TesterTheme.accent.opacity(height > 64 ? 0 : 0.4))
            )
            .animation(.easeOut(duration: 0.2), value: height)
    }
}

struct InlineExactTabView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "info.circle.fill").foregroundColor(TesterTheme.accent)
                    Text("Claims \"\(state.prefs.formId ?? "")\" — only renders inline when that exact form id is shown.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                .card()

                Button(action: { state.showModalForm() }) {
                    Label("Show Exact Form (renders inline below)", systemImage: "arrow.down.doc.fill")
                }
                .buttonStyle(PrimaryButtonStyle())

                InlineFormSlot(formId: state.prefs.formId)
            }
            .padding()
        }
        .screenBackground()
        .onAppear { state.trackScreen("InlineExact") }
    }
}

struct InlineAnyTabView: View {
    @ObservedObject var state: TesterState
    @State private var wildcardFormId = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "info.circle.fill").foregroundColor(TesterTheme.accent)
                    Text("Catches any form id not exactly claimed elsewhere.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                .card()

                VStack(alignment: .leading, spacing: 10) {
                    FieldLabel(text: "Form id")
                    TextField("form id", text: $wildcardFormId)
                        .textFieldStyle(FilledFieldStyle())
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                    Button(action: { state.showForm(wildcardFormId.trimmed) }) {
                        Label("Show Form (renders inline below)", systemImage: "arrow.down.doc.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(wildcardFormId.trimmed.isEmpty)
                    Button("Trigger unmatched form → modal fallback") { state.showForm("modal-fallback-demo") }
                        .buttonStyle(QuietButtonStyle(role: TesterTheme.accent))
                }
                .card()

                InlineFormSlot(formId: nil)
            }
            .padding()
        }
        .screenBackground()
        .onAppear { state.trackScreen("InlineAny") }
    }
}

struct SettingsTabView: View {
    @ObservedObject var state: TesterState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "Current configuration", icon: "gearshape.fill")
                    VStack(spacing: 6) {
                        InfoRow(label: "Environment", value: state.prefs.environment.label)
                        Divider()
                        InfoRow(label: "Form id", value: state.prefs.formId ?? "—")
                        Divider()
                        InfoRow(label: "API base URL", value: state.prefs.apiBaseUrl ?? "(default)")
                        Divider()
                        InfoRow(label: "Web host", value: state.prefs.webHost ?? "(default)")
                        Divider()
                        InfoRow(label: "Interceptor form id", value: state.prefs.interceptorFormId ?? "(none)")
                    }
                    .card()
                }

                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "Localization", icon: "globe")
                    VStack(spacing: 10) {
                        Button(action: { state.setLocaleFrFr() }) {
                            Label("Set Locale → fr-FR", systemImage: "character.bubble")
                        }
                        .buttonStyle(SecondaryButtonStyle())
                        Button(action: { state.setCountryFr() }) {
                            Label("Set Country → FR", systemImage: "flag.fill")
                        }
                        .buttonStyle(SecondaryButtonStyle())
                    }
                    .card()
                }

                Button("Change API key & setup") { state.clearSetup() }
                    .buttonStyle(QuietButtonStyle(role: .red))
            }
            .padding()
        }
        .screenBackground()
        .onAppear { state.trackScreen("Settings") }
    }
}

struct BillingView: View {
    let route: String
    @ObservedObject var state: TesterState

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "creditcard.fill")
                .font(.system(size: 44))
                .foregroundColor(TesterTheme.accent)
            Text("Billing").font(.title2.weight(.bold))
            Text("Reached via CTA app_navigate route: \"\(route)\"")
                .font(.footnote)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            Button("Back to home") { state.screen = .main }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.top, 8)
            Spacer()
        }
        .padding(24)
        .screenBackground()
        .onAppear { state.trackScreen("Billing") }
    }
}

struct RouteNotFoundView: View {
    let route: String
    @ObservedObject var state: TesterState

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "questionmark.circle.fill")
                .font(.system(size: 44))
                .foregroundColor(.orange)
            Text("Route not found").font(.title2.weight(.bold))
            Text("The CTA requested an unmapped route: \"\(route)\"")
                .font(.footnote)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            Button("Go back") { state.screen = .main }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.top, 8)
            Spacer()
        }
        .padding(24)
        .screenBackground()
        .onAppear { state.trackScreen("RouteNotFound") }
    }
}
