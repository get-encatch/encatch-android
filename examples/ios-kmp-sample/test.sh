#!/bin/bash
# Runs EncatchKmpSampleAppUITests against a booted iOS Simulator. Requires :mock-server running
# first (`./gradlew :mock-server:run` from the repo root) — the app defaults to
# http://127.0.0.1:8089, override with MOCK_SERVER_BASE_URL if needed. Also requires
# :kmp-sample's debug XCFramework to already be built (see build.sh); this script does not
# rebuild it. Screenshots attach to the resulting .xcresult bundle (Test Navigator, or
# `xcrun xcresulttool get` to extract programmatically).
set -euo pipefail
cd "$(dirname "$0")"

DEVICE_ID="${1:-$(xcrun simctl list devices booted -j | python3 -c 'import json,sys; d=json.load(sys.stdin)["devices"]; print(next(dev["udid"] for devs in d.values() for dev in devs))')}"

FRAMEWORK="../kmp-sample/build/XCFrameworks/debug/EncatchKmpSample.xcframework"
if [ ! -d "$FRAMEWORK" ]; then
    echo "Building EncatchKmpSample.xcframework (debug)..."
    (cd ../.. && ./gradlew :examples:kmp-sample:assembleEncatchKmpSampleDebugXCFramework)
fi

xcodegen generate
# Same constraint as ios-sample/build.sh: the debug xcframework only has an arm64 simulator
# slice, so building/testing against the generic "iOS Simulator" destination fails to link.
xcodebuild test -project EncatchKmpSample.xcodeproj -scheme EncatchKmpSampleApp -destination "id=$DEVICE_ID"
