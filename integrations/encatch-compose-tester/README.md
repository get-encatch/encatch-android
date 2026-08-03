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
  non-null `formId`; only the exact-match case is exposed as a composable. There is accordingly no
  Inline (Any) tab — the bottom nav has Home/Events/Settings/Inline (Exact) only.

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

Modeled on the richer `encatch-flutter-tester` reference app (see
`/Users/godwin/Desktop/cmss/projects/schema-definition/sdk/integrations/encatch-flutter-tester`
in the sibling schema-definition repo), minus the wildcard inline slot noted above:

- **Setup** — environment picker (Dev/UAT/Prod, each a preset `apiBaseUrl`/`webHost` pair), API
  key, default form id, optional interceptor test form id. Saved locally and restored on next
  launch until cleared.
- **Login** — a locally-saved **test users** list (username/email/display name — independent of
  the SDK, its own persisted JSON blob) you can add to, select from, and edit before signing in.
  **Identify user** calls `Encatch.identifyUser(username, traits)`.
- **Edit profile** — email/display name → `identifyUser`'s `UserTraits(set = ...)`.
- **Bottom navigation** — Home / Events / Settings / Inline (Exact).
- **Header** — a tap-to-cycle theme button (`Encatch.setTheme`/`.theme`, system → light → dark) and
  **Logout** (`resetUser()`), shared across every tab.
- **Home** — Tracks `home_viewed` on load. **Show Form** calls `showForm` with the default form id.
  **Show Form (prefilled)** calls `addToResponse(...)` before `showForm`. **Show Form (interceptor
  test)** (shown only if an interceptor form id was set) calls `showForm` for that id — see
  Interceptor below.
- **Events** — `trackEvent` presets (`button_clicked`, `feature_used`, `purchase_started`,
  `survey_viewed`, `home_viewed`) + a custom-name field, and `trackScreen` presets (`/home`,
  `/dashboard`, `/settings`, `/dashboard/encatch-test`) + a custom-path field.
- **Interceptor carousel + custom native form** — `EncatchConfig.onBeforeShowForm` unconditionally
  blocks the configured interceptor form id and queues it as a floating, dismissible card in an
  `InterceptorCarousel`. Tapping a card opens `NativeFormModal`: a fully custom, non-WebView
  3-step form (welcome → questions → thank-you) parsed from the interceptor payload's
  `formConfigJson`, submitted via `buildSubmitRequest`/`Encatch.submitForm` and driven end-to-end
  with manual `Encatch.emitEvent(FORM_SHOW/STARTED/SUBMIT/COMPLETE/CLOSE)` calls — demonstrates
  fully replacing the SDK's own rendering with native UI, now backed by `:kmp-sdk`'s own
  `formConfigJson`/`buildSubmitRequest` (previously a gap in `:kmp-sdk` itself, closed as part of
  this parity pass).
- **Inline (Exact)** — `EncatchInlineForm` claiming the default form id (exact-match only).
- **Settings** — environment/form id/interceptor id summary, **Set Locale → fr-FR**
  (`setLocale`), **Set Country → FR** (`setCountry`), and **Change API key & setup** (clears local
  config and SDK state, returns to Setup).
- **Screen tracking** — Each screen calls `trackScreen(...)`.
- **CTA navigation** — A single `Encatch.on(...)` listener registered at app start watches for
  `form:ctaTriggered` events with `action == "app_navigate"`: routes
  `"billing"`/`"billing/upgrade"` navigate to the in-app **Billing** screen; any other route
  navigates to **Route not found**.

## Manual test checklist

Same as [`encatch-android-tester`'s checklist](../encatch-android-tester/README.md#manual-test-checklist)
minus the wildcard-inline step — run it on both the Android app and the
[`encatch-compose-tester-ios`](../encatch-compose-tester-ios/README.md) host to confirm both
platforms behave identically off the same shared Compose UI.
