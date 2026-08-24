# com.encatch:core

Platform-agnostic business logic for the Encatch Android SDK — networking, storage, session/ping
management, and the WebView bridge message protocol. Published as a Kotlin Multiplatform module
(Android target only, for now) so a future Compose UI module or iOS/KMP SDK can share this layer.

Most apps should depend on [`com.encatch:android`](../android/README.md) instead, which pulls this
in automatically and adds the classic-Views UI (modal form overlay, WebView bridge wiring).

## Installation

```kotlin
dependencies {
    implementation("com.encatch:core:0.1.1")
}
```

## Default hosts

| Option | Default |
|--------|---------|
| `apiBaseUrl` | `https://api.encatch.com` |
| `webHost` | `https://form.encatch.com` |

These are exported as `DEFAULT_API_BASE_URL` and `DEFAULT_WEB_HOST`.

## License

MIT
