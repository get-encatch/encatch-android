# Encatch — iOS & macOS SDK (Swift Package)

The Encatch SDK lets you collect user feedback in your iOS and Mac Catalyst apps. Display
feedback forms via a native WebView overlay or inline view, identify users, track screens and
events, and submit responses to the Encatch backend.

This is a genuinely native Swift implementation — its own networking (`URLSession`), storage
(`UserDefaults`), session/retry-queue, appearance/theme resolution, and WebView JS-bridge
protocol. No embedded runtimes, no binary dependencies to link.

- **Platforms:** iOS 15+, macOS 12+ (form UI requires UIKit — on the Mac via Mac Catalyst;
  the core tracking/identify APIs compile for plain macOS too)
- **License:** MIT

## Installation

**Xcode:** File → Add Package Dependencies… → enter
`https://github.com/get-encatch/encatch-swift` → Dependency rule: *Up to Next Minor Version*.

**Package.swift:**

```swift
.package(url: "https://github.com/get-encatch/encatch-swift", from: "0.1.1"),
```

then add `"Encatch"` as a dependency of your target.

> Pre-1.0 note: while versions are `0.x`, minor bumps may contain breaking changes; SPM's
> `from: "0.1.1"` rule will only auto-update patch releases, which is the safe default.

## Setup

With SwiftUI, wrap your content in `EncatchProvider` (see [SwiftUI](#swiftui) below): it
initializes the SDK and installs the modal form host for you.

Otherwise, install the modal form host once, typically in your `App`'s initializer or
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

`initialize(apiKey:config:)` accepts an optional `EncatchConfig` for `apiBaseUrl`, `webHost`,
`theme`, `isFullScreen`, `debugMode`, `appVersion`, `persistUserAcrossRestarts`, and an
`onBeforeShowForm` interceptor that lets you veto (and custom-render) any form before the SDK
shows it.

### Sign-in state

`Encatch.shared.isIdentified()` says whether the SDK has a confirmed identity: `true` once
`identifyUser` succeeded, or at launch when the user saved by an earlier run is restored. While you
switch to another user it keeps describing the previous one until the new identify succeeds;
`resetUser()` and `clearAll()` make it `false`.

By default the identified user is saved and restored at the next launch. To keep the user for the
current app run only (every launch starts as a visitor until you identify again), pass
`EncatchConfig(persistUserAcrossRestarts: false)` to `initialize`.

### SwiftUI

The same building blocks as the React Native and Flutter SDKs, with the same names:

```swift
import SwiftUI
import Encatch

@main
struct MyApp: App {
    var body: some Scene {
        WindowGroup {
            // initializes the SDK once and installs the modal form host
            EncatchProvider(apiKey: "YOUR_API_KEY") {
                ContentView()
            }
        }
    }
}

struct ContentView: View {
    // React Native's useEncatch(): the SDK's state, updated as it changes, and its calls
    @EnvironmentObject var encatch: EncatchContext

    var body: some View {
        VStack {
            Text(encatch.isIdentified ? "Signed in as \(encatch.userName ?? "")" : "Visitor")
            Button("Sign in") { encatch.identifyUser(userName: "user-123") }
            Button("Feedback") { encatch.showForm("your-form-id") }
            EncatchInlineForm(formId: "your-inline-form-id")   // sizes itself to the survey
        }
        .encatchTrackScreen("Home")   // trackScreen each time the screen appears
    }
}
```

- `EncatchContext` publishes `isInitialized`, `isIdentified` and `userName`, and has React Native's
  context methods: `identifyUser`, `setLocale`, `setCountry`, `setTheme`, `trackEvent`,
  `trackScreen`, `showForm`, `dismissForm`, `addToResponse`, `resetUser`, `on` (returns a closure
  that unsubscribes), `submitForm`, `emitEvent` and `refineText`. They are call-and-forget and
  reach the SDK in the order they were made (so `resetUser()` then `identifyUser(…)` signs the new
  user in); `showForm` and `submitForm`, which wait for a server reply, don't hold up the other
  calls. To use a call's result, `await` the `Encatch.shared` method instead.
- Unlike React Native's and Flutter's providers, it starts no session at launch: as everywhere in
  the native SDKs, the session starts when an identify succeeds or the app calls `startSession()`.
- Screens aren't tracked automatically: add `.encatchTrackScreen("Name")` to each screen.

### Inline forms

An inline slot claims a form id, so `showForm` for that id renders inline in your layout instead
of as a modal. Leave `formId` as `nil` to make it a wildcard slot that catches any form id not
claimed elsewhere.

- **SwiftUI:** `EncatchInlineForm(formId: "your-form-id", minHeight: 0, onOverlayOpenChange: nil)`
  sizes itself to the survey (0 when none is shown); don't give it a fixed height.
- **UIKit:** add `EncatchInlineFormView` (a `UIView`) and set its `formId`; it sizes itself with its
  own height constraint, and `onHeightChange` reports each change for manual layout.

## Default hosts

| Option | Default |
|--------|---------|
| `apiBaseUrl` | `https://api.encatch.com` |
| `webHost` | `https://form.encatch.com` |

These are exported as `DEFAULT_API_BASE_URL`/`DEFAULT_WEB_HOST` and set via `EncatchConfig`
passed to `initialize(apiKey:config:)`.

## Contributing & issues

Development happens in the cross-platform monorepo at
[get-encatch/encatch-android](https://github.com/get-encatch/encatch-android) (under
`swift/`); this repo is the Swift Package Manager distribution mirror, updated per release.
Please open pull requests against the monorepo — issues are welcome in either repo.

## License

[MIT](LICENSE)
