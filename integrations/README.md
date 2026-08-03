# Integrations

Standalone tester apps you can run in an emulator/simulator and hand to real testers to exercise
an Encatch SDK end-to-end — separate from `sample-app`/`compose-sample`/`kmp-sample`/`ios-sample`
(which are minimal manual-verification harnesses used by this repo's own regression tests).

Modeled on the `integrations/encatch-expo-tester` app in the `encatch-typescript` monorepo: a
runtime **Setup** screen where a tester enters their own API key/config (no rebuild needed per
tester or environment), then screens covering login, event tracking, modal/inline forms, CTA
navigation, and settings/logout.

| App | SDK under test |
|---|---|
| [`encatch-android-tester`](encatch-android-tester/README.md) | `com.encatch:android` |
| [`encatch-ios-tester`](encatch-ios-tester/README.md) | `ios-native` (Swift Package) |
| [`encatch-kmp-tester`](encatch-kmp-tester/README.md) + [`encatch-kmp-tester-ios`](encatch-kmp-tester-ios/README.md) | `com.encatch:kmp-sdk` (Android app + iOS host, one shared `commonMain` layer) |
| [`encatch-compose-tester`](encatch-compose-tester/README.md) + [`encatch-compose-tester-ios`](encatch-compose-tester-ios/README.md) | `com.encatch:compose-sdk` (Android app + iOS host, one shared Compose UI) |

All four SDK packages now have a tester app.
