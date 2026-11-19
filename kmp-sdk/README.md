# com.encatch:kmp-sdk

The real Encatch SDK for Kotlin Multiplatform apps — a full-parity `Encatch` object callable from
`commonMain`, with zero platform-bridging code of your own. Android forwards to
[`com.encatch:core`](../core/README.md) directly (Android's native language *is* Kotlin, so
there's no bridge to write); iOS forwards through Kotlin/Native cinterop into
[`Encatch` (the native Swift SDK)](../swift/README.md).

This module has **no UI API** — its `commonMain` surface is business logic (init, identify, track,
show/dismiss forms, submit, session control, events); the modal survey itself comes from the
platform SDK it depends on. If you're building with Compose Multiplatform and want a
ready-made inline-form composable too, use [`com.encatch:compose-sdk`](../compose-sdk/README.md)
instead, which depends on this module and adds that.

## Installation

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.encatch:kmp-sdk:0.1.1")
        }
    }
}
```

## Setup

Nothing to do on either platform:

- **Android:** the modal form host installs itself at app start (AndroidX App Startup, before
  `Application.onCreate`). It comes with this module — `com.encatch:android` is a dependency of
  its Android variant, so you don't add it yourself. Apps that already call
  `com.encatch.android.EncatchFormHost.install(this)` can keep the call; it is a no-op when the
  host is installed. If your app removed App Startup's `InitializationProvider`, keep (or add)
  that call in `Application.onCreate`.

  This means the Android variant brings the UI module with it: `com.encatch:android` and its
  dependencies (AndroidX AppCompat, AndroidX Browser, the WebView survey dialog). If your app also
  declares `com.encatch:android` itself, keep it at the same version as `com.encatch:kmp-sdk` —
  don't pin a different one (Gradle would pick the higher, mixing SDK versions).
- **iOS:** `Encatch.init(...)` installs the modal form host automatically the first time it's
  called.

## Usage

```kotlin
import com.encatch.sdk.Encatch
import com.encatch.sdk.EncatchConfig

// commonMain — same call site on both platforms
scope.launch {
    Encatch.init("YOUR_API_KEY")
    Encatch.identifyUser("user-123")
    Encatch.showForm("your-form-id")
}

val unsubscribe = Encatch.on { eventType, payload ->
    // handle FORM_SHOW / FORM_COMPLETE / FORM_CLOSE / etc.
}
```

For an inline (non-modal) form view outside Compose, embed the platform-native view directly —
Android's `com.encatch.android.EncatchInlineFormView`, iOS's `EncatchInlineFormView` from
[`swift`](../swift/README.md) — this module doesn't yet expose a non-Compose accessor
for that view type itself (a known gap; see `kmp-sdk/build.gradle.kts`'s comment).

## Default hosts

| Option | Default |
|--------|---------|
| `apiBaseUrl` | `https://api.encatch.com` |
| `webHost` | `https://form.encatch.com` |

Override via `EncatchConfig(apiBaseUrl = ..., webHost = ...)` passed to `Encatch.init(...)`.

## License

MIT
