# Session handoff — automated variant testing infra

**Purpose of this file**: context is about to compact. This documents exactly where things stand
so work can resume without re-deriving anything. Delete this file once it's no longer needed —
it's a working note, not permanent repo documentation.

## Where we are

Building a **one-command automated test suite** that drives every real-world SDK integration
"variant" against a mocked backend, capturing real emulator/simulator screenshots. This follows
(and depends on) the SDK itself being complete: `:core` (KMP, Android+iOS+Desktop targets),
`:android` (classic Views), `swift/` (native Swift package) — all of that is done and working,
committed prior to this phase.

Plan file (still accurate, was written for this phase): `/Users/godwin/.claude/plans/stateless-floating-ripple.md`

**5 target variants** (clarified with user earlier this session):
1. Android native Views — **done**
2. Android Jetpack Compose (wraps `:android` via `AndroidView`) — **done**
3. iOS native Swift/UIKit (wraps the `swift/` package) — **done**
4. Compose Multiplatform host, Android+iOS — **not started**. Interop-only: wrap the *existing*
   native components (`AndroidView` on Android, `UIKitViewController` on iOS wrapping
   `EncatchFormViewController`/`EncatchInlineFormView`) — no WebView reimplementation, no new
   third-party dependency, low risk.
5. KMP host sample app, Android+iOS — **not started**. A sample app that's itself a Kotlin
   Multiplatform project (not just single-platform apps depending on KMP `:core`), validating the
   real Gradle/XCFramework consumption path a customer's own KMP app would use.
6. One-command orchestrator script tying all 5 together — **not started**.

## Task list state (if TaskList tool is available, these are the live tracked tasks)

| # | Task | Status |
|---|---|---|
| 14 | `:mock-server` module | ✅ done |
| 15 | Variant 1: Android Views | ✅ done |
| 16 | Variant 2: Android Compose | ✅ done |
| 17 | Variant 3: iOS native Swift | ✅ done |
| 18 | Variant 4: Compose Multiplatform interop sample | ⬜ not started |
| 19 | Variant 5: KMP host sample app | ⬜ not started |
| 20 | Orchestrator script + full verification | ⬜ not started |

## Commits so far (this phase), newest first

```
dd939c0 Include form:resize in mock hosted-form page (missed in previous commit)
c3c6ed0 Variant 3: commit real ios-sample project; fix 3 real bugs it found
40539b2 Variant 2: Android Jetpack Compose sample screen + screenshot test
2890bb8 Variant 1: Android Views screenshot test against :mock-server
c519a34 Add :mock-server module for offline/deterministic variant testing
```
All pushed? **No** — not yet pushed to `origin/main`. Working tree is clean as of now.

## Critical technical facts for continuing

### Mock server
- `mock-server/` — Ktor server, `./gradlew :mock-server:run` (port 8089, override via first CLI arg).
- Implements all 10 endpoints from `core/.../ApiClient.kt`'s `Endpoints` object with canned JSON.
  `show-form` returns `{"feedbackConfigurationId":"mock-config-1","appearanceProperties":{}}`.
- Serves the hosted-form stub page at `/s/react-native-sdk-form` from
  `mock-server/src/main/resources/react-native-sdk-form.html` — implements the real
  `window.ReactNativeWebView.postMessage` JS-bridge contract: fires `form:ready` then
  `form:resize` (**important** — a real form always reports height; forgetting this leaves the
  native modal/inline container stuck at 0 height, invisible even if nothing else is wrong) on
  load, renders a "Submit" button firing `form:submit` → `form:complete` shortly after.
- `EncatchConfig` already has `apiBaseUrl`/`webHost` overrides — **no `:core` changes needed** to
  point any variant at the mock server.
- **Android emulator** reaches the host Mac via `http://10.0.2.2:8089`.
- **iOS Simulator** reaches the host Mac directly via `http://127.0.0.1:8089` (shares host network
  namespace, no alias needed).

