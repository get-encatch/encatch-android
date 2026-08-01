# Encatch (Swift Package)

Native Swift UI + idiomatic Swift façade for the Encatch SDK, built on top of `:core`'s
Kotlin/Native output (`EncatchCore.xcframework`). Companion to the `:android` module — same
`:core` business logic (networking, sessions, retry queue, form encoding, WebView bridge
protocol), a native UI per platform.

## Local development

The `EncatchCore` binary target points at `../core/build/XCFrameworks/debug/EncatchCore.xcframework`,
which is a Gradle build output and isn't committed to git. Build it before resolving/building this
package:

```bash
cd ..
./gradlew :core:assembleEncatchCoreDebugXCFramework
```

For a release-optimized framework (smaller, stripped — see `core/build.gradle.kts`'s XCFramework
size measurements), use `:core:assembleEncatchCoreReleaseXCFramework` and point the binary target
at `core/build/XCFrameworks/release/EncatchCore.xcframework` instead.

Then, from this directory:

```bash
xcodebuild -scheme Encatch -destination 'generic/platform=iOS Simulator' build
xcodebuild test -scheme Encatch -destination 'id=<simulator-udid>'
```

(Plain `swift build`/`swift test` don't work for this package as-is since it's iOS-only with no
macOS slice in the xcframework — use `xcodebuild` with an iOS Simulator destination instead, or
open `Package.swift` in Xcode directly.)

## Layout

- `Sources/Encatch` — idiomatic Swift façade (real `enum`s/typed errors instead of the generated
  Kotlin/Native class hierarchy) + native WKWebView-based form UI, mirroring `:android`'s
  `EncatchFormDialog`/`EncatchInlineFormView`.
- `Tests/EncatchTests` — XCTest suite, run via `xcodebuild test` as above.
