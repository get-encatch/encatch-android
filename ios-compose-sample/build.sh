#!/bin/bash
# Builds :compose-sample's debug XCFramework (if needed), generates the Xcode project via
# xcodegen, and builds EncatchComposeSample for the iOS Simulator.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v xcodegen >/dev/null 2>&1; then
    echo "xcodegen not found — install via 'brew install xcodegen'" >&2
    exit 1
fi

FRAMEWORK="../compose-sample/build/XCFrameworks/debug/EncatchComposeSample.xcframework"
if [ ! -d "$FRAMEWORK" ]; then
    echo "Building EncatchComposeSample.xcframework (debug)..."
    (cd .. && ./gradlew :compose-sample:assembleEncatchComposeSampleDebugXCFramework)
fi

echo "Generating Xcode project..."
xcodegen generate

# Same constraint as ios-sample/build.sh: the debug xcframework only has an arm64 simulator
# slice, so building against the generic "iOS Simulator" destination fails to link.
DEVICE_ID="${1:-$(xcrun simctl list devices booted -j | python3 -c 'import json,sys; d=json.load(sys.stdin)["devices"]; ids=[dev["udid"] for devs in d.values() for dev in devs]; print(ids[0] if ids else "")')}"
if [ -z "$DEVICE_ID" ]; then
    echo "No booted simulator found — boot one (e.g. 'xcrun simctl boot <name>') or pass a device UDID as \$1." >&2
    exit 1
fi

echo "Building EncatchComposeSample for simulator $DEVICE_ID..."
xcodebuild -project EncatchComposeSample.xcodeproj -scheme EncatchComposeSampleApp -destination "id=$DEVICE_ID" build