### Variant 1 & 2 (Android, in `sample-app/`)
- `sample-app/build.gradle.kts`: `BuildConfig.MOCK_SERVER_BASE_URL` field, set via Gradle property
  `-PmockServerBaseUrl=http://10.0.2.2:8089` (empty by default — doesn't affect normal dev use).
- `sample-app/src/debug/AndroidManifest.xml`: debug-only `usesCleartextTraffic="true"` override so
  the emulator can reach the mock server's `http://` URL.
- `ScreenshotFlowTest.kt` (Variant 1) / `ComposeScreenshotFlowTest.kt` (Variant 2): UiAutomator
  device-screenshot-based tests (not Espresso view-matchers) — take a real screenshot at each step,
  written to the app's external files dir (`/sdcard/Android/data/com.encatch.sampleapp/files/screenshots[-compose]/`).
- **Gotcha**: `./gradlew :sample-app:connectedDebugAndroidTest` auto-uninstalls the app after the
  test run, so you can't `adb pull` screenshots afterward. Instead: build APKs
  (`assembleDebug assembleDebugAndroidTest`), `adb install` both manually, run via
  `adb shell am instrument -w com.encatch.sampleapp.test/androidx.test.runner.AndroidJUnitRunner`,
  *then* `adb pull`, *then* uninstall manually. The orchestrator script (task 20) needs to do this
  dance, not just call `connectedAndroidTest`.
- `ComposeMainActivity.kt` added alongside the original `MainActivity.kt` (both present, separate
  launcher entries) — Compose screen wraps `EncatchInlineFormView`/modal via `AndroidView`.

### Variant 3 (`ios-sample/`)
- Built via **xcodegen** (`brew install xcodegen`, already installed on this machine) —
  `ios-sample/project.yml` is the source of truth; `ios-sample/build.sh` regenerates
  `EncatchSample.xcodeproj` (gitignored, not committed) and builds. `ios-sample/test.sh` runs the
  XCUITest target.
- **Gotcha**: `:core`'s debug XCFramework only has an `iosSimulatorArm64` slice (no `iosX64()`
  target declared), so building against the generic `'generic/platform=iOS Simulator'` destination
  fails to link (pulls in x86_64 too). Always build/test against a **specific device UDID**
  (`-destination "id=<udid>"`), not the generic destination. `build.sh`/`test.sh` already do this
  (auto-detect booted simulator, or pass a UDID as `$1`).
- iPhone 17 simulator UDID used throughout this session: `4FAB1629-7114-46E1-A024-369805856A6D`.
- `ios-sample/Sources/App.swift` — SwiftUI app, accessibility identifiers (`initButton`,
  `showModalButton`, `showInlineButton`, `statusText`) for XCUITest targeting.
- `ios-sample/UITests/EncatchSampleUITests.swift` — XCUITest, screenshots via `XCTAttachment`
  (`add(attachment)`), inspect via the resulting `.xcresult` bundle or Xcode's Test Navigator.

### Three real bugs found + fixed during Variant 3 (do not reintroduce)

1. **Main-thread violation** (`swift/Sources/Encatch/UI/EncatchFormHost.swift`,
   `EncatchInlineFormView.swift`): `EncatchInternalEmitter`'s listeners fire on whatever thread the
   Kotlin coroutine that called `emit()` happens to be on (`:core`'s internal scope uses
   `Dispatchers.Default`) — **never** assume main thread before touching UIKit/WebKit in a listener
   closure. This only "worked" on Android by accident, because every call site in this repo
   happened to originate from a `Dispatchers.Main` coroutine (`lifecycleScope.launch {}`). Fixed by
   dispatching to main (`Thread.isMainThread` check + `DispatchQueue.main.sync`) before the
   `switch` in both listeners.

