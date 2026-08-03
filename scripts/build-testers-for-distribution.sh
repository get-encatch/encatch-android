#!/bin/bash
# One-command build of all six tester apps (three Android APKs + three iOS Simulator .app
# bundles), collected into a single timestamped folder ready to hand to a team for manual
# testing. Mac-only (iOS builds require Xcode + xcodegen).
#
# Usage: ./scripts/build-testers-for-distribution.sh [ios-udid]
#   ios-udid  defaults to an already-booted simulator, or boots the first available iPhone
#             simulator if none is booted.
#
# NOTE on iOS: these Xcode projects have no code-signing team configured (see
# integrations/*-ios/project.yml), so this produces iOS-Simulator-only .app bundles, not
# device-installable .ipa files. Testers need a Mac with Xcode/Simulator to run them — see the
# generated DISTRIBUTE.md for install steps. If real-device (ad-hoc/TestFlight) builds are
# needed instead, that requires configuring an Apple Developer signing team first.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

if ! command -v xcodegen >/dev/null 2>&1; then
    echo "xcodegen not found — install via 'brew install xcodegen'" >&2
    exit 1
fi

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$REPO_ROOT/dist/testers-${TIMESTAMP}"
DERIVED_DIR="$OUT_DIR/_derived"
mkdir -p "$OUT_DIR/android" "$OUT_DIR/ios" "$DERIVED_DIR"

echo "=== Encatch tester apps: build for distribution ==="
echo "Output: $OUT_DIR"
echo

# ---------------------------------------------------------------------------
# 1. Android APKs + iOS XCFrameworks (single Gradle invocation)
# ---------------------------------------------------------------------------
echo "--- Building Android APKs + iOS XCFrameworks ---"
./gradlew \
    :integrations:encatch-android-tester:assembleDebug \
    :integrations:encatch-kmp-tester:assembleDebug \
    :integrations:encatch-kmp-tester:assembleEncatchKmpTesterDebugXCFramework \
    :integrations:encatch-compose-tester:assembleDebug \
    :integrations:encatch-compose-tester:assembleEncatchComposeTesterDebugXCFramework

cp integrations/encatch-android-tester/build/outputs/apk/debug/*.apk "$OUT_DIR/android/"
cp integrations/encatch-kmp-tester/build/outputs/apk/debug/*.apk "$OUT_DIR/android/"
cp integrations/encatch-compose-tester/build/outputs/apk/debug/*.apk "$OUT_DIR/android/"
echo "Android APKs copied to $OUT_DIR/android/"
echo

# ---------------------------------------------------------------------------
# 2. iOS Simulator .app builds
# ---------------------------------------------------------------------------
IOS_UDID="${1:-}"
if [ -z "$IOS_UDID" ]; then
    IOS_UDID="$(xcrun simctl list devices booted -j | python3 -c 'import json,sys; d=json.load(sys.stdin)["devices"]; ids=[dev["udid"] for devs in d.values() for dev in devs]; print(ids[0] if ids else "")')"
fi
BOOTED_BY_SCRIPT=0
if [ -z "$IOS_UDID" ]; then
    echo "No booted simulator found — booting the first available iPhone simulator..."
    IOS_UDID="$(xcrun simctl list devices available -j | python3 -c '
import json, sys
data = json.load(sys.stdin)["devices"]
for runtime, devs in data.items():
    if "iOS" not in runtime:
        continue
    for dev in devs:
        if dev.get("name", "").startswith("iPhone"):
            print(dev["udid"])
            sys.exit()
')"
    if [ -z "$IOS_UDID" ]; then
        echo "No available iPhone simulator found — create one in Xcode first." >&2
        exit 1
    fi
    xcrun simctl boot "$IOS_UDID"
    BOOTED_BY_SCRIPT=1
fi
echo "iOS Simulator device: $IOS_UDID"
echo

build_ios_app() {
    local dir="$1" project="$2" scheme="$3" app_name="$4"
    echo "--- Building $scheme (iOS Simulator) ---"
    (cd "integrations/$dir" && xcodegen generate)
    xcodebuild \
        -project "integrations/$dir/$project" \
        -scheme "$scheme" \
        -destination "id=$IOS_UDID" \
        -derivedDataPath "$DERIVED_DIR/$dir" \
        build | tail -20

    local app_path="$DERIVED_DIR/$dir/Build/Products/Debug-iphonesimulator/$app_name.app"
    if [ ! -d "$app_path" ]; then
        echo "Expected build output not found at $app_path" >&2
        exit 1
    fi
    (cd "$(dirname "$app_path")" && zip -qr "$OUT_DIR/ios/$app_name-iOSSimulator.zip" "$app_name.app")
    echo "Zipped to $OUT_DIR/ios/$app_name-iOSSimulator.zip"
    echo
}

build_ios_app "encatch-ios-tester" "EncatchIosTester.xcodeproj" "EncatchIosTester" "EncatchIosTester"
build_ios_app "encatch-kmp-tester-ios" "EncatchKmpTester.xcodeproj" "EncatchKmpTesterApp" "EncatchKmpTesterApp"
build_ios_app "encatch-compose-tester-ios" "EncatchComposeTester.xcodeproj" "EncatchComposeTesterApp" "EncatchComposeTesterApp"

if [ "$BOOTED_BY_SCRIPT" = "1" ]; then
    xcrun simctl shutdown "$IOS_UDID" || true
fi

rm -rf "$DERIVED_DIR"

# ---------------------------------------------------------------------------
# 3. Distribution notes
# ---------------------------------------------------------------------------
cat > "$OUT_DIR/DISTRIBUTE.md" <<'EOF'
# Encatch tester apps — manual testing build

## Android

Install any APK directly on a device/emulator with USB debugging enabled:

    adb install -r android/encatch-android-tester-debug.apk

(or just AirDrop/Slack/email the `.apk` — most Android devices can sideload it directly after
enabling "install from unknown sources").

## iOS (Simulator only — no device signing configured)

These `.app` bundles are built for the iOS **Simulator**, not a physical iPhone — they require a
Mac with Xcode installed. To run one:

1. Unzip the `.zip`.
2. Open Simulator.app (or `xcrun simctl boot "iPhone 15"`).
3. Drag the `.app` onto the running Simulator window — or run:

       xcrun simctl install booted EncatchIosTester.app
       xcrun simctl launch booted com.encatch.iostester

   (bundle IDs: `com.encatch.iostester`, `com.encatch.kmptester.ios`, `com.encatch.composetester.ios`)

If your team needs a real-device install (ad-hoc `.ipa` / TestFlight) instead, that requires
configuring an Apple Developer signing team in the `integrations/*-ios/project.yml` files first —
none is currently set up.
EOF

echo "=== Done ==="
echo "Distribution folder: $OUT_DIR"
ls -la "$OUT_DIR/android" "$OUT_DIR/ios"
