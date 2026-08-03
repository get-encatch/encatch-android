#!/bin/bash
# Prepares all three iOS tester apps for a real-device install (builds XCFrameworks, generates
# Xcode projects) and opens each one in Xcode. Free-account Apple IDs can't complete Xcode's
# automatic-signing handshake with Apple's servers from a plain `xcodebuild` CLI invocation — it
# has to happen through an interactive Xcode session — so the actual build+install step is you
# pressing Run (▶) in Xcode for each project, with your device selected as the destination.
#
# Usage: ./scripts/install-testers-on-device.sh
#
# For each project this opens:
#   1. Make sure the scheme's destination (next to the ▶ button) is your device, e.g. "Godwin
#      iPhone" — not a Simulator.
#   2. Press ▶ Run. First time only: Xcode will register the device + create/download a
#      provisioning profile from Apple (needs your Xcode account signed in — Xcode > Settings >
#      Accounts). If the app doesn't launch, unlock the device and go to Settings > General > VPN
#      & Device Management to trust the developer certificate, then run again.
#   3. Free-account builds expire after 7 days — re-run this script and hit ▶ again to refresh.
#
# For Android, just `adb install -r <apk>` — no signing story needed, see
# scripts/build-testers-for-distribution.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v xcodegen >/dev/null 2>&1; then
    echo "xcodegen not found — install via 'brew install xcodegen'" >&2
    exit 1
fi

echo "--- Building iOS XCFrameworks ---"
./gradlew \
    :integrations:encatch-kmp-tester:assembleEncatchKmpTesterDebugXCFramework \
    :integrations:encatch-compose-tester:assembleEncatchComposeTesterDebugXCFramework
echo

for dir in encatch-ios-tester encatch-kmp-tester-ios encatch-compose-tester-ios; do
    echo "--- Generating Xcode project: $dir ---"
    (cd "integrations/$dir" && xcodegen generate)
done
echo

PROJECTS=(
    "integrations/encatch-ios-tester/EncatchIosTester.xcodeproj"
    "integrations/encatch-kmp-tester-ios/EncatchKmpTester.xcodeproj"
    "integrations/encatch-compose-tester-ios/EncatchComposeTester.xcodeproj"
)
for p in "${PROJECTS[@]}"; do
    open "$p"
done

cat <<'EOF'
=== Opened all three projects in Xcode ===

For each one: pick your iPhone as the run destination (next to the ▶ button, top-left) and press
▶ Run. That's it — Xcode installs and launches it on your device. Repeat for all three windows.

Free-account builds expire after 7 days; re-run this script and hit ▶ again to refresh.
EOF