2. **Kotlin/Native object double-boundary-crossing** (`core/.../FormAppearance.kt`,
   `FormThemeColor.kt`): a `JsonObject` Kotlin/Native object returned once across the Swift
   boundary, then passed back *in* as a parameter to another Kotlin function, segfaulted
   (`EXC_BAD_ACCESS` in `Map#get`) — but only with real network-decoded data, not
   directly-constructed test data, which is what made it easy to miss. Fixed: all the appearance/
   theme-color functions now take the raw `JsonElement` and downcast to `JsonObject` internally,
   every time, rather than expecting a pre-downcast value passed in. The `asJsonObjectOrNull`
   Swift-side helper that enabled the anti-pattern was deleted.

3. **Mock page never reported height** (see "Mock server" above) — not an SDK bug, but a real gap
   in the test harness that made the *first two fixes* impossible to visually confirm.

**General lesson reinforced**: `xcodebuild`/`swiftc` invocations were being grep-filtered for
`"error:"` only in earlier debugging — this hid compiler *warnings* that would have caught bug #2
early (`"cast ... always fails"`). When debugging Swift/Kotlin-Native interop, check full output,
not just error-filtered output.

### Debugging tools used effectively this session
- `mcp__Claude_Code_iOS_Simulator__control` (attach/tap/screenshot) — tap coordinates must be in
  **points** (from `attach`'s reported coordinate space, e.g. 402×874), *not* raw screenshot pixel
  coordinates (screenshots render ~2.28x larger). Scale screenshot-pixel taps down by that factor.
- `xcrun simctl spawn <udid> log show --predicate 'processImagePath contains "EncatchSample"' --last 1m`
  — real device logs, essential for diagnosing silent failures (e.g. Kotlin API request/response
  logging via `Encatch`'s own debug logger shows up here).
- Crash reports: `~/Library/Logs/DiagnosticReports/EncatchSample-*.ips` (JSON after the first
  line) — `python3 -c "import json; ..."` to parse exception type + top frames of the crashing
  thread. Always check the **file's actual mtime** (`ls -la --time-style=full-iso`), not just
  `ls -t` — a stale cached crash report can look like a fresh one if a `find -newermt` filter
  silently fails.

## Immediate next steps (in order)

1. **Push the 5 commits above to `origin/main`** if the user wants that (wasn't asked yet this
   phase — last explicit push was before this phase started).
2. **Variant 4** (Compose Multiplatform interop, Android+iOS): new `:compose-sample` module —
   `kotlin("multiplatform")` + `org.jetbrains.compose` plugin, `androidTarget()` +
   `iosArm64()`/`iosSimulatorArm64()`, commonMain depends on `:core`. `AndroidView` wraps
   `EncatchFormDialog`/`EncatchInlineFormView` (Android actual); `UIKitViewController` wraps
   `EncatchFormViewController`/`EncatchInlineFormView` from `swift/` (iOS actual). Given the 3 bugs
   just found, budget time for similar surprises — verify with real device runs, not just
   compilation, same as variant 3.
3. **Variant 5** (KMP host sample app): new `kmp-sample/` — minimal KMP application project,
   shared `commonMain` calling `:core`'s `Encatch` API directly, thin `androidMain`/`iosMain` entry
   points.
4. **Orchestrator** (`scripts/test-all-variants.sh`): start mock-server, boot simulator/emulator,
   build+install+drive+screenshot all 5 variants (using the manual install/instrument/pull dance
   for Android, `xcodebuild test` for iOS), summarize pass/fail, tear down.
5. Re-run full regression (`./gradlew build`, `swift test` via `xcodebuild test -scheme Encatch`)
   before each commit, as has been done throughout.

## Housekeeping reminders
- **Never add a `Co-Authored-By: Claude` trailer to commits** (standing user instruction).
- Emulator (`Medium_Phone_API_35`) and iOS Simulator may still be running in the background from
  this session — reuse them rather than rebooting if still up (`adb devices`,
  `xcrun simctl list devices booted`).
- `mock-server` may or may not still be running in the background — check
  `curl -s -o /dev/null -w "%{http_code}" http://localhost:8089/s/react-native-sdk-form` before
  assuming.
