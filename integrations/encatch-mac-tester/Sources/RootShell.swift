import SwiftUI
import Encatch

/// The main window: a `NavigationSplitView` sidebar (Mail/Xcode-style) replacing the iPhone
/// tester's bottom tab bar, plus an onboarding sheet host. Destination bodies are placeholders
/// for now — Phase 2/3/4 fill these in (`MacTheme`, `Onboarding`, `HomeView`, etc.).
struct RootShell: View {
    @ObservedObject var state: TesterState

    var body: some View {
        NavigationSplitView {
            List(SidebarDestination.allCases, id: \.self, selection: $state.sidebarSelection) { destination in
                Label(destination.label, systemImage: destination.icon)
                    .badge(destination == .interceptor ? state.blockedForms.count : 0)
            }
            .listStyle(.sidebar)
            .navigationTitle("Encatch Mac Tester")
        } detail: {
            VStack(spacing: 0) {
                header
                Divider()
                detailContent
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .sheet(item: $state.onboardingStep) { step in
            OnboardingSheet(state: state, step: step)
        }
    }

    /// Rendered once, always on top of every destination — not a `.toolbar` modifier, because
    /// SwiftUI's `.toolbar` merging across a switched `detail` view proved unreliable under
    /// Catalyst (items would silently disappear on some destinations, especially ones that
    /// declare their own `.toolbar`, like `LogsView` used to). A plain persistent header
    /// guarantees Theme/Logout show identically on every screen.
    private var header: some View {
        HStack {
            Text(currentDestinationTitle).font(.headline)
            Spacer()
            Button(action: { state.cycleTheme() }) {
                Label("Theme", systemImage: themeIcon)
            }
            Button("Logout") { state.logOut() }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.bar)
    }

    private var currentDestinationTitle: String {
        switch state.screen {
        case .billing: return "Billing"
        case .routeNotFound: return "Route Not Found"
        case .main: return state.sidebarSelection?.label ?? "Home"
        }
    }

    private var themeIcon: String {
        switch state.currentTheme {
        case .system: return "circle.lefthalf.filled"
        case .light: return "sun.max"
        case .dark: return "moon"
        }
    }

    @ViewBuilder
    private var detailContent: some View {
        switch state.screen {
        case .billing(let route):
            BillingView(route: route, state: state)
        case .routeNotFound(let route):
            RouteNotFoundView(route: route, state: state)
        case .main:
            switch state.sidebarSelection {
            case .home, .none: HomeView(state: state)
            case .events: EventsView(state: state)
            case .logs: LogsView(state: state)
            case .inlineExact: InlineExactView(state: state)
            case .inlineAny: InlineAnyView(state: state)
            case .interceptor: InterceptorView(state: state)
            case .settings: SettingsView(state: state)
            }
        }
    }
}

/// The Settings sidebar destination — this is a tester app, not a shipped product, so a
/// standalone Preferences window (this app briefly had one, via a second `WindowGroup` standing
/// in for the Catalyst-unavailable `Settings{}` Scene) was more ceremony than it was worth.
/// Ported from `encatch-ios-tester`'s `SettingsScreen`, restyled onto `Form(.formStyle(.grouped))`
/// + `LabeledContent`.
struct SettingsView: View {
    @ObservedObject var state: TesterState
    @State private var showLocaleSheet = false
    @State private var showCountrySheet = false

    var body: some View {
        Form {
            Section("Setup") {
                LabeledContent("Environment", value: state.prefs.environment.label)
                LabeledContent("Form id", value: state.prefs.formId ?? "(none)")
                LabeledContent("API base URL", value: state.prefs.apiBaseUrl ?? "(default)")
                LabeledContent("Web host", value: state.prefs.webHost ?? "(default)")
                LabeledContent("Interceptor form id", value: state.prefs.interceptorFormId ?? "(none)")
            }
            Section("Region") {
                LabeledContent("Locale", value: state.appliedLocale ?? "(device default)")
                LabeledContent("Country", value: state.appliedCountry ?? "(unset)")
                Button("Set Locale…") { showLocaleSheet = true }
                Button("Set Country…") { showCountrySheet = true }
            }
            Section {
                Button("Change API Key & Setup…") { state.clearSetup() }
                    .foregroundColor(.red)
            }
        }
        .formStyle(.grouped)
        .frame(maxWidth: 480)
        .frame(maxWidth: .infinity, alignment: .top)
        .sheet(isPresented: $showLocaleSheet) {
            LocaleInputSheet(
                title: "Set Locale",
                placeholder: "e.g. fr-FR, hi-IN",
                input: state.appliedLocale ?? "",
                onApply: { state.setLocale($0) }
            )
        }
        .sheet(isPresented: $showCountrySheet) {
            LocaleInputSheet(
                title: "Set Country",
                placeholder: "e.g. FR, IN",
                input: state.appliedCountry ?? "",
                onApply: { state.setCountry($0) }
            )
        }
    }
}

/// Modal prompting for a locale/country code to switch the SDK to — same sheet the
/// iOS testers use. Apply is disabled while the input is blank.
struct LocaleInputSheet: View {
    let title: String
    let placeholder: String
    @State var input: String
    let onApply: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    private var trimmed: String { input.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationView {
            VStack(alignment: .leading, spacing: 12) {
                TextField(placeholder, text: $input)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                Text("Applies to the next form shown — an already-open form keeps its language.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                Spacer()
            }
            .padding()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") { onApply(trimmed); dismiss() }
                        .disabled(trimmed.isEmpty)
                }
            }
        }
        .frame(minWidth: 360, minHeight: 220)
    }
}

extension OnboardingStep: Identifiable {
    var id: String {
        switch self {
        case .setup: return "setup"
        case .login: return "login"
        case .editProfile(let username): return "editProfile-\(username)"
        }
    }
}
