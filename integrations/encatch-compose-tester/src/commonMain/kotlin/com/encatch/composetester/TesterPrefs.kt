package com.encatch.composetester

/** Local persistence for the setup screen, so one build works for any tester/environment. */
expect object TesterPrefs {
    var apiKey: String?
    var formId: String?
    var apiBaseUrl: String?
    var webHost: String?
    var userName: String?
    val isSetupComplete: Boolean
    fun clear()
}
