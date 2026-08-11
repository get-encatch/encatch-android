#!/bin/bash
# Builds encatch-compose-tester's debug XCFramework (if needed), generates the Xcode project via
# xcodegen, and builds EncatchComposeTesterApp for the iOS Simulator. Same shape as
# ios-compose-sample/build.sh.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v xcodegen >/dev/null 2>&1; then
    echo "xcodegen not found — install via 'brew install xcodegen'" >&2
    exit 1
fi

FRAMEWORK="../encatch-compose-tester/build/XCFrameworks/debug/EncatchComposeTester.xcframework"
if [ ! -d "$FRAMEWORK" ]; then
    echo "Building EncatchComposeTester.xcframework (debug)..."
    (cd ../.. && ./gradlew :integrations:encatch-compose-tester:assembleEncatchComposeTesterDebugXCFramework)
fi

echo "Generating Xcode project..."
xcodegen generate

DEVICE_ID="${1:-$(xcrun simctl list devices booted -j | python3 -c 'import json,sys; d=json.load(sys.stdin)["devices"]; ids=[dev["udid"] for devs in d.values() for dev in devs]; print(ids[0] if ids else "")')}"
if [ -z "$DEVICE_ID" ]; then
    echo "No booted simulator found — boot one (e.g. 'xcrun simctl boot <name>') or pass a device UDID as \$1." >&2
    exit 1
fi

echo "Building EncatchComposeTesterApp for simulator $DEVICE_ID..."
xcodebuild -project EncatchComposeTester.xcodeproj -scheme EncatchComposeTesterApp -destination "id=$DEVICE_ID" build
