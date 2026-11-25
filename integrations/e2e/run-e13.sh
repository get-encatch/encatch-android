#!/usr/bin/env bash
# E13 on a device (RN SDK 1.5.0 doc, item 4): sign in, restart the app without clearing its data,
# check what the second launch sends. Runs E13-off (Setup's "Keep the user after an app restart"
# off) and E13-default against this repo's mock-server, through the tester app's "Mock" environment.
#
#   integrations/e2e/run-e13.sh android <adb serial>      # tester installed, mock-server on 8089
#   integrations/e2e/run-e13.sh ios <simulator udid>
#
# Start the mock-server first: ./gradlew :mock-server:run (port 8089; the emulator reaches it at
# 10.0.2.2, the Simulator at 127.0.0.1). Needs maestro and python3.
set -euo pipefail

platform="${1:?android or ios}"
device="${2:?the device: an adb serial or a simulator udid}"
mock="${MOCK_URL:-http://127.0.0.1:8089}"
here="$(cd "$(dirname "$0")" && pwd)"
case "$platform" in
  android) app_id=com.encatch.androidtester ;;
  ios) app_id=com.encatch.iostester ;;
  *) echo "run-e13: platform is android or ios" >&2; exit 1 ;;
esac

failed=0
for persist in false true; do
  name="E13-$([[ $persist == false ]] && echo off || echo default) ($platform)"
  curl -sf -X POST "$mock/__mock/reset" >/dev/null || { echo "run-e13: no mock-server at $mock" >&2; exit 1; }
  if ! maestro --device "$device" test -e APP_ID="$app_id" -e PERSIST="$persist" "$here/e13-restart.yaml" >"/tmp/run-e13-$platform-$persist.log" 2>&1; then
    echo "FAIL $name: the flow failed (log: /tmp/run-e13-$platform-$persist.log)"
    tail -15 "/tmp/run-e13-$platform-$persist.log"
    failed=1
    continue
  fi
  sleep 5 # the second launch's own requests (its screen) arrive
  curl -sf "$mock/__mock/requests" | python3 -I -c '
import json, sys
persist = sys.argv[1] == "true"
calls = [c for c in json.load(sys.stdin)]
sessions = []
for c in calls:
    sid = c["headers"].get("X-Session-Id")
    if sid not in sessions:
        sessions.append(sid)
problems = []
if len(sessions) != 2:
    problems.append(f"expected requests from 2 launches, got {len(sessions)}")
identifies = [c for c in calls if c["endpoint"] == "identify-user"]
if len(identifies) != 1:
    problems.append(f"expected 1 identify-user, got {len(identifies)}")
first = [c for c in calls if c["headers"].get("X-Session-Id") == sessions[0]] if sessions else []
second = [c for c in calls if len(sessions) > 1 and c["headers"].get("X-Session-Id") == sessions[1]]
if not any(c["headers"].get("X-User-Id") == "uid-e2e-alice" for c in first):
    problems.append("no identified request in the first launch")
if not second:
    problems.append("no request in the second launch")
for c in second:
    endpoint, name, uid = c["endpoint"], c["headers"].get("X-User-Name"), c["headers"].get("X-User-Id")
    if persist and (name, uid) != ("e2e-alice", "uid-e2e-alice"):
        problems.append(f"second launch {endpoint} not sent as the user: {name} / {uid}")
    if not persist and (name or uid):
        problems.append(f"second launch {endpoint} carries identity: {name} / {uid}")
summary = ", ".join(c["endpoint"] for c in second) or "none"
print(("PASS " if not problems else "FAIL ") + sys.argv[2] + f": {len(calls)} requests, 2nd launch: {summary}")
for p in problems:
    print("  - " + p)
sys.exit(1 if problems else 0)
' "$persist" "$name" || failed=1
done
exit $failed
