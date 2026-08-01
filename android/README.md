# com.encatch:android

The Encatch Android SDK lets you collect user feedback in your Android apps. Display feedback
forms via a WebView overlay, identify users, track screens and events, and submit responses to
the Encatch backend — functionally equivalent to `@encatch/react-native-sdk`.

> **Note:** Install the latest stable release. Pre-release builds are published under the
> `-beta` version suffix.

## Installation

```kotlin
dependencies {
    implementation("com.encatch:android:0.1.0-beta")
}
```

## Setup

Install the form UI once, typically in your `Application.onCreate`:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EncatchFormHost.install(this)
    }
}
```

## Usage

```kotlin
lifecycleScope.launch {
    Encatch.init("YOUR_API_KEY")
    Encatch.identifyUser("user-123")
    Encatch.showForm("your-form-id")
}

val unsubscribe = Encatch.on { eventType, payload ->
    // handle form:show / form:complete / form:close / etc.
}
```

## Default hosts

| Option | Default |
|--------|---------|
| `apiBaseUrl` | `https://api.encatch.com` |
| `webHost` | `https://form.encatch.com` |

These are exported as `DEFAULT_API_BASE_URL` and `DEFAULT_WEB_HOST` from `com.encatch:core`.

## License

MIT
