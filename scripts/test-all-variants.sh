#!/bin/bash
# One-command automated test suite for all 5 SDK integration variants, against a mocked backend,
# with real emulator/simulator screenshots. Mac-only (iOS Simulator requires it).
#
# Usage: ./scripts/test-all-variants.sh [android-serial] [ios-udid]
#   android-serial  defaults to the first device from `adb devices`
#   ios-udid        defaults to the first booted simulator
#
# Requires: an already-running/booted Android emulator, an already-booted iOS Simulator, and
# xcodegen (`brew install xcodegen`). Starts :mock-server itself if it isn't already running.
#
# Variants 1/2/4(Android)/5(Android) are driven with real UI Automator flows via instrumentation
# tests (init -> show modal -> show inline, screenshotted at each step). Variant 3 (iOS native
# Swift) is driven with a real XCUITest flow, same shape, screenshots attach to the .xcresult.
# Variants 4/5's iOS sides don't have XCUITest targets yet — they get a build + launch +
# single-screenshot smoke check, not a full driven flow.
set -uo pipefail
cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

ANDROID_SERIAL="${1:-}"
IOS_UDID="${2:-}"

MOCK_PORT=8089
MOCK_URL="http://localhost:${MOCK_PORT}/s/react-native-sdk-form"
ANDROID_MOCK_BASE_URL="http://10.0.2.2:${MOCK_PORT}"
IOS_MOCK_BASE_URL="http://127.0.0.1:${MOCK_PORT}"

OUTPUT_DIR="$REPO_ROOT/build/variant-screenshots"
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

RESULTS=()
record() { RESULTS+=("$1|$2|$3"); }

echo "=== Encatch SDK: all-variants test run ==="
echo "Output: $OUTPUT_DIR"
echo

# ---------------------------------------------------------------------------
# 0. Preflight
# ---------------------------------------------------------------------------
if ! command -v xcodegen >/dev/null 2>&1; then
    echo "xcodegen not found — install via 'brew install xcodegen'" >&2
    exit 1
fi

if [ -z "$ANDROID_SERIAL" ]; then
    ANDROID_SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [ -z "$ANDROID_SERIAL" ]; then
    echo "No Android emulator/device found — boot one first (\`emulator -avd <name>\`)." >&2
    exit 1
fi
echo "Android device: $ANDROID_SERIAL"

if [ -z "$IOS_UDID" ]; then
    IOS_UDID="$(xcrun simctl list devices booted -j | python3 -c 'import json,sys; d=json.load(sys.stdin)["devices"]; ids=[dev["udid"] for devs in d.values() for dev in devs]; print(ids[0] if ids else "")')"
fi
if [ -z "$IOS_UDID" ]; then
    echo "No booted iOS Simulator found — boot one first (\`xcrun simctl boot <name>\`)." >&2
    exit 1
fi
echo "iOS simulator: $IOS_UDID"
echo

# ---------------------------------------------------------------------------
# 1. Mock server
# ---------------------------------------------------------------------------
MOCK_SERVER_STARTED_BY_US=0
if curl -s -o /dev/null -w "%{http_code}" "$MOCK_URL" --max-time 2 | grep -q "200"; then
    echo ":mock-server already running on :$MOCK_PORT — reusing it."
else
    echo "Starting :mock-server..."
    nohup ./gradlew :mock-server:run > "$OUTPUT_DIR/mock-server.log" 2>&1 &
    MOCK_SERVER_PID=$!
    MOCK_SERVER_STARTED_BY_US=1
    ready=0
    for _ in $(seq 1 60); do
        if curl -s -o /dev/null -w "%{http_code}" "$MOCK_URL" --max-time 1 | grep -q "200"; then
            ready=1
            break
        fi
        sleep 2
    done
    if [ "$ready" -ne 1 ]; then
        echo "mock-server didn't become ready in time — see $OUTPUT_DIR/mock-server.log" >&2
        exit 1
    fi
    echo ":mock-server ready."
