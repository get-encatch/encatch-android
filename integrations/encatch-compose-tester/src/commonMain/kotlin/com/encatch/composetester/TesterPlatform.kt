package com.encatch.composetester

/** Which platform this Compose Multiplatform tester build is running on — shown in Settings. */
expect val testerPlatformName: String

/**
 * Developer-local Setup-screen defaults baked in at build time from the git-ignored root
 * `dev-tester-defaults.properties` (Android BuildConfig; empty on iOS for now and when the
 * file is absent). Prefill only — the Setup fields stay editable.
 */
expect val devDefaultApiKey: String
expect val devDefaultFormId: String
