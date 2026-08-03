# encatch-compose-tester

Standalone Compose Multiplatform app for testing `com.encatch:compose-sdk` — the simplest of the
four testers, since one shared `commonMain` Compose UI (`App.kt`) drives both platforms directly:
`ComposeUIViewController` on iOS, `setContent` on Android. No platform-specific screen code at
all, unlike [`encatch-kmp-tester`](../encatch-kmp-tester/README.md) (which needs native UI per
platform because `:kmp-sdk` itself has no UI layer).

Modeled on [`encatch-android-tester`](../encatch-android-tester/README.md)/
[`encatch-ios-tester`](../encatch-ios-tester/README.md): a runtime **Setup** screen
(`TesterPrefs`, an `expect`/`actual` object — `SharedPreferences` on Android, `NSUserDefaults` on
iOS) so one build works for any tester or environment.

Depends on `:compose-sdk`/`:android` as internal Gradle modules (these SDKs aren't published to a
real Maven repo yet — see [`compose-sdk/README.md`](../../compose-sdk/README.md)).

## Known gap (relative to encatch-android-tester/encatch-ios-tester)

- **No wildcard inline slot** — `:compose-sdk`'s `EncatchInlineForm(formId: String, ...)` takes a
  non-null `formId`; only the exact-match case is exposed as a composable.

## Run in an emulator (Android)

```bash
./gradlew :integrations:encatch-compose-tester:installDebug
```

## Build an APK to send to a tester (Android)

```bash
./gradlew :integrations:encatch-compose-tester:assembleDebug
# APK: integrations/encatch-compose-tester/build/outputs/apk/debug/encatch-compose-tester-debug.apk
```

## iOS

This module also builds `EncatchComposeTester.xcframework` (Kotlin/Native, embedding Compose
Multiplatform's iOS runtime), consumed by the
[`encatch-compose-tester-ios`](../encatch-compose-tester-ios/README.md) host app — see that
directory for build/run instructions.

```bash
./gradlew :integrations:encatch-compose-tester:assembleEncatchComposeTesterDebugXCFramework
```

## Features

- **Setup** — API key, default form id, optional API base URL / web host / interceptor test form
  id. Saved locally and restored on next launch until cleared from Settings.
- **Login** — Mock login calls `Encatch.identifyUser(username)`.
- **Home** — Tracks `home_viewed` on load. **Show form (modal)** calls `showForm` with the
  configured default form id. **Interceptor test** (shown only if an interceptor form id was set)
  calls `showForm` for that id, which is held by `EncatchConfig.onBeforeShowForm` until you answer
  the on-screen Allow/Deny dialog — demonstrates replacing the SDK form with native UI.
- **Events** — Buttons for `button_clicked`, `feature_used`, `purchase_started`, `survey_viewed`.
- **Inline** — `EncatchInlineForm` claiming the default form id (exact-match only, see gap above).
- **Settings** — **Log out** calls `resetUser()`. **Clear saved setup** calls `clearAll()` and
  wipes local prefs, returning to Setup.
- **Screen tracking** — Each screen calls `trackScreen(...)`.
- **CTA navigation** — A single `Encatch.on(...)` listener registered at app start watches for
  `form:ctaTriggered` events with `action == "app_navigate"`: routes
  `"billing"`/`"billing/upgrade"` navigate to the in-app **Billing** screen; any other route
  navigates to **Route not found**.

## Manual test checklist

Same as [`encatch-android-tester`'s checklist](../encatch-android-tester/README.md#manual-test-checklist)
minus the wildcard-inline step (step 6) — run it on both the Android app and the
[`encatch-compose-tester-ios`](../encatch-compose-tester-ios/README.md) host to confirm both
platforms behave identically off the same shared Compose UI.
