# Development notes (monorepo-only)

This file stays in the `encatch-android` monorepo — the publish script excludes it (along with
`dist/` and `build-dist.sh`) from the public [`encatch-swift`](https://github.com/get-encatch/encatch-swift)
mirror. Everything user-facing lives in [README.md](README.md), which IS mirrored.

## Building & testing locally

```bash
swift build
swift test
```

Samples/testers in this repo consume the package as a **local** SPM dependency
(`.package(path: "../../swift")`, since both `examples/*` and `integrations/*` app dirs are two
levels below the repo root) — see `examples/ios-sample/`, `integrations/encatch-ios-tester/`,
`integrations/encatch-mac-tester/`.

## dist/ and build-dist.sh

Consumers who cinterop against this package from Kotlin/Native ([`:kmp-sdk`](../kmp-sdk/README.md)/
[`:compose-sdk`](../compose-sdk/README.md)) rely on a compiled artifact at `swift/dist/`
(static `libEncatch.a` + generated ObjC header + swiftmodule per target) — run `./build-dist.sh`
to (re)produce it after changing anything under `Sources/`. Those Gradle modules wire this in
automatically; a plain Swift-only consumer of the package never needs `dist/` or `build-dist.sh`.

**Release rule — a behavior-relevant change under `Sources/` needs TWO tags, not one.**
`:kmp-sdk`/`:compose-sdk`'s published iOS klibs bundle the static library compiled from this
Swift source at publish time, so:

1. `swift-vX.Y.Z` — ships the fix to SPM consumers (pure iOS apps).
2. `kotlin-vX.Y.Z` — re-publishes the Maven artifacts so their embedded iOS static lib picks up
   the same fix. Without this, KMP/Compose customers' iOS builds keep the old Swift behavior
   forever, even though "nothing Kotlin changed".

`:android`/`:core` are unaffected by Swift changes (no dependency on this package), but they
version together with `:kmp-sdk`/`:compose-sdk` under the single root Gradle version, so the
`kotlin-v*` release republishes all four regardless.

## Releasing to encatch-swift (SPM mirror)

The public repo [get-encatch/encatch-swift](https://github.com/get-encatch/encatch-swift) is a
**write-only distribution mirror**: development never happens there, and every release overwrites
its working tree wholesale from this directory. To publish a release, push a `swift-v*` tag —
`.github/workflows/swift-release.yml` runs the publish script on a macOS runner:

```bash
git tag swift-v0.1.4 && git push origin swift-v0.1.4
```

(Fallback: run `scripts/publish-encatch-swift.sh 0.1.4` locally — same flow, your git
credentials instead of the `ENCATCH_SWIFT_PUSH_TOKEN` Actions secret.)

The script (run it from the monorepo root):

1. Preflights: clean git tree here, valid semver, tag not already published.
2. Clones the mirror, replaces its tree with `swift/` minus the exclusion list
   (`dist/`, `build-dist.sh`, `DEVELOPMENT.md`, build dirs).
3. **Leak gate:** greps the staged tree for `/Users/`, `godwin`, `.claude` — aborts on any hit.
4. **Build gate:** `swift build && swift test` inside the staged mirror checkout, proving the
   package is self-contained.
5. Commits `Release X.Y.Z`, tags `X.Y.Z`, and pushes. The GitHub release is then created by
   the mirror's CI — only after both build jobs pass on the CI toolchain (older Swift than a
   typical dev Mac), so a locally-green-but-CI-broken version never becomes a release.

Consumers resolve plain `X.Y.Z` tags in the mirror (`from: "0.1.0"`). In THIS repo, use
prefixed tags (`swift-v0.1.0`) if you want a marker here, since four SDKs share the monorepo.

PRs opened against the mirror should be redirected to this repo; if a patch is accepted there
anyway, hand-apply it here and cut a new release — never merge into the mirror directly, the
next publish would silently overwrite it.

## Mirror CI

`.github/workflows/ci.yml` under `swift/` is inert in the monorepo (GitHub only reads
root-level `.github/`) but ships with the mirror, where it builds and tests every push/PR:
`swift build`/`swift test` on macOS plus an `xcodebuild` compile against an iOS Simulator
destination.
