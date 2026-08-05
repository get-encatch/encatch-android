# encatch-kmp-tester

Standalone Kotlin Multiplatform app for testing `com.encatch:kmp-sdk` — modeled on
[`encatch-android-tester`](../encatch-android-tester/README.md) and
[`encatch-ios-tester`](../encatch-ios-tester/README.md), but this time one `commonMain` layer
(`TesterController.kt`) drives two fully-native UIs: a plain-Views Android `Activity`
(no Compose — see below) and a SwiftUI host in
[`encatch-kmp-tester-ios`](../encatch-kmp-tester-ios/README.md).

Unlike `kmp-sample` (this repo's minimal manual-verification harness, wrapped inside `sample-app`
via `android-library`), this module applies `com.android.application` directly and is a real
standalone, installable Android app in its own right — plus a runtime **Setup** screen so one
build works for any tester or environment, no rebuild required.

Depends on `:kmp-sdk`/`:android` as internal Gradle modules (external apps use the published
Maven Central artifacts instead — see [`kmp-sdk/README.md`](../../kmp-sdk/README.md)).

## Run in an emulator (Android)

```bash
./gradlew :integrations:encatch-kmp-tester:installDebug
```

## Build an APK to send to a tester (Android)

```bash
./gradlew :integrations:encatch-kmp-tester:assembleDebug
# APK: integrations/encatch-kmp-tester/build/outputs/apk/debug/encatch-kmp-tester-debug.apk
```

## iOS

This module also builds `EncatchKmpTester.xcframework` (Kotlin/Native), consumed by the
[`encatch-kmp-tester-ios`](../encatch-kmp-tester-ios/README.md) host app — see that directory for
build/run instructions.

```bash
./gradlew :integrations:encatch-kmp-tester:assembleEncatchKmpTesterDebugXCFramework
```

## Features

Same feature set as `encatch-android-tester`/`encatch-ios-tester` (see that README for the fuller
narrative — this one calls out only what's different since both native UIs go through the shared
`TesterController.kt` instead of the SDK directly):

- **Setup** — environment picker (Dev/UAT/Prod), API key, default form id, optional interceptor
  test form id. Saved locally (`SharedPreferences` on Android, `UserDefaults` on iOS).
- **Login** — saved test users list (local, independent of the SDK) → **Identify user** calls
  `TesterController.identify(userName, email, displayName)`.
- **Edit profile** — email/display name, re-calls `identify` if that user is currently signed in.
- **Bottom tab navigation** — Home / Events / Settings / Inline (Any) / Inline (Exact).
- **Header** — theme cycle button (`TesterController.cycleTheme()`, returns the new theme name so
  neither platform UI needs to import `:kmp-sdk`'s `Theme` type directly) and Logout.
- **Home** — **Show Form**, **Show Form (prefilled)** (`showPrefilledForm`, wraps `addToResponse`+
  `showForm`), and **Show Form (interceptor test)** when an interceptor form id is set.
- **Events** — `trackEvent` presets + custom, `trackScreen` presets + custom.
- **Interceptor carousel + custom native form** — `TesterController.initSdk`'s `onIntercept`
  callback unconditionally blocks the configured interceptor form id and hands the platform UI a
  `(formId, formConfigJson, completion)` triple; both UIs queue it into a dismissible card
  carousel, then render a fully custom 3-step form (welcome → questions → thank-you) parsed from
  `formConfigJson` and submitted via `TesterController.submitNativeForm`/`emitEvent`/`dismissForm`
  — the same custom-render pattern `encatch-android-tester`/`encatch-ios-tester` use, now backed by
  `:kmp-sdk`'s own `formConfigJson`/`buildSubmitRequest` (previously a gap in `:kmp-sdk` itself,
  closed as part of this parity pass — see `kmp-sdk/src/commonMain/kotlin/com/encatch/sdk/Types.kt`
  and `FormSubmitBuilder.kt`). `onIntercept` stays a plain (non-`suspend`) callback rather than a
  `suspend` function type, since Kotlin/Native's ObjC export doesn't turn `suspend` function-type
  *parameters* into completion-handler methods the way it does for `suspend` member functions —
  the platform UI calls `completion(allow)` whenever it has an answer (here, always `false`), and a
  `suspendCancellableCoroutine` on the Kotlin side converts that back into the suspend result
  `:kmp-sdk`'s interceptor needs.
- **Inline (Exact) / Inline (Any)** — the real native inline view embedded directly (`:android`'s
  `EncatchInlineFormView` / `ios-native`'s via this module's own cinterop bridge) — a known gap in
  `:kmp-sdk` itself, same one `kmp-sample` already has (see `build.gradle.kts`'s comment).
- **Settings** — environment/form id summary, **Set Locale → fr-FR** / **Set Country → FR**
  (`TesterController.setLocale`/`setCountry`), **Change API key & setup**.
- **Screen tracking** — Each screen calls `trackScreen(...)`.
- **CTA navigation** — A single `TesterController.onEvent(...)` listener registered at app start
  watches for `form:ctaTriggered` events with `action == "app_navigate"`: routes
  `"billing"`/`"billing/upgrade"` navigate to the in-app **Billing** screen; any other route
  navigates to **Route not found**.

## Manual test checklist

Same steps as [`encatch-android-tester`'s checklist](../encatch-android-tester/README.md#manual-test-checklist) —
run it on both the Android app and the
[`encatch-kmp-tester-ios`](../encatch-kmp-tester-ios/README.md) host to confirm both native UIs
behave identically off the same shared `TesterController`.
