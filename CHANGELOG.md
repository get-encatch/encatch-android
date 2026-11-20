# Changelog

SDK-facing changes to the published packages (`com.encatch:core` / `:android` / `:kmp-sdk` /
`:compose-sdk` on Maven Central, and the `Encatch` Swift Package). Tester-app and tooling
changes are not listed.

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

- `kmp-sdk` on Android now brings `com.encatch:android` (the survey UI, with its file-chooser
  activity and FileProvider), so modal surveys show with no extra dependency; `compose-sdk` exposes
  it as an API dependency. Keep `com.encatch:android` at the same version if you also declare it.
- `com.encatch:android` depends on `androidx.startup` and adds an `InitializationProvider` entry to
  the manifest. An app that removed that provider calls `EncatchFormHost.install(application)`.

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
