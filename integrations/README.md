# Integrations

Standalone tester apps you can run in an emulator/simulator and hand to real testers to exercise
an Encatch SDK end-to-end — separate from `sample-app`/`compose-sample`/`kmp-sample`/`ios-sample`
(which are minimal manual-verification harnesses used by this repo's own regression tests).

Modeled on the richer `encatch-flutter-tester` reference app in the sibling `schema-definition`
repo (itself descended from `integrations/encatch-expo-tester` in `encatch-typescript`): a runtime
**Setup** screen where a tester enters their own API key/config and picks an environment
(Dev/UAT/Prod) — no rebuild needed per tester or environment — then (bottom-nav on the mobile
testers, a sidebar on `encatch-mac-tester`) a flow covering locally-saved test-user login/edit-
profile, event/screen tracking, prefilled + modal + inline forms, an interceptor flow that hands
blocked forms off to a fully custom native form renderer, theme cycling and locale/country
setters, CTA navigation, and settings/logout.

| App | SDK under test |
|---|---|
| [`encatch-android-tester`](encatch-android-tester/README.md) | `com.encatch:android` |
| [`encatch-ios-tester`](encatch-ios-tester/README.md) | `ios-native` (Swift Package) |
| [`encatch-mac-tester`](encatch-mac-tester/README.md) | `ios-native` (Swift Package, Mac Catalyst) |
| [`encatch-kmp-tester`](encatch-kmp-tester/README.md) + [`encatch-kmp-tester-ios`](encatch-kmp-tester-ios/README.md) | `com.encatch:kmp-sdk` (Android app + iOS host, one shared `commonMain` layer) |
| [`encatch-compose-tester`](encatch-compose-tester/README.md) + [`encatch-compose-tester-ios`](encatch-compose-tester-ios/README.md) | `com.encatch:compose-sdk` (Android app + iOS host, one shared Compose UI) |

All four SDK packages now have a tester app at this richer level of parity.
`encatch-mac-tester` is a Mac Catalyst sibling of `encatch-ios-tester` testing the same
`ios-native` package, but with a genuinely Mac-native UI (sidebar, real Preferences window,
system controls) rather than a ported phone screen — see its README's "macOS-native decisions"
section.
