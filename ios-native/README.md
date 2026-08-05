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
.package(url: "https://github.com/get-encatch/encatch-swift", from: "0.1.3"),
```

then add `"Encatch"` as a dependency of your target.

> Pre-1.0 note: while versions are `0.x`, minor bumps may contain breaking changes; SPM's
> `from: "0.1.3"` rule will only auto-update patch releases, which is the safe default.

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

`initialize(apiKey:config:)` accepts an optional `EncatchConfig` for `apiBaseUrl`, `webHost`,
`theme`, `isFullScreen`, `debugMode`, `appVersion`, and an `onBeforeShowForm` interceptor that
lets you veto (and custom-render) any form before the SDK shows it.

### Inline forms

`EncatchInlineFormView` is a `UIView` that claims a form id, so `showForm` for that id renders
inline in your layout instead of as a modal. Leave `formId` as `nil` to make it a wildcard slot
that catches any form id not claimed elsewhere.

```swift
import SwiftUI
import Encatch

struct MyFormSlot: UIViewRepresentable {
    @Binding var height: CGFloat

    func makeUIView(context: Context) -> EncatchInlineFormView {
        let view = EncatchInlineFormView()
        view.formId = "your-form-id"   // or nil for a wildcard slot
        view.onHeightChange = { [binding = $height] newHeight in
            DispatchQueue.main.async { binding.wrappedValue = newHeight }
        }
        return view
    }

    func updateUIView(_ uiView: EncatchInlineFormView, context: Context) {}
}

// place it:  MyFormSlot(height: $height).frame(height: max(height, 64))
```

UIKit hosts can add `EncatchInlineFormView` directly and use `onHeightChange` to drive layout.

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
`ios-native/`); this repo is the Swift Package Manager distribution mirror, updated per release.
Please open pull requests against the monorepo — issues are welcome in either repo.

## License

[MIT](LICENSE)
