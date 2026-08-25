#!/bin/bash
# One-command build + install of all three iOS tester apps onto a connected physical iPhone —
# no Xcode UI needed. Uses xcodebuild's own provisioning-update flow
# (-allowProvisioningUpdates) plus `xcrun devicectl` for the transfer, which resolves the
# signing handshake non-interactively as long as a valid provisioning profile/certificate for
# DEVELOPMENT_TEAM is already present in this Mac's keychain (Xcode > Settings > Accounts, or
# any prior manual Run from Xcode).
#
# Usage: ./scripts/install-testers-on-device.sh [device-udid] [team-id]
#   device-udid  defaults to the first available (paired) physical device from `devicectl`
#   team-id      defaults to $ENCATCH_DEVELOPMENT_TEAM, else the Apple Developer team ID below
#
# Builds are Debug configuration (device testers, not App Store) and launch automatically after
# install. Free-account builds still expire after 7 days — just re-run this script to refresh.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v xcodegen >/dev/null 2>&1; then
    echo "xcodegen not found — install via 'brew install xcodegen'" >&2
    exit 1
fi

DEVICE_ID="${1:-}"
if [ -z "$DEVICE_ID" ]; then
    # Table output is unreliable to awk-parse: device Name can contain spaces (e.g. "Godwin
    # iPhone"), which shifts every column over. --json-output is the only supported script
    # interface (per `devicectl list devices --help`).
    JSON_TMP="$(mktemp)"
    xcrun devicectl list devices --json-output "$JSON_TMP" >/dev/null 2>&1 || true
    DEVICE_ID="$(python3 -c '
import json, sys
try:
    devices = json.load(open(sys.argv[1]))["result"]["devices"]
except Exception:
    sys.exit(0)
for d in devices:
    if d.get("connectionProperties", {}).get("pairingState") == "paired":
        print(d["identifier"]); break
' "$JSON_TMP" 2>/dev/null)"
    rm -f "$JSON_TMP"
fi
if [ -z "$DEVICE_ID" ]; then
    echo "No paired physical device found — connect an iPhone and trust this Mac first (\`xcrun devicectl list devices\`)." >&2
    exit 1
fi
echo "Target device: $DEVICE_ID"

DEVELOPMENT_TEAM="${2:-${ENCATCH_DEVELOPMENT_TEAM:-UG3272Y9F9}}"
echo "Development team: $DEVELOPMENT_TEAM"

# Developer-local Setup-screen defaults (git-ignored; see dev-tester-defaults.properties.example).
# Injected into each app's Info.plist via the $(ENCATCH_DEV_*) build settings referenced in
# project.yml — absent file means empty values and a blank Setup screen, same as before.
DEV_DEFAULT_API_KEY=""
DEV_DEFAULT_FORM_ID=""
if [ -f dev-tester-defaults.properties ]; then
    DEV_DEFAULT_API_KEY="$(grep '^encatch.dev.apiKey=' dev-tester-defaults.properties | cut -d= -f2- || true)"
    DEV_DEFAULT_FORM_ID="$(grep '^encatch.dev.formId=' dev-tester-defaults.properties | cut -d= -f2- || true)"
    echo "Dev defaults: baked in from dev-tester-defaults.properties"
fi
echo

echo "--- Building iOS XCFrameworks ---"
./gradlew \
    :integrations:encatch-kmp-tester:assembleEncatchKmpTesterDebugXCFramework \
    :integrations:encatch-compose-tester:assembleEncatchComposeTesterDebugXCFramework
echo

# dir : scheme : bundle id
APPS=(
    "encatch-ios-tester:EncatchIosTester:com.encatch.iostester"
    "encatch-kmp-tester-ios:EncatchKmpTesterApp:com.encatch.kmptester.ios"
    "encatch-compose-tester-ios:EncatchComposeTesterApp:com.encatch.composetester.ios"
)

for entry in "${APPS[@]}"; do
    IFS=':' read -r dir scheme bundle_id <<< "$entry"
    echo "=== $dir ==="

    (cd "integrations/$dir" && xcodegen generate >/dev/null)

    project_path="integrations/$dir"/*.xcodeproj
    derived_data="integrations/$dir/.xcbuild-device"

    echo "Building..."
    # Generic destination, not "id=$DEVICE_ID": xcodebuild's own destination-id namespace is
    # unrelated to devicectl's identifier (used below for install/launch) — passing the
    # devicectl UDID here fails with "Unable to find a device matching the provided
    # destination specifier".
    xcodebuild -project $project_path -scheme "$scheme" \
        -destination 'generic/platform=iOS' -derivedDataPath "$derived_data" \
        DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" \
        ENCATCH_DEV_API_KEY="$DEV_DEFAULT_API_KEY" ENCATCH_DEV_FORM_ID="$DEV_DEFAULT_FORM_ID" \
        -allowProvisioningUpdates build \
        2>&1 | grep -E '\*\* BUILD|error:' || true

    app_path="$derived_data/Build/Products/Debug-iphoneos/$scheme.app"
    if [ ! -d "$app_path" ]; then
        echo "error: build didn't produce $app_path — see xcodebuild output above" >&2
        exit 1
    fi

    echo "Installing..."
    xcrun devicectl device install app --device "$DEVICE_ID" "$app_path"

    echo "Launching..."
    xcrun devicectl device process launch --device "$DEVICE_ID" "$bundle_id" >/dev/null 2>&1 || true

    rm -rf "$derived_data" "integrations/$dir"/*.xcodeproj
    echo
done

echo "=== All three testers built, installed, and launched on $DEVICE_ID ==="
