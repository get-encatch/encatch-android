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

Modeled on the richer `encatch-flutter-tester` reference app (see
`/Users/godwin/Desktop/cmss/projects/schema-definition/sdk/integrations/encatch-flutter-tester`
in the sibling schema-definition repo):

- **Setup** — environment picker (Dev/UAT/Prod, each a preset `apiBaseUrl`/`webHost` pair), API
  key, default form id, optional interceptor test form id. Saved locally (`SharedPreferences`) and
  restored on next launch until cleared.
- **Login** — a locally-saved **test users** list (username/email/display name — independent of
  the SDK, its own `SharedPreferences` JSON blob) you can add to, select from, and edit before
  signing in. **Identify user** calls `Encatch.identifyUser(username, traits)`.
- **Edit profile** — email/display name → `identifyUser`'s `UserTraits(set = ...)`.
- **Bottom navigation** — Home / Events / Settings / Inline (Any) / Inline (Exact).
- **Header** — a tap-to-cycle theme button (`Encatch.setTheme`/`.theme`, system → light → dark) and
  **Logout** (`resetUser()`), shared across every tab.
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
  `ShowFormResponse.questionnaireFields`, submitted via `buildSubmitRequest`/`Encatch.submitForm`
  and driven end-to-end with manual `Encatch.emitEvent(FORM_SHOW/STARTED/SUBMIT/COMPLETE/CLOSE)`
  calls — demonstrates fully replacing the SDK's own rendering with native UI.
- **Inline (Exact)** — an exact-match `EncatchInlineFormView` claiming the default form id.
- **Inline (Any)** — a wildcard `EncatchInlineFormView` (`formId = null`) you can target by typing
  any other form id, plus a button that shows a deliberately unmatched form id to demonstrate the
  modal fallback.
- **Settings** — environment/form id/interceptor id summary, **Set Locale → fr-FR**
  (`setLocale`), **Set Country → FR** (`setCountry`), and **Change API key & setup** (clears local
  config and SDK state, returns to Setup).
- **Screen tracking** — Each screen calls `trackScreen(...)` on first composition.
- **CTA navigation** — A single `Encatch.on(...)` listener registered at app start watches for
  `form:ctaTriggered` events with `action == "app_navigate"`: routes `"billing"`/`"billing/upgrade"`
  navigate to the in-app **Billing** screen; any other route navigates to **Route not found**.

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
