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

Encatch's libraries come from Encatch's own Maven repository. Add it once, in your
`settings.gradle.kts` (or wherever your project declares its repositories, such as the root
`build.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "Encatch"
                    url = uri("https://packages.encatch.com/maven")
                }
            }
            filter {
                includeGroupAndSubgroups("com.encatch")
            }
        }
    }
}
```

`exclusiveContent` takes `com.encatch` libraries only from that repository and uses it for nothing
else (`includeGroupAndSubgroups` needs Gradle 8.1 or later; older: `includeGroupByRegex("com\\.encatch(\\..+)?")`).
Every version is listed, with its setup lines and checksums, at https://packages.encatch.com/.
Versions 0.1.0 and 0.1.1 are also on Maven Central.

Then add the dependency:

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

### Sign-in state

`Encatch.isIdentified()` says whether the SDK has a confirmed identity: `true` once
`identifyUser` succeeded, or at launch when the user saved by an earlier run is restored. While you
switch to another user it keeps describing the previous one until the new identify succeeds;
`resetUser()` and `clearAll()` make it `false`.

By default the identified user is saved and restored at the next launch. To keep the user for the
current app run only (every launch starts as a visitor until you identify again), pass
`EncatchConfig(persistUserAcrossRestarts = false)` to `Encatch.init`.

For an inline (non-modal) form view outside Compose, embed the platform-native view directly —
Android's `com.encatch.android.EncatchInlineFormView`, iOS's `EncatchInlineFormView` from
[`swift`](../swift/README.md) — this module doesn't yet expose a non-Compose accessor
for that view type itself (a known gap; see `kmp-sdk/build.gradle.kts`'s comment).

### Repeat identifies and user properties

After the first `identifyUser` of each app run, calling it again for the same user with the same
traits, or with none, sends nothing, so it is safe to call on every launch or screen. A different
user, a new signature, a locale or country the last identify didn't carry, and changed traits are
sent.

To update the user's properties on purpose (a counter, say), use `setUserProperties`. It takes the
same traits, is never skipped, and is ignored unless that user is the current one and their identify
succeeded. It waits for an identify of that user still in flight and for an open survey.

```kotlin
Encatch.setUserProperties(
    "user-123",
    UserTraits(set = mapOf("plan" to JsonPrimitive("team")), increment = mapOf("logins" to 1.0)),
)
```

It counts against the identify rate limit (about 5 per minute per user), and an update with
`$increment` or `$decrement` is never retried.

## Default hosts

| Option | Default |
|--------|---------|
| `apiBaseUrl` | `https://api.encatch.com` |
| `webHost` | `https://form.encatch.com` |

Override via `EncatchConfig(apiBaseUrl = ..., webHost = ...)` passed to `Encatch.init(...)`.

## License

MIT
