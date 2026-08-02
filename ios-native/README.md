# Encatch (iOS, Swift Package)

The Encatch iOS SDK lets you collect user feedback in your iOS apps. Display feedback forms via a
native WebView overlay or inline view, identify users, track screens and events, and submit
responses to the Encatch backend — functionally equivalent to `@encatch/react-native-sdk` and to
[`com.encatch:android`](../android/README.md).

This is a genuinely native Swift implementation — its own networking (`URLSession`), storage
(`UserDefaults`), session/retry-queue, appearance/theme resolution, and WebView JS-bridge protocol.
No Kotlin/Native dependency, no `.xcframework` binary to link.

## Installation

This package currently lives inside the `encatch-android` monorepo rather than its own dedicated
repo, so it isn't yet resolvable via a remote Swift Package URL. Add it as a **local** Swift
Package dependency, pointing at this directory — the same way this repo's own sample apps
(`ios-sample/`, `ios-compose-sample/`, `ios-kmp-sample/`) consume it:

**Xcode:** File → Add Package Dependencies… → Add Local… → select this `ios-native/` directory.

**Package.swift:**

```swift
.package(path: "../path/to/ios-native"),
```

then add `"Encatch"` as a dependency of your target.

> A standalone, remotely-resolvable release of this package (its own repo, tagged releases) is
> planned but not done yet — track this if you need it before then.

## Setup

Install the modal form host once, typically in your `App`'s initializer or
`AppDelegate.application(_:didFinishLaunchingWithOptions:)`:

```swift
import Encatch

@main
struct MyApp: App {
    init() {
        EncatchFormHost.install()
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
```

## Usage

```swift
import Encatch

Task {
    try await Encatch.shared.initialize(apiKey: "YOUR_API_KEY")
    try await Encatch.shared.identifyUser(userName: "user-123")
    try await Encatch.shared.showForm("your-form-id")
}

let unsubscribe = Encatch.shared.onEvent { eventType, payload in
    // handle .formShow / .formComplete / .formClose / etc.
}
```

### Inline forms

```swift
import SwiftUI
import Encatch

struct MyFormSlot: UIViewRepresentable {
    func makeUIView(context: Context) -> EncatchInlineFormView {
        let view = EncatchInlineFormView()
        view.formId = "your-form-id"
        return view
    }

    func updateUIView(_ uiView: EncatchInlineFormView, context: Context) {}
}
```

## Default hosts

| Option | Default |
|--------|---------|
| `apiBaseUrl` | `https://api.encatch.com` |
| `webHost` | `https://form.encatch.com` |

These are exported as `DEFAULT_API_BASE_URL`/`DEFAULT_WEB_HOST` and set via `EncatchConfig` passed
to `initialize(apiKey:config:)`.

## Local development

```bash
swift build
swift test
```

Consumers who cinterop against this package from Kotlin/Native (see [`:kmp-sdk`](../kmp-sdk/README.md)/
[`:compose-sdk`](../compose-sdk/README.md)) rely on a compiled artifact at `ios-native/dist/` —
run `./build-dist.sh` to (re)produce it after changing anything under `Sources/`. Those two Gradle
modules already wire this in automatically; a plain Swift-only consumer of this package does not
need `dist/` or `build-dist.sh` at all.

## License

MIT
