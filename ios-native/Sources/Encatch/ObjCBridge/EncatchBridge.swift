import Foundation
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Why this file exists
//
// Kotlin/Native's cinterop tool only understands compiled Objective-C headers (via clang) — it
// cannot consume Swift modules directly, and it definitely can't consume `async`/`throws` Swift
// APIs. `compose-sample` and `kmp-sample` compile Kotlin/Native binaries for their iOS targets and
// need to drive this pure-Swift `Encatch` SDK (see `Core/Encatch.swift`) from Kotlin code.
//
// This file is a narrow, purpose-built `@objc`-compatible facade over exactly the calls those two
// samples make today (see `compose-sample/src/commonMain/kotlin/com/encatch/composesample/
// ComposeSampleScreen.kt`, `compose-sample/src/iosMain/.../ComposeSampleViewController.kt`,
// `compose-sample/src/iosMain/.../EncatchInlineFormHost.ios.kt`, `kmp-sample/src/commonMain/.../
// SampleAppController.kt`, `kmp-sample/src/iosMain/.../KmpSampleViewController.kt`). It is NOT a
// full mirror of the `Encatch` API — only `initialize`, `isInitialized`, `deviceId`, `showForm`,
// and installing the modal form host are exposed, because that's all the samples call.
//
// Background / rationale: `/Users/godwin/.claude/plans/stateless-floating-ripple.md`.
//
// Design constraints for `@objc` compatibility (see that plan doc):
//  - Classes must inherit `NSObject`; no Swift-only enums with associated values, no `struct`s, no
//    generics in the exposed surface.
//  - Structured params get small `@objc` classes (`EncatchBridgeConfig`) instead of the Swift
//    `EncatchConfig` struct.
//  - `async`/`throws` methods become completion-handler-based, wrapping a `Task { do { try await
//    ... } catch { ... } }` internally.

/// `@objc`-compatible mirror of `EncatchConfig`, for Kotlin/Native cinterop callers that can't
/// construct the Swift struct directly. Unset fields fall back to `Encatch.initialize`'s own
/// defaults (see `EncatchConfig.init`).
@objc(EncatchBridgeConfig)
public final class EncatchBridgeConfig: NSObject {
    @objc public var apiBaseUrl: String?
    @objc public var webHost: String?
    @objc public var debugMode: Bool = false
    @objc public var isFullScreen: Bool = false
    @objc public var appVersion: String?
    /// One of "light" / "dark" / "system" (case-insensitive). Any other value (including nil)
    /// resolves to `.system`.
    @objc public var theme: String?

    @objc public override init() {
        super.init()
    }

    fileprivate func toEncatchConfig() -> EncatchConfig {
        let resolvedTheme: Theme
        switch theme?.lowercased() {
        case "light": resolvedTheme = .light
        case "dark": resolvedTheme = .dark
        default: resolvedTheme = .system
        }
        return EncatchConfig(
            apiBaseUrl: apiBaseUrl ?? DEFAULT_API_BASE_URL,
            webHost: webHost ?? DEFAULT_WEB_HOST,
            theme: resolvedTheme,
            isFullScreen: isFullScreen,
            debugMode: debugMode,
            appVersion: appVersion
        )
    }
}

/// The Kotlin/Native-facing entry point onto the pure-Swift `Encatch` singleton. See file-level
/// doc comment above for why this exists and what it deliberately does NOT expose.
@objc(EncatchBridge)
public final class EncatchBridge: NSObject {
    @objc public static let shared = EncatchBridge()

    private override init() {
        super.init()
    }

    /// Mirrors `Encatch.shared.initialize(apiKey:config:)`. Completion is invoked exactly once,
    /// on an arbitrary thread (matches Swift concurrency's `Task` — callers needing main-thread
    /// delivery should hop themselves, same as any other async bridge).
    @objc public func initialize(
        apiKey: String,
        config: EncatchBridgeConfig?,
        completion: @escaping (NSError?) -> Void
    ) {
        Task {
            do {
                try await Encatch.shared.initialize(apiKey: apiKey, config: config?.toEncatchConfig())
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    @objc public var isInitialized: Bool { Encatch.shared.isInitialized }

    @objc public var deviceId: String? { Encatch.shared.deviceId }

    /// Mirrors `Encatch.shared.showForm(_:)` with default options (the only call shape the samples
    /// use today).
    @objc public func showForm(_ formId: String, completion: @escaping (NSError?) -> Void) {
        Task {
            do {
                try await Encatch.shared.showForm(formId)
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    #if canImport(UIKit)
    /// Installs the app-wide modal form host (`EncatchFormHost.install()`). `EncatchFormHost` is a
    /// caseless `enum` namespace, which Swift can't expose to Objective-C directly (only
    /// `NSObject`-rooted classes/members are `@objc`-representable) — this static method is the
    /// cinterop-visible entry point instead.
    @objc public static func installFormHost() {
        EncatchFormHost.install()
    }
    #endif
}
