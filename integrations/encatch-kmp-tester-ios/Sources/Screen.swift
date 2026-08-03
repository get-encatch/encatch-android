enum Screen {
    case setup
    case login
    case main
    case editProfile(username: String)
    case billing(route: String)
    case routeNotFound(route: String)
}

/// Bottom-tab destinations inside `.main`.
enum TesterTab: CaseIterable {
    case home, events, settings, inlineAny, inlineExact

    var label: String {
        switch self {
        case .home: return "Home"
        case .events: return "Events"
        case .settings: return "Settings"
        case .inlineAny: return "Inline (Any)"
        case .inlineExact: return "Inline (Exact)"
        }
    }
}
