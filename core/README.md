# com.encatch:core

Platform-agnostic business logic for the Encatch Android SDK — networking, storage, session/ping
management, and the WebView bridge message protocol. Published as a Kotlin Multiplatform module
(Android target only, for now) so a future Compose UI module or iOS/KMP SDK can share this layer.

Most apps should depend on [`com.encatch:android`](../android/README.md) instead, which pulls this
in automatically and adds the classic-Views UI (modal form overlay, WebView bridge wiring).

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
