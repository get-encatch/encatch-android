# Changelog

SDK-facing changes to the published packages (`com.encatch:core` / `:android` / `:kmp-sdk` /
`:compose-sdk`, from Encatch's Maven repository at `packages.encatch.com`, and the `Encatch` Swift
Package). Kotlin versions 0.1.0 and 0.1.1 are also on Maven Central. Tester-app and tooling
changes are not listed.

## 0.2.0-beta.7

Kotlin and Swift (both packages are at 0.2.0-beta.7 again), as the React Native SDK 1.5.0.

### Added

- **`onSignatureExpired` in `EncatchConfig`.** Called when the current user's secure-identify
  signature has expired, with the user's name, so the app can get a fresh signature from its server
  and call `identifyUser` again with it. A rejected new signature calls it once per user until an
  identify for them succeeds; the stored signature expiring always does (so at most twice). It runs
  after the SDK has handled the reply, only while that user is still current, and not after
  `stop()`, `clearAll()` or a re-init; an error it throws is logged. The SDK learns of an expiry
  only when a request the API checks goes out (today `identifyUser` and `setUserProperties`). Also
  on the KMP and Compose `EncatchConfig` and the Objective-C bridge. In Swift it is passed with its
  own label, so an existing `EncatchConfig(...) { payload in ... }` trailing closure still means
  `onBeforeShowForm`.

### Changed

- **Only a real expiry pauses tracking.** A 401 counts as an expired signature only when the API
  says so (`reason: "user_signature_expired"`), or, on older APIs that give no reason, when
  `setUserProperties` re-sent the saved signature with its time. Before, any 401 on a request
  carrying a saved session-time signature paused events, screens, ping and automatic surveys,
  though the API only checks the signature on identify. A new signature rejected as expired only
  calls `onSignatureExpired`: it changes nothing else.
- **Survey answers go only to the user they belong to.** The SDK remembers who each survey was
  shown to; its answers are sent only while that user is current (checked again before each send
  attempt, after a rate-limit pause), and are dropped with a warning once another user is signed in
  or the user signs out. A visitor's answers are still sent if the visitor signs in meanwhile. A
  survey asked for a user who is no longer current when it arrives is not shown. `clearAll()` or a
  re-init drops a survey submit still waiting; held submits are only ever sent as the user who made
  them.

### Fixed

- **Swift: two identical requests are now the same bytes** (keys sorted), so the API's 60-second
  protection against a repeated identical identify (a doubled `$increment`) works as on Android.
- **Android:** a survey open when its activity is destroyed (a rotation, say) is now closed, so the
  next survey can show; a survey's close is reported once, and a dialog already closed no longer
  reports a close that could end the next survey.

## 0.2.0-beta.6

Kotlin only (the Swift package stays at 0.2.0-beta.5).

### Fixed

- **An app had to add `kotlinx-serialization-json` itself to build `UserTraits`.** `core` and
  `kmp-sdk` use its `JsonElement` and `JsonObject` in public types (`UserTraits`, the show-form
  payloads) but didn't pass the library on to apps, so code like `JsonPrimitive("team")` didn't
  compile without it. They now do, and `android` and `compose-sdk` pass it on with them. Apps that
  already add it are unaffected.

## 0.2.0-beta.5

Repeat identifies are skipped, and a new call updates the user's properties, as in the React Native
SDK 1.5.0. Kotlin and Swift alike.

### Changed

- **A repeat `identifyUser()` is no longer sent.** After the first identify of each app run, a call
  for the same user with the same traits, or with none, sends nothing (it is logged in debug mode).
  A different user, a new or removed signature, a locale or country the last identify didn't carry,
  and changed traits are sent as before. Use `setUserProperties` to update properties again on
  purpose. Traits with a number that isn't finite are refused with a warning instead of failing in
  the request queue.

### Added

