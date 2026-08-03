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

- **Setup** — API key, default form id, optional API base URL / web host / interceptor test form
  id. Saved locally (`UserDefaults`) and restored on next launch until cleared from Settings.
- **Login** — Mock login calls `Encatch.shared.identifyUser(userName:)`.
- **Home** — Tracks `home_viewed` on load. **Show form (modal)** calls `showForm` with the
  configured default form id. **Interceptor test** (shown only if an interceptor form id was set)
  calls `showForm` for that id, which is held by `EncatchConfig.onBeforeShowForm` until you answer
  the InterceptorSheet — demonstrates replacing the SDK form with native UI.
- **Events** — Buttons for `button_clicked`, `feature_used`, `purchase_started`, `survey_viewed`.
- **Inline** — An exact-match `EncatchInlineFormView` claiming the default form id, and a wildcard
  slot (`formId = nil`) you can target by typing any other form id.
- **Settings** — **Log out** calls `resetUser()`. **Clear saved setup** calls `clearAll()` and
  wipes local prefs, returning to Setup.
- **Screen tracking** — Each screen calls `trackScreen(...)` in `onAppear`.
- **CTA navigation** — A single `Encatch.shared.on(...)` listener registered at app start watches
  for `form:ctaTriggered` events with `action == "app_navigate"`: routes
  `"billing"`/`"billing/upgrade"` navigate to the in-app **Billing** screen; any other route
  navigates to **Route not found**.

## Manual test checklist

1. **Setup:** Launch the app, enter an API key and a default form id, tap **Save & continue**.
2. **Login:** Enter a username, tap **Log in**.
3. **Modal form:** Home → **Show form (modal)** → opens as a modal overlay.
4. **Interceptor:** Set an interceptor form id in Setup, then Home → **Interceptor test** → the
   SDK form is blocked; the InterceptorSheet appears. Tap **Allow** to let the SDK form open, or
   **Deny** to simulate a native replacement.
5. **Exact inline:** Inline → the top `EncatchInlineFormView` renders the default form id once you
   tap **Show exact inline form**.
6. **Wildcard inline:** Inline → enter any other form id → **Show in wildcard slot**.
7. **app_navigate (billing):** Configure a thank-you screen with
   `completionCta.inApp: { action: "app_navigate", route: "billing" }` (or `"billing/upgrade"`).
   Complete that form and trigger the CTA. Expect the overlay to close and navigation to
   **Billing**.
8. **app_navigate (404):** Use a form with an unmapped route, e.g. `"does/not/exist"`. Expect
   **Route not found** with the requested route, then **Go back**.
9. **exit_form + app_navigate + delay:** An `exit_form` CTA with `action: "app_navigate"`,
   `route: "billing"`, and `autoTriggerDelayMs: 5000` — the overlay clears immediately on submit;
   navigation to Billing follows ~5s later on the SDK's own timer.
10. **Log out / clear setup:** Settings → **Log out** returns to Login (SDK state persists).
    **Clear saved setup** wipes local config and returns to Setup.
