# encatch-kmp-tester-ios

Thin SwiftUI host app for [`encatch-kmp-tester`](../encatch-kmp-tester/README.md)'s
`EncatchKmpTester.xcframework` (Kotlin/Native) — the iOS side of the `:kmp-sdk` tester.

All SDK calls and screen-navigation logic live in the shared `commonMain` `TesterController`
(compiled into the framework); this app's Swift code (`TesterState.swift`) is mostly a thin
`ObservableObject` wrapper that calls `TesterController.shared`'s functions and holds
`UserDefaults`-backed setup state — structurally identical to
[`encatch-ios-tester`](../encatch-ios-tester/README.md)'s `TesterState`, just backed by Kotlin
instead of pure Swift. Kotlin/Native exports `TesterController`'s `suspend` functions as
completion-handler methods, which Swift's importer automatically bridges to `async throws` — no
manual bridging code needed (confirmed by this app building and running against the real
framework).

## Build and run

Requires [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```bash
cd integrations/encatch-kmp-tester-ios
./build.sh              # builds encatch-kmp-tester's XCFramework first if missing, then the app
./build.sh <DEVICE_UDID> # or target a specific simulator
```

This generates `EncatchKmpTester.xcodeproj` via `xcodegen` and builds for the iOS Simulator. Open
the generated `.xcodeproj` in Xcode to run/debug interactively.

If you change `encatch-kmp-tester`'s Kotlin source, rebuild the framework before re-running
`build.sh` (it only rebuilds when the XCFramework directory is missing):

```bash
(cd ../.. && ./gradlew :integrations:encatch-kmp-tester:assembleEncatchKmpTesterDebugXCFramework)
```

## Manual test checklist

Same as [`encatch-kmp-tester`'s checklist](../encatch-kmp-tester/README.md#manual-test-checklist) —
run it here to confirm the iOS side behaves identically to the Android app off the same shared
`TesterController`.
