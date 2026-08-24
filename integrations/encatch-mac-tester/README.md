# encatch-mac-tester

Native macOS app for testing the `swift` Swift Package under **Mac Catalyst** — a sibling to
[`encatch-ios-tester`](../encatch-ios-tester/README.md) with full feature parity, but built to
look and behave like an actual Mac app rather than an iPhone screen stretched into a window: a
sidebar instead of a bottom tab bar, system-native controls instead of the iOS app's
pill/capsule/flat-card theme, and a menu bar.

Links `swift` as a local Swift Package (`.package(path: "../../swift")` in
`project.yml`) — same pattern as every other tester/sample in this repo. **Zero changes to
`swift` itself** were needed to run under Catalyst — see
[`swift/README.md`](../../swift/README.md) for the feasibility findings.

## Build and run

Requires [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```bash
cd integrations/encatch-mac-tester
./build.sh
```

This generates `EncatchMacTester.xcodeproj` via `xcodegen` and builds for Mac Catalyst. Open the
generated `.xcodeproj` in Xcode to run/debug interactively (select "My Mac" as the run
destination), or install manually:

```bash
open build/Build/Products/Debug-maccatalyst/EncatchMacTester.app
```

## macOS-native decisions

This app deliberately does **not** port `encatch-ios-tester`'s `Theme.swift` or its bottom-tab
navigation model verbatim — running the iOS app's UI unmodified under Catalyst would look like a
stretched phone screen, not a Mac app. The choices below explain the divergence, and a few real
Catalyst limitations discovered along the way (verified by build, not assumed):

- **Sidebar, not tabs** — `NavigationSplitView` (Mail/Xcode-style) replaces the bottom
  `TesterTab` bar. **Interceptor** gets its own sidebar row with a badge count instead of staying
  a tab-docked floating carousel, since the sidebar already gives blocked forms a persistent,
  always-visible place to surface (the carousel only existed because a phone has no other
  chrome for that).
- **No `Settings{}` Scene, and Settings lives in the sidebar, not a separate window** — SwiftUI's
  `Settings{}` and `Window(_:id:)` Scene types are **hard-unavailable when compiling for
  iOS/Catalyst** (a real compiler error, not a version gate). This app briefly used the working
  substitute (a second `WindowGroup(id:)` opened via `openWindow(id:)`, wired to Cmd+, — which
  only works if the app's Info.plist declares
  `UIApplicationSceneManifest.UIApplicationSupportsMultipleScenes = true`; Xcode normally injects
  that automatically for SwiftUI-lifecycle apps, but a custom `info.path`, as used here and in
  `encatch-ios-tester`, bypasses it — see `project.yml`'s comment on the key), but settled on
  folding `SettingsView` into the sidebar as a regular `SidebarDestination.settings` instead: a
  standalone Preferences window is more ceremony than a tester app needs. Cmd+, still works — it
  just selects that sidebar row instead of opening a window.
- **Onboarding is a sheet, not a screen swap** — Setup → Login → EditProfile present as one
  fixed-size `.sheet` over an always-visible `RootShell`, instead of replacing the whole window
  the way the iPhone app's `Screen` enum does. The sidebar and toolbar stay visible-but-inert
  behind it, so the window never stops looking like "the app."
- **Interceptor is a two-pane destination**, not a carousel — a `List` of blocked forms (leading)
  plus the native form wizard embedded directly as the detail pane (trailing), not a `.sheet`. A
  tester bouncing between "trigger the interceptor on Home" and "answer it here" can see both the
  sidebar and the form at once.
- **Logs is a `Table`**, not a scrolling list — sortable Status/Endpoint/Time/Duration columns
  with a selection-driven detail inspector, Console.app-style.
- **System controls throughout** — `.borderedProminent`/`.bordered` buttons,
  `.roundedBorder` text fields, `Form(.formStyle(.grouped))` + `GroupBox`/`Section`,
  `LabeledContent`, and `Color.accentColor` (the user's own system accent color) instead of a
  hardcoded brand color. `.menuStyle(.borderedButton)` was tried for the Events destination's
  preset dropdowns and turned out to be **also unavailable under Catalyst** — the default menu
  style is used instead.
- **iOS-only mechanisms dropped, no replacement needed**: the on-screen-keyboard scroll-room
  workaround (`keyboardHeight`/`avoidsKeyboard`) is meaningless without a soft keyboard; the
  `ChipGrid` two-per-row chunking hack (itself a workaround for no `Layout` protocol on iOS 15) is
  replaced by a `Menu` dropdown, which fits a handful of presets better on a pointer-driven UI
  anyway.
- This app targets **iOS 17** (not iOS 15 like `swift`'s own floor) for `Table`,
  `.defaultSize`/`.windowResizability`, and the two-parameter `.onChange` — a consuming app can
  freely target higher than its dependency's minimum, and Mac Catalyst users are virtually
  guaranteed to be on a current macOS anyway.

## Features

Full parity with `encatch-ios-tester`, Mac-native presentation:

- **Onboarding sheet** — environment picker (Dev/UAT/Prod), API key, default form id, optional
  interceptor form id (Setup step); locally-saved test users you can add/select/edit before
  signing in (Login/EditProfile steps). **Identify User** calls
  `Encatch.shared.identifyUser(userName:traits:)`.
- **Sidebar** — Home / Events / Logs / Inline (Exact) / Inline (Any) / Interceptor / Settings,
  with a badge on Interceptor showing pending blocked-form count.
- **Persistent header** — a tap-to-cycle theme button (`Encatch.shared.setTheme`/`.theme`, which
  also flips the host app's own `.preferredColorScheme`) and Logout, rendered once above every
  destination rather than via `.toolbar` — SwiftUI's `.toolbar` merging across a switched
  `detail` view proved unreliable under Catalyst (items would silently disappear on some
  destinations), so this is a plain persistent view instead.
- **Menu bar** — a "Tester" menu (Show Form ⇧⌘F, Show Form Prefilled, Cycle Theme ⇧⌘T, Change API
  Key & Setup) and Cmd+, to jump to Settings.
- **Home** — Show Form (modal), Show Form (prefilled via `addToResponse`), Show Form
  (interceptor test, shown only if an interceptor form id is set), `home_viewed` tracking.
- **Events** — `trackEvent`/`trackScreen` presets shown upfront as a grouped, clickable list
  (with hover/press feedback) rather than tucked behind a dropdown, plus a custom-value field for
  each.
- **Interceptor** — `EncatchConfig.onBeforeShowForm` unconditionally blocks the configured
  interceptor form id and queues it in the sidebar destination's list. Selecting a row opens a
  fully custom, non-WebView 3-step form (welcome → questions → thank-you) parsed from the
  interceptor payload's `ShowFormResponse.questionnaireFields`, submitted via
  `buildSubmitRequest`/`Encatch.shared.submitForm` and driven end-to-end with manual
  `Encatch.shared.emitEvent(...)` calls.
- **Inline (Exact)** — an exact-match `EncatchInlineFormView` claiming the default form id.
- **Inline (Any)** — a wildcard `EncatchInlineFormView` plus an unmatched-form-id demo that falls
  back to the SDK's modal.
- **Logs** — every SDK HTTP request/response (`Encatch.shared.onNetworkLog`, debugMode-gated, API
  key masked) in a sortable table with a detail inspector and Copy All/Clear/Copy actions.
- **Screen tracking** — every destination calls `trackScreen(...)` on appear.
- **CTA navigation** — `form:ctaTriggered` with `action=app_navigate` routes to Billing or Route
  Not Found, replacing the split view's detail pane.

## Manual test checklist

1. **Onboarding:** Launch, pick an environment, enter an API key and default form id, Save &
   Continue. Add a test user (or select a saved one), optionally edit their profile, Identify
   User.
2. **Modal form:** Home → Show Form → opens as a modal overlay.
3. **Prefilled form:** Home → Show Form (Prefilled) → opens with a pending prefill response.
4. **Interceptor + custom form:** Set an interceptor form id in Settings, then Home → Show
   Form (Interceptor Test) → a row appears in the Interceptor destination (with a sidebar badge).
   Select it, answer the questions, submit.
5. **Exact inline:** Inline (Exact) → Show Exact Form → renders inline below the button.
6. **Wildcard inline:** Inline (Any) → enter any other form id → Show Form → renders inline
   below. "Trigger unmatched form → modal fallback" demonstrates the SDK falling back to its
   modal.
7. **Events:** fire each `trackEvent`/`trackScreen` preset row plus a custom one.
8. **Logs:** open the Logs destination, sort by each column, select a row, Copy, Copy All, Clear.
9. **app_navigate (billing):** Configure a thank-you screen with `completionCta.inApp: {
   action: "app_navigate", route: "billing" }`. Complete that form and trigger the CTA — expect
   the detail pane to switch to Billing.
10. **app_navigate (404):** Use a form with an unmapped route. Expect Route Not Found with the
    requested route, then Go Back.
11. **Theme cycling:** header theme button through system → light → dark → system — confirm both
    the SDK's own form theming and the host app's own window appearance (sidebar, controls)
    switch together.
12. **Settings:** Cmd+, (or the sidebar row) selects Settings; Set Locale… and Set Country…
    (each opens a modal to type the code, e.g. fr-FR / FR; applied values show in the Region
    section), Change API Key & Setup.
13. **Logout / change setup:** header Logout re-presents the onboarding sheet at Login (SDK
    state persists). Settings → Change API Key & Setup wipes local config and returns to Setup.
