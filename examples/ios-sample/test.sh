#!/bin/bash
# Runs EncatchSampleUITests against a booted iOS Simulator. Requires :mock-server running first
# (`./gradlew :mock-server:run` from the repo root) — the app defaults to http://127.0.0.1:8089,
# override with MOCK_SERVER_BASE_URL if needed. Screenshots attach to the resulting .xcresult
# bundle (Test Navigator, or `xcrun xcresulttool get` to extract programmatically).
set -euo pipefail
cd "$(dirname "$0")"

DEVICE_ID="${1:-$(xcrun simctl list devices booted -j | python3 -c 'import json,sys; d=json.load(sys.stdin)["devices"]; print(next(dev["udid"] for devs in d.values() for dev in devs))')}"

xcodegen generate
xcodebuild test -project EncatchSample.xcodeproj -scheme EncatchSample -destination "id=$DEVICE_ID"
