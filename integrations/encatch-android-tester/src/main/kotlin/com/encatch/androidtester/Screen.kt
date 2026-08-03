package com.encatch.androidtester

sealed class Screen {
    data object Setup : Screen()
    data object Login : Screen()
    data object Main : Screen()
    data class EditProfile(val username: String) : Screen()
    data class Billing(val route: String) : Screen()
    data class RouteNotFound(val route: String) : Screen()
}

/** Bottom-nav destinations inside [Screen.Main]. */
enum class TesterTab(val label: String) {
    HOME("Home"),
    EVENTS("Events"),
    SETTINGS("Settings"),
    INLINE_ANY("Inline (Any)"),
    INLINE_EXACT("Inline (Exact)"),
}
