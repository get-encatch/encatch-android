# encatch-android-tester

Standalone Android app for testing `com.encatch:android` — modeled on `encatch-expo-tester` in the
`encatch-typescript` monorepo. Unlike `sample-app` (this repo's minimal manual-verification
harness), this app has a runtime **Setup** screen so one APK works for any tester or environment,
no rebuild required.

Depends on `:android`/`:core` as internal Gradle modules (these SDKs aren't published to a real
Maven repo yet — see [`android/README.md`](../../android/README.md) for what a real external
consumer's dependency line looks like once they are).

## Run in an emulator

```bash
./gradlew :integrations:encatch-android-tester:installDebug
```

Then launch **Encatch Tester** from the emulator's app drawer.

## Build an APK to send to a tester

```bash
./gradlew :integrations:encatch-android-tester:assembleDebug
# APK: integrations/encatch-android-tester/build/outputs/apk/debug/encatch-android-tester-debug.apk
```

## Features

- **Setup** — API key, default form id, optional API base URL / web host / interceptor test form
  id. Saved locally (`SharedPreferences`) and restored on next launch until cleared from Settings.
- **Login** — Mock login calls `Encatch.identifyUser(username)`.
- **Home** — Tracks `home_viewed` on load. **Show form (modal)** calls `showForm` with the
  configured default form id. **Interceptor test** (shown only if an interceptor form id was set)
  calls `showForm` for that id, which is held by `EncatchConfig.onBeforeShowForm` until you answer
  the on-screen Allow/Deny dialog — demonstrates replacing the SDK form with native UI.
- **Events** — Buttons for `button_clicked`, `feature_used`, `purchase_started`, `survey_viewed`.
- **Inline** — An exact-match `EncatchInlineFormView` claiming the default form id, and a wildcard
  slot (`formId = null`) you can target by typing any other form id.
- **Settings** — **Log out** calls `resetUser()`. **Clear saved setup** calls `clearAll()` and
  wipes local prefs, returning to Setup.
- **Screen tracking** — Each screen calls `trackScreen(...)` on first composition.
- **CTA navigation** — A single `Encatch.on(...)` listener registered at app start watches for
  `form:ctaTriggered` events with `action == "app_navigate"`: routes `"billing"`/`"billing/upgrade"`
  navigate to the in-app **Billing** screen; any other route navigates to **Route not found**.

## Manual test checklist

1. **Setup:** Launch the app, enter an API key and a default form id, tap **Save & continue**.
2. **Login:** Enter a username, tap **Log in**.
3. **Modal form:** Home → **Show form (modal)** → opens as a modal overlay.
4. **Interceptor:** Set an interceptor form id in Setup, then Home → **Interceptor test** → the
   SDK form is blocked; the InterceptorDialog appears. Tap **Allow** to let the SDK form open, or
   **Deny** to simulate a native replacement.
5. **Exact inline:** Inline → the top `EncatchInlineFormView` renders the default form id
   automatically once you tap **Show exact inline form**.
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
