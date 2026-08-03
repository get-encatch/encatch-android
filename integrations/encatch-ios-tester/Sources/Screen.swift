enum Screen {
    case setup
    case login
    case home
    case events
    case inline
    case settings
    case billing(route: String)
    case routeNotFound(route: String)
}
