package com.encatch.composetester

sealed class Screen {
    data object Setup : Screen()
    data object Login : Screen()
    data object Main : Screen()
    data class EditProfile(val username: String) : Screen()
    data class Billing(val route: String) : Screen()
    data class RouteNotFound(val route: String) : Screen()
}

/** Bottom-nav destinations inside [Screen.Main]. No Inline (Any) tab — `:compose-sdk`'s
 * `EncatchInlineForm` has no wildcard slot yet (see `README.md`'s Known gap). */
enum class TesterTab(val label: String) {
    HOME("Home"),
    EVENTS("Events"),
    SETTINGS("Settings"),
    INLINE_EXACT("Inline (Exact)"),
}
