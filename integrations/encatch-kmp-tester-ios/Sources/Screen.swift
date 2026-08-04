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
    case home, events, logs, settings, inlineAny, inlineExact

    var label: String {
        switch self {
        case .home: return "Home"
        case .events: return "Events"
        case .logs: return "Logs"
        case .settings: return "Settings"
        case .inlineAny: return "Inline (Any)"
        case .inlineExact: return "Inline (Exact)"
        }
    }

    /// SF Symbol shown in the bottom tab bar.
    var icon: String {
        switch self {
        case .home: return "house.fill"
        case .events: return "bolt.fill"
        case .logs: return "list.bullet.rectangle"
        case .settings: return "gearshape.fill"
        case .inlineAny: return "square.dashed"
        case .inlineExact: return "square.grid.2x2.fill"
        }
    }
}
