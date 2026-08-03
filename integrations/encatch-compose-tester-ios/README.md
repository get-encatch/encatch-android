# encatch-compose-tester-ios

Thin host app for [`encatch-compose-tester`](../encatch-compose-tester/README.md)'s
`EncatchComposeTester.xcframework` (Kotlin/Native + Compose Multiplatform) — the iOS side of the
`:compose-sdk` tester.

Unlike [`encatch-ios-tester`](../encatch-ios-tester/README.md) and
[`encatch-kmp-tester-ios`](../encatch-kmp-tester-ios/README.md), this app has essentially no Swift
code of its own: every screen, the Setup persistence, and all SDK calls live entirely in
`encatch-compose-tester`'s shared `commonMain` Compose UI. `App.swift` just wraps the framework's
`ComposeTesterViewControllerKt.ComposeTesterViewController()` in a
`UIViewControllerRepresentable` — mirrors `ios-compose-sample`'s `App.swift`.

## Build and run

Requires [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```bash
cd integrations/encatch-compose-tester-ios
./build.sh              # builds encatch-compose-tester's XCFramework first if missing, then the app
./build.sh <DEVICE_UDID> # or target a specific simulator
```

This generates `EncatchComposeTester.xcodeproj` via `xcodegen` and builds for the iOS Simulator.
Open the generated `.xcodeproj` in Xcode to run/debug interactively.

If you change `encatch-compose-tester`'s Kotlin source, rebuild the framework before re-running
`build.sh` (it only rebuilds when the XCFramework directory is missing):

```bash
(cd ../.. && ./gradlew :integrations:encatch-compose-tester:assembleEncatchComposeTesterDebugXCFramework)
```

## Manual test checklist

Same as [`encatch-compose-tester`'s checklist](../encatch-compose-tester/README.md#manual-test-checklist) —
run it here to confirm the iOS side behaves identically to the Android app off the same shared
Compose UI.
