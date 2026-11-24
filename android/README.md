# com.encatch:android

The Encatch Android SDK lets you collect user feedback in your Android apps. Display feedback
forms via a WebView overlay, identify users, track screens and events, and submit responses to
the Encatch backend — functionally equivalent to `@encatch/react-native-sdk`.

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
dependencies {
    implementation("com.encatch:android:0.1.1")
}
```

## Setup

Nothing to install: the form UI (the modal survey host) installs itself at app start through
AndroidX App Startup, before `Application.onCreate`.

`EncatchFormHost.install(this)` in `Application.onCreate` still works and is a no-op once the host
is installed — keep it if you have it. You need it only if your app removed App Startup's
`InitializationProvider` from its manifest.

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

The view grows to the form's full content height and delegates all scrolling to your layout, so
the host must also handle keyboard (IME) insets — otherwise the keyboard covers the form's text
inputs with no way to scroll them into view. In Compose, add `Modifier.imePadding()` to the
scroll container; in a classic View layout use `windowSoftInputMode="adjustResize"`, and on
Android 15+ (enforced edge-to-edge) additionally apply `WindowInsetsCompat.Type.ime()` insets
yourself. The modal overlay handles all of this internally; only inline delegates it to the host.

## Default hosts

| Option | Default |
|--------|---------|
| `apiBaseUrl` | `https://api.encatch.com` |
| `webHost` | `https://form.encatch.com` |

These are exported as `DEFAULT_API_BASE_URL` and `DEFAULT_WEB_HOST` from `com.encatch:core`.

## License

MIT
