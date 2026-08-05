#!/bin/bash
# Generates the Xcode project via xcodegen and builds the EncatchSample app for the iOS
# Simulator, linking the pure-Swift `ios-native/` package (no Kotlin/Native XCFramework
# dependency). Run `./run.sh`
# afterward to launch it, or `./test.sh` to run the UI test suite (needs :mock-server running
# first, see mock-server/).
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v xcodegen >/dev/null 2>&1; then
    echo "xcodegen not found — install via 'brew install xcodegen'" >&2
    exit 1
fi

echo "Generating Xcode project..."
xcodegen generate

DEVICE_ID="${1:-$(xcrun simctl list devices booted -j | python3 -c 'import json,sys; d=json.load(sys.stdin)["devices"]; ids=[dev["udid"] for devs in d.values() for dev in devs]; print(ids[0] if ids else "")')}"
if [ -z "$DEVICE_ID" ]; then
    echo "No booted simulator found — boot one (e.g. 'xcrun simctl boot <name>') or pass a device UDID as \$1." >&2
    exit 1
fi

echo "Building EncatchSample for simulator $DEVICE_ID..."
xcodebuild -project EncatchSample.xcodeproj -scheme EncatchSample -destination "id=$DEVICE_ID" build
