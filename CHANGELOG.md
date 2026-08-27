# Changelog

SDK-facing changes to the published packages (`com.encatch:core` / `:android` / `:kmp-sdk` /
`:compose-sdk` on Maven Central, and the `Encatch` Swift Package). Tester-app and tooling
changes are not listed.

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