fi
echo

cleanup() {
    if [ "$MOCK_SERVER_STARTED_BY_US" = "1" ] && [ -n "${MOCK_SERVER_PID:-}" ]; then
        echo "Stopping :mock-server (pid $MOCK_SERVER_PID)..."
        kill "$MOCK_SERVER_PID" >/dev/null 2>&1 || true
        pkill -f "MockServerMainKt" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# 2. Android — build once, drive each variant's instrumentation test
# ---------------------------------------------------------------------------
echo "--- Building Android sample-app + androidTest APKs ---"
if ./gradlew :examples:sample-app:assembleDebug :examples:sample-app:assembleDebugAndroidTest \
    -PmockServerBaseUrl="$ANDROID_MOCK_BASE_URL" > "$OUTPUT_DIR/android-build.log" 2>&1; then
    echo "Android build OK."
else
    echo "Android build FAILED — see $OUTPUT_DIR/android-build.log" >&2
    record "1-android-views" "FAIL" "build failed"
    record "2-android-compose" "FAIL" "build failed"
    record "4-android-compose-multiplatform" "FAIL" "build failed"
    record "5-android-kmp-sample" "FAIL" "build failed"
    ANDROID_BUILD_FAILED=1
fi
echo

run_android_test() {
    local variant_name="$1"
    local test_class="$2"
    local screenshot_subdir="$3"

    if [ "${ANDROID_BUILD_FAILED:-0}" = "1" ]; then
        return
    fi

    echo "--- $variant_name: $test_class ---"
    adb -s "$ANDROID_SERIAL" uninstall com.encatch.sampleapp >/dev/null 2>&1 || true
    adb -s "$ANDROID_SERIAL" uninstall com.encatch.sampleapp.test >/dev/null 2>&1 || true
    adb -s "$ANDROID_SERIAL" install -r examples/sample-app/build/outputs/apk/debug/sample-app-debug.apk >/dev/null
    adb -s "$ANDROID_SERIAL" install -r examples/sample-app/build/outputs/apk/androidTest/debug/sample-app-debug-androidTest.apk >/dev/null

    local instrument_log="$OUTPUT_DIR/${variant_name}-instrument.log"
    if adb -s "$ANDROID_SERIAL" shell am instrument -w -e class "com.encatch.sampleapp.$test_class" \
        com.encatch.sampleapp.test/androidx.test.runner.AndroidJUnitRunner > "$instrument_log" 2>&1 \
        && grep -q "OK (1 test)" "$instrument_log"; then
        mkdir -p "$OUTPUT_DIR/$variant_name"
        adb -s "$ANDROID_SERIAL" pull "/sdcard/Android/data/com.encatch.sampleapp/files/$screenshot_subdir" \
            "$OUTPUT_DIR/$variant_name" >/dev/null 2>&1
        record "$variant_name" "PASS" "screenshots in $variant_name/"
        echo "PASS"
    else
        record "$variant_name" "FAIL" "see ${variant_name}-instrument.log"
        echo "FAIL — see $instrument_log"
    fi

    adb -s "$ANDROID_SERIAL" uninstall com.encatch.sampleapp >/dev/null 2>&1 || true
    adb -s "$ANDROID_SERIAL" uninstall com.encatch.sampleapp.test >/dev/null 2>&1 || true
    echo
}

run_android_test "1-android-views" "ScreenshotFlowTest" "screenshots"
run_android_test "2-android-compose" "ComposeScreenshotFlowTest" "screenshots-compose"
run_android_test "4-android-compose-multiplatform" "ComposeMultiplatformScreenshotFlowTest" "screenshots-compose-multiplatform"
run_android_test "5-android-kmp-sample" "KmpSampleScreenshotFlowTest" "screenshots-kmp-sample"

# ---------------------------------------------------------------------------
# 3. iOS variant 3 — native Swift, full XCUITest flow
# ---------------------------------------------------------------------------
echo "--- 3-ios-native-swift: XCUITest ---"
if MOCK_SERVER_BASE_URL="$IOS_MOCK_BASE_URL" ./examples/ios-sample/test.sh "$IOS_UDID" \
    > "$OUTPUT_DIR/3-ios-native-swift-test.log" 2>&1; then
    record "3-ios-native-swift" "PASS" "screenshots in .xcresult — see log for path"
    echo "PASS"
else
    record "3-ios-native-swift" "FAIL" "see 3-ios-native-swift-test.log"
    echo "FAIL — see $OUTPUT_DIR/3-ios-native-swift-test.log"
fi
echo

# ---------------------------------------------------------------------------
# 4/5. iOS variants 4 & 5 — no XCUITest target yet: build + launch + smoke screenshot.
# Both apps cinterop against swift/'s @objc facade (EncatchBridge.swift) rather than linking
# any Kotlin/Native binary. This is
# the fix for the old architecture, where two independently-linked Kotlin/Native frameworks that
# each embedded :core produced two disconnected `Encatch` singletons in one process.
# ---------------------------------------------------------------------------
run_ios_smoke_test() {
    local variant_name="$1"
    local project_dir="$2"
    local scheme="$3"
    local bundle_id="$4"

    echo "--- $variant_name: build + launch smoke check ---"
    local build_log="$OUTPUT_DIR/${variant_name}-build.log"
    if ! MOCK_SERVER_BASE_URL="$IOS_MOCK_BASE_URL" "./$project_dir/build.sh" "$IOS_UDID" > "$build_log" 2>&1; then
        record "$variant_name" "FAIL" "build failed, see ${variant_name}-build.log"
        echo "FAIL — build failed, see $build_log"
        return
    fi

    local app_path
    app_path="$(xcodebuild -project "$project_dir"/*.xcodeproj -scheme "$scheme" \
        -destination "id=$IOS_UDID" -showBuildSettings 2>/dev/null \
        | awk -F'= ' '/ TARGET_BUILD_DIR /{print $2; exit}')/${scheme}.app"

    if [ ! -d "$app_path" ]; then
        record "$variant_name" "FAIL" "couldn't locate built .app"
        echo "FAIL — couldn't locate built .app at $app_path"
        return
    fi

    xcrun simctl uninstall "$IOS_UDID" "$bundle_id" >/dev/null 2>&1 || true
    xcrun simctl install "$IOS_UDID" "$app_path"
    xcrun simctl launch "$IOS_UDID" "$bundle_id" >/dev/null
    sleep 2
    mkdir -p "$OUTPUT_DIR/$variant_name"
    xcrun simctl io "$IOS_UDID" screenshot "$OUTPUT_DIR/$variant_name/00-launch.png" >/dev/null 2>&1
    xcrun simctl terminate "$IOS_UDID" "$bundle_id" >/dev/null 2>&1 || true

    record "$variant_name" "PASS (smoke only)" "launched + screenshot in $variant_name/ — no driven flow yet"
    echo "PASS (smoke only) — launched and captured a screenshot, no driven flow yet"
    echo
}

run_ios_smoke_test "4-ios-compose-multiplatform" "examples/ios-compose-sample" "EncatchComposeSampleApp" "com.encatch.composesample"
run_ios_smoke_test "5-ios-kmp-sample" "examples/ios-kmp-sample" "EncatchKmpSampleApp" "com.encatch.kmpsample"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo "=== Summary ==="
printf "%-32s %-18s %s\n" "VARIANT" "RESULT" "DETAIL"
FAILED=0
for entry in "${RESULTS[@]}"; do
    IFS='|' read -r name status detail <<< "$entry"
    printf "%-32s %-18s %s\n" "$name" "$status" "$detail"
    if [ "$status" = "FAIL" ]; then
        FAILED=1
    fi
done
echo
echo "Full output: $OUTPUT_DIR"

exit $FAILED
