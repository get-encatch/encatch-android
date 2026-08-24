# com.encatch:android

The Encatch Android SDK lets you collect user feedback in your Android apps. Display feedback
forms via a WebView overlay, identify users, track screens and events, and submit responses to
the Encatch backend — functionally equivalent to `@encatch/react-native-sdk`.

## Installation

```kotlin
dependencies {
    implementation("com.encatch:android:0.1.1")
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

### Inline forms

Renders the form inline within your layout instead of as a modal overlay — place it anywhere in
an Activity/Fragment's view hierarchy, XML or programmatic:

```xml
<com.encatch.android.EncatchInlineFormView
    android:id="@+id/inlineForm"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

```kotlin
findViewById<EncatchInlineFormView>(R.id.inlineForm).formId = "your-form-id"
```

or programmatically:

```kotlin
val inlineForm = EncatchInlineFormView(context).apply {
    formId = "your-form-id"
}
container.addView(inlineForm)
```

Leave `formId` unset to make the view a wildcard slot that catches any `showForm(...)` call not
claimed by another exact-match inline slot — otherwise, calls fall through to the modal overlay.

## Default hosts

| Option | Default |
|--------|---------|
| `apiBaseUrl` | `https://api.encatch.com` |
| `webHost` | `https://form.encatch.com` |

These are exported as `DEFAULT_API_BASE_URL` and `DEFAULT_WEB_HOST` from `com.encatch:core`.

## License

MIT
