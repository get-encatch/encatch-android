# Encatch

Collect user feedback in native and cross-platform mobile apps — display feedback forms, identify
users, track screens and events, and submit responses to the Encatch backend. Functionally
equivalent to [`@encatch/react-native-sdk`](https://www.npmjs.com/package/@encatch/react-native-sdk).

Two independent, fully native SDKs — one per platform, not a shared cross-platform core — plus two
thin bridge libraries for customers building with Compose Multiplatform or Kotlin Multiplatform:

| Package | Platform | Install | What it is |
|---|---|---|---|
| [`com.encatch:android`](android/README.md) | Android (native Views) | Maven Central | The real Android SDK. |
| [`Encatch` (Swift Package)](swift/README.md) | iOS (native Swift/UIKit) | SPM via [get-encatch/encatch-swift](https://github.com/get-encatch/encatch-swift) | The real iOS SDK. Zero Kotlin dependency. |
| [`com.encatch:kmp-sdk`](kmp-sdk/README.md) | KMP (Android + iOS) | Maven Central | Thin bridge onto the two native SDKs above — full API, no UI. |
| [`com.encatch:compose-sdk`](compose-sdk/README.md) | Compose Multiplatform (Android + iOS) | Maven Central | `:kmp-sdk` + a ready-made inline-form composable. |

`com.encatch:core` is the platform-agnostic Kotlin business logic backing `:android` and
`:kmp-sdk` on Android — most apps don't depend on it directly (see [`core/README.md`](core/README.md)).

## Which package do I want?

- **Plain native Android app** → `com.encatch:android`.
- **Plain native iOS app (Swift/UIKit or SwiftUI)** → the `swift` Swift Package.
- **Kotlin Multiplatform app, no Compose UI** → `com.encatch:kmp-sdk`.
- **Compose Multiplatform app (Android + iOS from one UI)** → `com.encatch:compose-sdk`.

Each package's README has full installation and usage instructions.

## Why two native SDKs instead of one shared core?

Android's native language is Kotlin, so `:android`/`:core` are already a real native
implementation there. iOS gets the same treatment — `swift/` is genuinely native Swift (own
networking, storage, session management), not a Kotlin/Native UI wrapped in Swift syntax. Every
other integration (`:kmp-sdk`, `:compose-sdk`, and any future one — React Native, Flutter) is a
thin bridge onto these two, never a third reimplementation. This mirrors how other mobile SDK
providers (e.g. Refiner) structure theirs, and it's what lets a Compose Multiplatform or KMP
customer get the same "few lines, no plumbing" experience a plain native customer already gets.

## Repo layout

- `core/`, `android/` — the native Android SDK.
- `swift/` — the native iOS SDK (Swift Package).
- `kmp-sdk/`, `compose-sdk/` — the cross-platform bridge libraries.
- `mock-server/` — a local mock backend for development/testing (`./gradlew :mock-server:run`).
- `examples/` — minimal example apps exercising each package above (`sample-app`, `compose-sample`,
  `kmp-sample`, `ios-sample`, `ios-compose-sample`, `ios-kmp-sample`); not part of the published
  SDKs.
- `integrations/` — standalone tester apps with a runtime setup screen, buildable to an APK/build
  you can hand to a real tester (see [`integrations/README.md`](integrations/README.md)).
- `scripts/test-all-variants.sh` — one-command build/test/screenshot pass across every example app.

## License

MIT
