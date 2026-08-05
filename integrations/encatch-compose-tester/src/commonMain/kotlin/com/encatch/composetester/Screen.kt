package com.encatch.composetester

sealed class Screen {
    data object Setup : Screen()
    data object Login : Screen()
    data object Main : Screen()
    data class EditProfile(val username: String) : Screen()
    data class Billing(val route: String) : Screen()
    data class RouteNotFound(val route: String) : Screen()
}

/** Bottom-nav destinations inside [Screen.Main].
 *
 * [shortLabel] is what the bottom bar renders — six equal-width slots on a phone don't fit
 * "Inline (Exact)"/"Inline (Any)" at a legible size; the full [label] stays as the header
 * title. */
enum class TesterTab(val label: String, val shortLabel: String = label) {
    HOME("Home"),
    EVENTS("Events"),
    LOGS("Logs"),
    SETTINGS("Settings"),
    INLINE_EXACT("Inline (Exact)", shortLabel = "Exact"),
    INLINE_ANY("Inline (Any)", shortLabel = "Any"),
}
