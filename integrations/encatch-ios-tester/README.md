# encatch-ios-tester

Standalone iOS app for testing the `ios-native` Swift Package — modeled on
[`encatch-android-tester`](../encatch-android-tester/README.md) (and, further back,
`encatch-expo-tester` in the `encatch-typescript` monorepo). Unlike `ios-sample` (this repo's
minimal manual-verification harness), this app has a runtime **Setup** screen so one build works
for any tester or environment, no rebuild required.

Links `ios-native` as a local Swift Package (`.package(path: "../../ios-native")` in
`project.yml`) — the same stand-in used by `ios-sample`/`ios-compose-sample`/`ios-kmp-sample`,
since `ios-native` isn't published as a remotely-resolvable package yet (see
[`ios-native/README.md`](../../ios-native/README.md)).

## Build and run

Requires [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```bash
cd integrations/encatch-ios-tester
./build.sh              # boots against the first currently-booted simulator
./build.sh <DEVICE_UDID> # or target a specific simulator
```

This generates `EncatchIosTester.xcodeproj` via `xcodegen` and builds for the iOS Simulator. Open
the generated `.xcodeproj` in Xcode to run/debug interactively, or install manually:

```bash
xcrun simctl install <DEVICE_UDID> ~/Library/Developer/Xcode/DerivedData/EncatchIosTester-*/Build/Products/Debug-iphonesimulator/EncatchIosTester.app
xcrun simctl launch <DEVICE_UDID> com.encatch.iostester
```

To distribute a build to an external tester (TestFlight / ad-hoc), archive the
`EncatchIosTester` scheme for a device destination and export as usual — the project has no
mock-server-specific wiring baked in, so a normal Xcode archive/export flow applies.

## Features

Modeled on the richer `encatch-flutter-tester` reference app (see
`/Users/godwin/Desktop/cmss/projects/schema-definition/sdk/integrations/encatch-flutter-tester`
in the sibling schema-definition repo):

- **Setup** — environment picker (Dev/UAT/Prod, each a preset `apiBaseUrl`/`webHost` pair), API
  key, default form id, optional interceptor test form id. Saved locally (`UserDefaults`) and
  restored on next launch until cleared.
- **Login** — a locally-saved **test users** list (username/email/display name — independent of
  the SDK, its own `UserDefaults` JSON blob) you can add to, select from, and edit before signing
  in. **Identify user** calls `Encatch.shared.identifyUser(userName:traits:)`.
- **Edit profile** — email/display name → `identifyUser`'s `UserTraits(set: ...)`.
- **Bottom tab bar** — Home / Events / Settings / Inline (Any) / Inline (Exact).
- **Header** — a tap-to-cycle theme button (`Encatch.shared.setTheme`/`.theme`, system → light →
  dark) and **Logout** (`resetUser()`), shared across every tab.
- **Home** — Tracks `home_viewed` on load. **Show Form** calls `showForm` with the default form id.
  **Show Form (prefilled)** calls `addToResponse(...)` before `showForm`. **Show Form (interceptor
  test)** (shown only if an interceptor form id was set) calls `showForm` for that id — see
  Interceptor below.
- **Events** — `trackEvent` presets (`button_clicked`, `feature_used`, `purchase_started`,
  `survey_viewed`, `home_viewed`) + a custom-name field, and `trackScreen` presets (`/home`,
  `/dashboard`, `/settings`, `/dashboard/encatch-test`) + a custom-path field.
- **Interceptor carousel + custom native form** — `EncatchConfig.onBeforeShowForm` unconditionally
  blocks the configured interceptor form id and queues it as a floating, dismissible card in an
  `InterceptorCarousel`. Tapping a card opens `NativeFormModal`: a fully custom, non-WebView
  3-step form (welcome → questions → thank-you) parsed from the interceptor payload's
  `ShowFormResponse.questionnaireFields`, submitted via `buildSubmitRequest`/
  `Encatch.shared.submitForm` and driven end-to-end with manual
  `Encatch.shared.emitEvent(.formShow/.formStarted/.formSubmit/.formComplete/.formClose)` calls —
  demonstrates fully replacing the SDK's own rendering with native UI.
- **Inline (Exact)** — an exact-match `EncatchInlineFormView` claiming the default form id.
- **Inline (Any)** — a wildcard `EncatchInlineFormView` (`formId = nil`) you can target by typing
  any other form id, plus a button that shows a deliberately unmatched form id to demonstrate the
  modal fallback.
- **Settings** — environment/form id/interceptor id summary, **Set Locale → fr-FR**
  (`setLocale`), **Set Country → FR** (`setCountry`), and **Change API key & setup** (clears local
  config and SDK state, returns to Setup).
- **Screen tracking** — Each screen calls `trackScreen(...)` in `onAppear`.
- **CTA navigation** — A single `Encatch.shared.on(...)` listener registered at app start watches
  for `form:ctaTriggered` events with `action == "app_navigate"`: routes
  `"billing"`/`"billing/upgrade"` navigate to the in-app **Billing** screen; any other route
  navigates to **Route not found**.

## Manual test checklist

1. **Setup:** Launch the app, pick an environment, enter an API key and a default form id, tap
   **Save & continue**.
2. **Login:** Add a new test user (or select a saved one), optionally edit their profile, tap
   **Identify user**.
3. **Modal form:** Home → **Show Form** → opens as a modal overlay.
4. **Prefilled form:** Home → **Show Form (prefilled)** → opens with a pending prefill response.
5. **Interceptor + custom form:** Set an interceptor form id in Setup, then Home → **Show Form
   (interceptor test)** → the SDK form is blocked and a card appears in the floating carousel. Tap
   the card to open the custom native form, answer the questions, and submit.
6. **Exact inline:** Inline (Exact) → **Show Exact Form** → renders inline below.
7. **Wildcard inline:** Inline (Any) → enter any other form id → **Show Form** → renders inline
   below. **Trigger unmatched form → modal fallback** demonstrates the SDK falling back to its
   modal when the wildcard slot can't claim a form.
8. **Events:** fire each `trackEvent`/`trackScreen` preset plus a custom one.
9. **app_navigate (billing):** Configure a thank-you screen with
   `completionCta.inApp: { action: "app_navigate", route: "billing" }` (or `"billing/upgrade"`).
   Complete that form and trigger the CTA. Expect the overlay to close and navigation to
   **Billing**.
10. **app_navigate (404):** Use a form with an unmapped route, e.g. `"does/not/exist"`. Expect
    **Route not found** with the requested route, then **Go back**.
11. **Theme cycling:** tap the header theme button through system → light → dark → system.
12. **Locale/Country:** Settings → **Set Locale → fr-FR** / **Set Country → FR**.
13. **Logout / change setup:** header **Logout** returns to Login (SDK state persists).
    Settings → **Change API key & setup** wipes local config and returns to Setup.
