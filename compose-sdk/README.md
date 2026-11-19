# com.encatch:compose-sdk

The Encatch SDK for Compose Multiplatform apps (Android + iOS from one `commonMain` UI). Depends
on [`com.encatch:kmp-sdk`](../kmp-sdk/README.md) for the `Encatch` business-logic API, and adds the
one thing a pure KMP consumer wouldn't need: `EncatchInlineForm`, a composable wrapping the
platform-native inline form view (`AndroidView`/`UIKitView` interop — no WebView reimplementation,
no third-party dependency).

Setup is zero-plumbing on both platforms — the modal form host installs itself: on iOS the first
time you call `Encatch.init(...)`, on Android at app start (through AndroidX App Startup, before
`Application.onCreate`). Modal surveys work whether or not an `EncatchInlineForm` is on screen.
You never call an install function yourself.

## Installation

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.encatch:compose-sdk:0.1.1")
        }
    }
}
```

This transitively brings in `com.encatch:kmp-sdk`'s `Encatch` API and, on Android,
`com.encatch:android` (the modal survey host and the native views) — no separate dependency needed.

If your app removed AndroidX App Startup's `InitializationProvider` from its manifest, the modal host
does not start by itself: call `com.encatch.android.EncatchFormHost.install(this)` in
`Application.onCreate`.

## Usage

```kotlin
import com.encatch.sdk.Encatch
import com.encatch.sdk.compose.EncatchInlineForm

@Composable
fun MyScreen() {
    val scope = rememberCoroutineScope()

    Column {
        Button(onClick = {
            scope.launch {
                Encatch.init("YOUR_API_KEY")
                Encatch.showForm("your-modal-form-id")
            }
        }) { Text("Show modal form") }

        EncatchInlineForm(
            formId = "your-inline-form-id",
            modifier = Modifier.fillMaxWidth().height(320.dp),
        )
    }
}
```

That's the entire integration — no `Application.onCreate` setup, no per-platform `expect`/`actual`
of your own, no `installFormHost()`/`EncatchFormHost.install()` call.

## Default hosts

| Option | Default |
|--------|---------|
| `apiBaseUrl` | `https://api.encatch.com` |
| `webHost` | `https://form.encatch.com` |

Override via `EncatchConfig(apiBaseUrl = ..., webHost = ...)` passed to `Encatch.init(...)` (see
[`:kmp-sdk`](../kmp-sdk/README.md) for the full `Encatch` API this module builds on).

## License

MIT
