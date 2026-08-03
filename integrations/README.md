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

Testers for `ios-native`, `kmp-sdk`, and `compose-sdk` are planned but not built yet.
