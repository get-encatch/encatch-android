#!/usr/bin/env bash
#
# Reproduces ios-native/dist/{ios-arm64,sim-arm64}/ — the cinterop-consumable artifact
# (static lib + generated ObjC header + swiftmodule) that compose-sample/kmp-sample's Kotlin/Native
# iOS targets cinterop against (see their build.gradle.kts + src/nativeInterop/cinterop/
# EncatchBridge.def). SwiftPM's plain `swift build` doesn't emit an ObjC header/static-lib layout
# cinterop can use, so this drives `xcodebuild` (which does, via its auto-resolved SPM scheme)
# directly, then re-packages the outputs by hand:
#
#   Device (arm64):
#     xcodebuild build -scheme Encatch -destination 'generic/platform=iOS' -derivedDataPath .xcbuild-dev
#     -> Build/Products/Debug-iphoneos/Encatch.o (single-arch arm64 already)
#     -> libtool -static -> dist/ios-arm64/libEncatch.a
#     -> header from .../Objects-normal/arm64/Encatch-Swift.h (the single-arch one — NOT the
#        multi-arch GeneratedModuleMaps-* copy, which has #if __arm64__/#elif __x86_64__ guards)
#
#   Simulator (universal arm64+x86_64 by default; we only need arm64 — Apple Silicon host):
#     xcodebuild build -scheme Encatch -destination 'generic/platform=iOS Simulator' -derivedDataPath .xcbuild-sim
#     -> Build/Products/Debug-iphonesimulator/Encatch.o (universal)
#     -> lipo -extract arm64 -> single-arch .o
#     -> libtool -static -> dist/sim-arm64/libEncatch.a
#     -> header from the same Objects-normal/arm64/Encatch-Swift.h pattern
#
# Safe/idempotent to re-run: skips the xcodebuild dance entirely if dist/ already looks up to date
# for the current Sources/ tree (content hash stamped in dist/.build-stamp), unless -f/--force is
# passed. Background: dist/ exists so :kmp-sdk/:compose-sdk's Kotlin/Native iOS targets can
# cinterop against a prebuilt static lib instead of compiling Swift sources themselves.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

FORCE=0
for arg in "$@"; do
  case "$arg" in
    -f|--force) FORCE=1 ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: $0 [-f|--force]" >&2
      exit 1
      ;;
  esac
done

DIST_DIR="$SCRIPT_DIR/dist"
STAMP_FILE="$DIST_DIR/.build-stamp"
SCHEME="Encatch"
DEV_DERIVED_DATA="$SCRIPT_DIR/.xcbuild-dev"
SIM_DERIVED_DATA="$SCRIPT_DIR/.xcbuild-sim"

# ---------------------------------------------------------------------------
# Staleness check: hash Sources/ (the only inputs that affect the compiled output) plus this
# script itself, so edits to the build recipe also invalidate the cache.
# ---------------------------------------------------------------------------
compute_source_hash() {
  # find | sort gives a stable file order across machines/runs; shasum over paths+contents so
  # renames/adds/removes are also detected, not just content edits.
  find "$SCRIPT_DIR/Sources" -type f \( -name '*.swift' \) -print0 \
    | sort -z \
    | xargs -0 shasum -a 256 \
    | shasum -a 256 \
    | awk '{print $1}'
}

CURRENT_HASH="$(compute_source_hash)-$(shasum -a 256 "$SCRIPT_DIR/build-dist.sh" | awk '{print $1}')"

if [[ "$FORCE" -eq 0 ]] && [[ -f "$STAMP_FILE" ]] \
  && [[ -f "$DIST_DIR/ios-arm64/libEncatch.a" ]] && [[ -f "$DIST_DIR/sim-arm64/libEncatch.a" ]] \
  && [[ -f "$DIST_DIR/ios-arm64/Encatch-Swift.h" ]] && [[ -f "$DIST_DIR/sim-arm64/Encatch-Swift.h" ]]; then
  if [[ "$(cat "$STAMP_FILE")" == "$CURRENT_HASH" ]]; then
    echo "ios-native/dist/ is up to date (Sources/ unchanged since last build). Skipping xcodebuild."
    echo "Run with -f/--force to rebuild anyway."
    exit 0
  fi
fi

echo "Rebuilding ios-native/dist/ ..."

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR/ios-arm64" "$DIST_DIR/sim-arm64"

build_slice() {
  local platform_dest="$1"     # e.g. 'generic/platform=iOS'
  local derived_data="$2"
  local products_subdir="$3"   # e.g. Debug-iphoneos
  local out_dir="$4"           # e.g. dist/ios-arm64
  local needs_lipo="$5"        # "1" for the universal simulator .o, "0" for device

  echo "== xcodebuild ($platform_dest) =="
  rm -rf "$derived_data"
  xcodebuild build \
    -scheme "$SCHEME" \
    -destination "$platform_dest" \
    -derivedDataPath "$derived_data" \
    -quiet

  local products_dir="$derived_data/Build/Products/$products_subdir"
  local objects_dir="$derived_data/Build/Intermediates.noindex/${SCHEME}.build/$products_subdir/${SCHEME}.build/Objects-normal/arm64"

  local raw_o="$products_dir/${SCHEME}.o"
  if [[ ! -f "$raw_o" ]]; then
    echo "ERROR: expected build output not found: $raw_o" >&2
    exit 1
  fi

  local static_input_o="$raw_o"
  if [[ "$needs_lipo" == "1" ]]; then
    static_input_o="$derived_data/${SCHEME}-arm64.o"
    lipo -extract arm64 "$raw_o" -o "$static_input_o"
  fi

  libtool -static "$static_input_o" -o "$out_dir/libEncatch.a"

  local header_src="$objects_dir/${SCHEME}-Swift.h"
  if [[ ! -f "$header_src" ]]; then
    echo "ERROR: expected generated header not found: $header_src" >&2
    exit 1
  fi
  cp "$header_src" "$out_dir/${SCHEME}-Swift.h"

  local swiftmodule_src="$products_dir/${SCHEME}.swiftmodule"
  if [[ -d "$swiftmodule_src" ]]; then
    cp -R "$swiftmodule_src" "$out_dir/${SCHEME}.swiftmodule"
  fi
}

build_slice "generic/platform=iOS" "$DEV_DERIVED_DATA" "Debug-iphoneos" "$DIST_DIR/ios-arm64" "0"
build_slice "generic/platform=iOS Simulator" "$SIM_DERIVED_DATA" "Debug-iphonesimulator" "$DIST_DIR/sim-arm64" "1"

echo "$CURRENT_HASH" > "$STAMP_FILE"

echo "ios-native/dist/ rebuilt:"
find "$DIST_DIR" -maxdepth 2 -mindepth 1 | sort
