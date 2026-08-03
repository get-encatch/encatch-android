package com.encatch.composetester

sealed class Screen {
    data object Setup : Screen()
    data object Login : Screen()
    data object Home : Screen()
    data object Events : Screen()
    data object Inline : Screen()
    data object Settings : Screen()
    data class Billing(val route: String) : Screen()
    data class RouteNotFound(val route: String) : Screen()
}
