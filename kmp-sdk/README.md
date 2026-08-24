# com.encatch:kmp-sdk

The real Encatch SDK for Kotlin Multiplatform apps — a full-parity `Encatch` object callable from
`commonMain`, with zero platform-bridging code of your own. Android forwards to
[`com.encatch:core`](../core/README.md) directly (Android's native language *is* Kotlin, so
there's no bridge to write); iOS forwards through Kotlin/Native cinterop into
[`Encatch` (the native Swift SDK)](../swift/README.md).

This module has **no UI** — it's pure business logic (init, identify, track, show/dismiss forms,
submit, session control, events). If you're building with Compose Multiplatform and want a
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

**Android:** install the modal form host once, typically in your `Application.onCreate` — this
module doesn't do this for you automatically, since it has no `Context`/`Application` reference
available from `commonMain` (unlike `com.encatch:compose-sdk`, which can do this lazily via
Compose's `LocalContext`):

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.encatch.android.EncatchFormHost.install(this)
    }
}
```

**iOS:** nothing to do — `Encatch.init(...)` installs the modal form host automatically the first
time it's called.

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
