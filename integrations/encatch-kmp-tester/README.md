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

Depends on `:kmp-sdk`/`:android` as internal Gradle modules (these SDKs aren't published to a real
Maven repo yet — see [`kmp-sdk/README.md`](../../kmp-sdk/README.md)).

## Known gap: no interceptor screen

`:kmp-sdk`'s `EncatchConfig` doesn't expose `onBeforeShowForm` yet (see
`src/commonMain/kotlin/com/encatch/kmptester/TesterController.kt`'s doc comment) — unlike the
android/ios-native testers, this app has no interceptor demo. Add the field to `:kmp-sdk`'s
`EncatchConfig`/`Encatch.ios.kt` first if you need to test that path here.

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

Same feature set as `encatch-android-tester`/`encatch-ios-tester` minus the interceptor demo (see
above):

- **Setup** — API key, default form id, optional API base URL / web host. Saved locally
  (`SharedPreferences` on Android, `UserDefaults` on iOS) and restored on next launch until
  cleared from Settings.
- **Login** — Mock login calls `TesterController.identify(userName)`.
- **Home** — Tracks `home_viewed` on load. **Show form (modal)** calls `showForm` with the
  configured default form id.
- **Events** — Buttons for `button_clicked`, `feature_used`, `purchase_started`, `survey_viewed`.
- **Inline** — An exact-match `EncatchInlineFormView` claiming the default form id, and a wildcard
  slot you can target by typing any other form id. Both platforms embed the real native inline
  view directly (`:android`'s `EncatchInlineFormView` / `ios-native`'s via this module's own
  cinterop bridge) — a known gap in `:kmp-sdk` itself, same one `kmp-sample` already has (see
  `build.gradle.kts`'s comment).
- **Settings** — **Log out** calls `resetUser()`. **Clear saved setup** calls `clearAll()` and
  wipes local prefs, returning to Setup.
- **Screen tracking** — Each screen calls `trackScreen(...)`.
- **CTA navigation** — A single `TesterController.onEvent(...)` listener registered at app start
  watches for `form:ctaTriggered` events with `action == "app_navigate"`: routes
  `"billing"`/`"billing/upgrade"` navigate to the in-app **Billing** screen; any other route
  navigates to **Route not found**.

## Manual test checklist

Same steps as [`encatch-android-tester`'s checklist](../encatch-android-tester/README.md#manual-test-checklist)
minus the interceptor step (step 4) — run it on both the Android app and the
[`encatch-kmp-tester-ios`](../encatch-kmp-tester-ios/README.md) host to confirm both native UIs
behave identically off the same shared `TesterController`.
