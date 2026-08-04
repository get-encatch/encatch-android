/// The main window's content — unlike the iPhone tester, Setup/Login/EditProfile are never a
/// `Screen` case here: they're a sheet (`TesterState.onboardingStep`) presented over an
/// always-visible `RootShell`, matching how Mac apps present one-time setup without hiding their
/// own chrome. `Screen` only covers CTA-driven navigation that replaces the split view's detail.
enum Screen: Equatable {
    case main
    case billing(route: String)
    case routeNotFound(route: String)
}

/// Onboarding sheet steps — Setup (env/credentials) → Login (pick/save a test user) →
/// EditProfile (optional, before signing in).
enum OnboardingStep: Equatable {
    case setup
    case login
    case editProfile(username: String)
}

/// Sidebar destinations inside the main `NavigationSplitView` — replaces the iPhone tester's
/// bottom `TesterTab`. Interceptor is its own row (with a badge) rather than a tab-docked
/// carousel, since the sidebar already gives blocked forms a persistent, always-visible place to
/// surface — see the plan's rationale in `stateless-floating-ripple.md`.
enum SidebarDestination: Hashable, CaseIterable {
    case home, events, logs, inlineExact, inlineAny, interceptor, settings

    var label: String {
        switch self {
        case .home: return "Home"
        case .events: return "Events"
        case .logs: return "Logs"
        case .inlineExact: return "Inline (Exact)"
        case .inlineAny: return "Inline (Any)"
        case .interceptor: return "Interceptor"
        case .settings: return "Settings"
        }
    }

    /// SF Symbol shown in the sidebar row.
    var icon: String {
        switch self {
        case .home: return "house"
        case .events: return "bolt"
        case .logs: return "list.bullet.rectangle"
        case .inlineExact: return "square.grid.2x2"
        case .inlineAny: return "square.dashed"
        case .interceptor: return "hand.raised"
        case .settings: return "gearshape"
        }
    }
}
