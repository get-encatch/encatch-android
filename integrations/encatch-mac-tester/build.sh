#!/bin/bash
# Generates the Xcode project via xcodegen and builds EncatchMacTester for Mac Catalyst, linking
# the pure-Swift `swift/` package directly (same dependency pattern as
# encatch-ios-tester/build.sh, different destination).
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v xcodegen >/dev/null 2>&1; then
    echo "xcodegen not found — install via 'brew install xcodegen'" >&2
    exit 1
fi

echo "Generating Xcode project..."
xcodegen generate

echo "Building EncatchMacTester for Mac Catalyst..."
xcodebuild -project EncatchMacTester.xcodeproj -scheme EncatchMacTester \
    -destination 'platform=macOS,variant=Mac Catalyst' build