- **`Encatch.setUserProperties(userName, traits)`** (Kotlin, Swift, the ObjC bridge, `kmp-sdk` and
  `compose-sdk` on both platforms, and SwiftUI's `EncatchContext`): updates the identified user's
  properties with the same traits as `identifyUser`. Never skipped. Ignored unless `userName` is the
  current user and their identify succeeded (or they were restored with their id), so a pending or
  failed switch can't update the wrong person. Waits for an identify of that user still in flight and
  for an open survey. Updates go out in call order; it counts against the identify rate limit; an
  update with `$increment` or `$decrement` is never retried.

### Fixed

- **`clearAll()` (Kotlin) now also empties the request queue**, as Swift's did: nothing queued
  before it is sent afterwards.

## 0.2.0-beta.4

SwiftUI support in the Swift package.

### Added

- **SwiftUI** (Swift package, iOS 15+), with the React Native and Flutter SDKs' names:
  `EncatchProvider(apiKey:config:)` initializes the SDK once and installs the modal form host;
  `EncatchContext` (read with `@EnvironmentObject`, React Native's `useEncatch()`) publishes
  `isInitialized`, `isIdentified` and `userName` and has the same call-and-forget methods;
  `EncatchInlineForm(formId:minHeight:onOverlayOpenChange:)` is an inline survey that sizes itself;
  `.encatchTrackScreen(_:)` tracks a screen when it appears. The UIKit views are unchanged.

## 0.2.0-beta.3

Two fixes in saving the locale and country. Kotlin and Swift.

### Fixed

- **Kotlin: a saved locale or country could be lost.** `setLocale()` and `setCountry()` saved in the
  background, so a save made at the same moment (`identifyUser` with `locale`/`country`, or another
  `setLocale`) could be overwritten, and the value was not restored at the next launch. They save
  straight away now. Both SDKs also save the locale and the country one at a time, so saves from
  several threads can't overwrite each other. The values in use during the app run were not affected.
- **Swift: `setLocale()` and `setCountry()` before `initialize()` crashed.** They no longer do; call
  them after `initialize()` for the value to be saved.

## 0.2.0-beta.2

The sign-in state and the option to keep the user only for the current app run, as in the React
Native SDK 1.5.0. Kotlin and Swift alike.

### Added

- **`Encatch.isIdentified()`** (Kotlin, Swift, and `kmp-sdk`/`compose-sdk` on both platforms): `true`
  once an identify succeeded, or at launch when the user saved by an earlier run is restored with
  their user id. `false` before `init`, for a visitor, and while a first identify is pending or after
  it failed. Switching to another user keeps describing the previous user until the new user's
  identify succeeds. `resetUser()`, `clearAll()` and the server's "retries exhausted" reset make it
  `false`; `stopSession()` and an expired secure signature leave it as it is.
- **`EncatchConfig.persistUserAcrossRestarts`** (default `true`, unchanged behaviour). With `false`,
  the user (user name, user id, secure signature, survey history) lives in memory for the current
  app run only: every launch starts as a visitor until the app calls `identifyUser` again, and user
  data an earlier run saved is deleted at `init`. The device id, a saved `stopSession()` and the
  locale and country preferences are still saved.

## 0.2.0-beta.1

The native SDKs now behave as the web SDK, React Native 1.4.4 and Flutter 1.2.0 do: same server
contract, same survey and identity rules. Kotlin (`core`, used by `android`, `kmp-sdk` and
`compose-sdk` on Android) and Swift (iOS, and KMP/Compose on iOS) alike.

### Fixed

- **iOS: partial saves went out as final submits.** The survey page sends `isPartialSubmit` as a
  JSON boolean; the Swift SDK read it as text, so every partial save (on the timer and on close) was sent
  to submit-form as a final submit, and a later ✕ was not counted as a dismissal. Native iOS, and KMP and
  Compose on iOS (Android was not affected).
- **Surveys launched by an event, on identify or in a ping never opened.** The server offers a
  survey as `nextFeedbackId` (+ `onPageDelay`) in replies to identify-user, ping, track-event and
  track-screen; only track-screen replies were read. Every reply is read now, one survey at a time:
  an automatic survey is skipped while another is waiting, loading or open; asking again for the
  open survey keeps the answers in progress; "not eligible now" (404) is logged at debug only.
- **Closing a survey told the server nothing.** Closing it before the final submit (its ✕, a tap
  outside it, the back button) is now a dismissal: `dismiss-form` with the form's UUID — sent for
  visitors too, as the web SDK does — and `form:dismissed` for an identified user. Closing it on the thank-you screen, after "remind me later" or a
  call-to-action is not. The survey page is first asked to save a partial answer (up to 500 ms).
- **"Remind me later" was not sent.** It now sends `form:remindmelater` with the form's id
  (identified users), so the survey can come back after its wait. It is not a dismissal.
- **Compose on Android: a modal survey never appeared** unless an inline form had been composed
  first. The form host now installs itself at app start (AndroidX App Startup) for every Android
  package; `EncatchFormHost.install(...)` is no longer needed and harmless if kept.
- **Survey events from visitors** (403 from the server) are no longer sent; survey events and
  dismissals no longer go out with an empty form id.
- **Half-identified users got 401 on everything.** `X-User-Name` and `X-User-Id` go out together
  or not at all; identify-user sends its own name and never a stored `X-User-Id`; reset and
  identify run one after the other, and a late reply for a user who is no longer current is
  ignored; `userPendingRetryExhausted` (the server's spelling) resets the user.
- **Secure identify:** the signature goes in the `X-User-Signature` header with
  `X-User-Session-Time` / `X-User-Signature-Time`, then on every request of that user. It is stored
  on the device in plain app storage (SharedPreferences / UserDefaults) until a reset, an identify
  without `secure`, another user or its expiry. For keys with a session time, a 401 on a signed
  request means it expired: events, screens and pings are skipped and survey submits held (in
  memory, the latest 10) until the next identify; held submits are dropped if another user is
  identified, on reset, re-init or app restart. The signature is masked in logs.
- **Ping:** the server's `pingAgainIn` is honoured from every reply; no ping while a survey is open.
  `stopSession()` holds, also across a restart, until `startSession()`, an identify, a reset or a
  re-init; it also closes an open survey.
- **Request bursts:** after a 429, calls of that group (tracking, show-form, submit) made during
  the `Retry-After` pause (60 s by default, at most 5 minutes) are skipped, not queued; an identify
  is held (the latest) and survey submits wait and retry up to 3 times. One retry-queue flush at a
  time; a 4xx drops only that item. A repeat of the last identify (same user, traits, locale,
  country, signature) is not sent again until the user changes, resets or it fails; identical
  events, or the same screen, within 1 s are sent once.
- **`$sdkVersion`** now reports this version.

### Added

- `Encatch.setFormVisible(visible, formId)` (Kotlin and Swift); the one-argument form is kept.
- Android: `EncatchFormHost.attach(activity)` / `attach(context)` and `EncatchFormHost.isInstalled`.

### Changed

- **The Kotlin packages are published to Encatch's Maven repository, `https://packages.encatch.com/maven`,
  instead of Maven Central.** Add the repository (see each package's README) before updating past
  0.1.1, or Gradle reports "Could not find com.encatch:…". 0.1.0 and 0.1.1 stay on Maven Central too.
- `kmp-sdk` on Android now brings `com.encatch:android` (the survey UI, with its file-chooser
  activity and FileProvider), so modal surveys show with no extra dependency; `compose-sdk` exposes
  it as an API dependency. Keep `com.encatch:android` at the same version if you also declare it.
- `com.encatch:android` depends on `androidx.startup` and adds an `InitializationProvider` entry to
  the manifest. An app that removed that provider calls `EncatchFormHost.install(application)`.
- **Not public API:** `InternalEvent`, `ShowFormPayload`, `EncatchInternalEmitter`,
  `FormWebViewBridge` and the WebView messages (`SDKMessage`, `SDKMessageType`, `FormMessage`,
  `FormMessageType`), Kotlin and Swift; `EncatchFormDialog` and `EncatchWebView` (Android);
  `EncatchFormViewController` (iOS). They are visible only because the SDK uses them internally,
  and they may change in any release, including minor ones; don't use them in app code. Still
  public API: `EncatchFormHost` (`install`, and on Android `attach` / `isInstalled`),
  `EncatchInlineFormView`, and the `ShowFormInterceptorPayload` (with its `formConfig:
  ShowFormResponse`) passed to `onBeforeShowForm`.

### Known

- A partly answered survey closed with its ✕ keeps no partial answer yet: the survey page
  (`/s/react-native-sdk-form`) does not answer the SDK's save request. Same on React Native and
  Flutter; fixed with the survey page.

## 0.1.1

### Fixed

- **Android (all packages): automatically triggered forms never appeared.** Auto-triggers
  (track-screen / track-event / identify responses) emitted the show-form event from a
  background dispatcher, and the UI layer touched the Dialog/WebView off the main thread — the
  failure was swallowed, so the form silently never showed. Events are now marshaled to the
  main thread (modal and inline), mirroring the iOS SDK's existing behavior.
- **iOS/macOS: form failed to present (or crashed) when `showForm` was called while the host
  app was dismissing its own sheet or modal.** The SDK now skips mid-dismissal view controllers
  when resolving a presenter and waits for a stable one before presenting.
- **Android: dead camera option in the file-upload chooser.** When the host app declares the
  CAMERA permission (any app using the video/audio question type does) but the user hadn't
  granted it yet, tapping the chooser's camera row silently did nothing. The SDK now requests
  CAMERA first and falls back to a plain file picker if denied.
- **Signature upload mode failed with an error (Android and iOS).** User-picked images reach
  the SDK as a `data:<mime>;base64,` data URL, which the strict base64 decoders rejected before
  any network call. The data-URL header is now stripped when present (drawn signatures, sent as
  bare base64, are unaffected).
- **Android: large uploads timed out.** The HTTP client's implicit 10s read timeout (and a
  later fixed cap) killed multi-MB camera/video uploads mid-transfer. Uploads now have no
  total-time limit — only a 120s idle (no-data-moving) timeout — matching the other Encatch
  SDKs; JSON endpoints get explicit 30s timeouts and the Q&A-with-AI stream is exempt.
- **KMP (iOS): `addToResponse` dropped structured values.** `JsonElement` values crossed the
  ObjC bridge as opaque objects and were stringified; they are now flattened to native
  dictionaries/arrays/numbers so prefilled answers arrive intact.

### Added

- Typed form-configuration surface matching `@encatch/schema` 1.5.2 (parity with the Flutter
  SDK 1.1.2): `FormConfigurationResponse` (+ `typedFormConfiguration` accessors),
  `LogicJumpRule`, the completion CTA config types (`CompletionCtaAction`,
  `PlatformCompletionCta`, `CompletionCtaSecondary`, `CompletionCta`), and
  `PaymentsUpiAnswer.fromNumericAmount`. The `annotation` and `payments_upi` question types are
  marked deprecated (kept for backward compatibility).
- Debug network logs now include the multipart upload endpoint (binary body summarized as a
  `<multipart>` line) and capture response headers (e.g. `x-encatch-id`) on every logged call.
- Swift: public `asString` / `asDouble` / `asObject` / `asArray` accessors on `JSONValue`.

## 0.1.0

Initial release.
