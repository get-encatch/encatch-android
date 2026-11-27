#!/usr/bin/env bash
# E14 on a device (RN SDK 1.5.0 handover: identifyUser dedupe and setUserProperties): sign in,
# identify again, identify with traits twice, set properties twice, identify with the traits again,
# then restart (data kept) and identify twice. Checks what the mock-server recorded, and that the
# skipped calls reached the SDK (its debug log says "skipped").
#
#   integrations/e2e/run-e14.sh android <adb serial>      # tester installed, mock-server on 8089
#   integrations/e2e/run-e14.sh ios <simulator udid>
#
# Start the mock-server first: ./gradlew :mock-server:run (port 8089; the emulator reaches it at
# 10.0.2.2, the Simulator at 127.0.0.1). Needs maestro and python3.
set -euo pipefail

platform="${1:?android or ios}"
device="${2:?the device: an adb serial or a simulator udid}"
mock="${MOCK_URL:-http://127.0.0.1:8089}"
here="$(cd "$(dirname "$0")" && pwd)"
skipped="skipped: same user and traits"
log="/tmp/run-e14-$platform.log"
sdk_log="/tmp/run-e14-$platform-sdk.log"
case "$platform" in
  android) app_id=com.encatch.androidtester ;;
  ios) app_id=com.encatch.iostester ;;
  *) echo "run-e14: platform is android or ios" >&2; exit 1 ;;
esac

stop_app() {
  if [[ $platform == android ]]; then adb -s "$device" shell am force-stop "$app_id" || true
  else xcrun simctl terminate "$device" "$app_id" 2>/dev/null || true; fi
}
# a tester still running from before would add its own requests (its ping); one left running on
# another device against the same mock-server too: stop that one yourself
stop_app
curl -sf -X POST "$mock/__mock/reset" >/dev/null || { echo "run-e14: no mock-server at $mock" >&2; exit 1; }

# the SDK's own log, to count the skipped identifies per app run
if [[ $platform == android ]]; then
  adb -s "$device" logcat -c
else
  xcrun simctl spawn "$device" log stream --level debug --style compact \
    --predicate "eventMessage CONTAINS \"$skipped\"" >"$sdk_log" 2>&1 &
  log_pid=$!
  trap 'kill $log_pid 2>/dev/null || true' EXIT
fi

if ! maestro --device "$device" test -e APP_ID="$app_id" "$here/e14-identify-dedupe.yaml" >"$log" 2>&1; then
  echo "FAIL E14 ($platform): the flow failed (log: $log)"
  tail -15 "$log"
  exit 1
fi
sleep 3
stop_app

if [[ $platform == android ]]; then
  # "<pid>" per skipped line, from logcat's threadtime format
  adb -s "$device" logcat -d -v threadtime | grep -F "$skipped" | awk '{print $3}' >"$sdk_log.pids" || true
else
  kill $log_pid 2>/dev/null || true
  sleep 1
  # the process id after the process name ("EncatchIosTester[1234:…]"), one line per skip
  # only the SDK's own lines (the log tool's header repeats the filter text)
  grep -F "[Encatch] identifyUser" "$sdk_log" | grep -F "$skipped" | sed -E 's/.*\[([0-9]+):.*/\1/' >"$sdk_log.pids" || true
fi

curl -sf "$mock/__mock/requests" | python3 -I -c '
import json, sys
calls = json.load(sys.stdin)
pids = [l.strip() for l in open(sys.argv[2]) if l.strip()]
sessions = []
for c in calls:
    sid = c["headers"].get("X-Session-Id")
    if sid not in sessions:
        sessions.append(sid)
problems = []
if len(sessions) != 2:
    problems.append(f"expected requests from 2 launches, got {len(sessions)}")

def kind(c):
    attrs = (c.get("body") or {}).get("userAttributes")
    if not attrs:
        return "plain"
    if "$increment" in attrs:
        return "props"
    if attrs.get("$set", {}).get("e2e_plan") == "free":
        return "free"
    return "other"

identifies = [c for c in calls if c["endpoint"] == "identify-user"]
order = []
for c in identifies:
    launch = sessions.index(c["headers"].get("X-Session-Id")) + 1 if c["headers"].get("X-Session-Id") in sessions else 0
    order.append(f"{kind(c)}@{launch}")
expected = ["plain@1", "free@1", "props@1", "props@1", "free@1", "plain@2"]
if order != expected:
    problems.append(f"identify-user order {order}, expected {expected}")
for c in identifies:
    if kind(c) == "props":
        body = c.get("body") or {}
        if body.get("userId") != "uid-e2e-alice":
            uid = body.get("userId")
            problems.append(f"a properties update without the user id: {uid}")
        if c["headers"].get("X-User-Name") != "e2e-alice":
            problems.append("a properties update without X-User-Name e2e-alice")
second = [c for c in calls if len(sessions) > 1 and c["headers"].get("X-Session-Id") == sessions[1]]
for c in second:
    if c["endpoint"] != "identify-user" and (c["headers"].get("X-User-Name"), c["headers"].get("X-User-Id")) != ("e2e-alice", "uid-e2e-alice"):
        endpoint = c["endpoint"]
        problems.append(f"second launch {endpoint} not sent as the user")
# the skips happened (else a tap that never reached the SDK would pass): 2 in the first run, 1 after
runs = []
for p in pids:
    if not runs or runs[-1][0] != p:
        runs.append([p, 0])
    runs[-1][1] += 1
counts = [n for _, n in runs]
if counts != [2, 1]:
    problems.append(f"skipped identifies per app run {counts}, expected [2, 1]")
listed = ", ".join(order)
print(("PASS " if not problems else "FAIL ") + sys.argv[1] + f": {len(calls)} requests; identify-user: {listed}; skipped per run: {counts}")
for p in problems:
    print("  - " + p)
sys.exit(1 if problems else 0)
' "E14 ($platform)" "$sdk_log.pids"
