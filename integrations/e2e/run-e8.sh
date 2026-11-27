#!/usr/bin/env bash
# E8 on a device (React Native handover HANDOVER-ON-SIGNATURE-EXPIRED.md §9, onSignatureExpired): sign
# in, identify with an expired signature, let the tester's onSignatureExpired identify again with a
# fresh one, then update properties. Checks what the mock-server recorded.
#
#   integrations/e2e/run-e8.sh android <adb serial>      # tester installed, mock-server on 8089
#   integrations/e2e/run-e8.sh ios <simulator udid>
#
# Start the mock-server first: ./gradlew :mock-server:run (port 8089; the emulator reaches it at
# 10.0.2.2, the Simulator at 127.0.0.1). Needs maestro and python3.
set -euo pipefail

platform="${1:?android or ios}"
device="${2:?the device: an adb serial or a simulator udid}"
mock="${MOCK_URL:-http://127.0.0.1:8089}"
here="$(cd "$(dirname "$0")" && pwd)"
log="/tmp/run-e8-$platform.log"
case "$platform" in
  android) app_id=com.encatch.androidtester ;;
  ios) app_id=com.encatch.iostester ;;
  *) echo "run-e8: platform is android or ios" >&2; exit 1 ;;
esac

stop_app() {
  if [[ $platform == android ]]; then adb -s "$device" shell am force-stop "$app_id" || true
  else xcrun simctl terminate "$device" "$app_id" 2>/dev/null || true; fi
}
stop_app
curl -sf -X POST "$mock/__mock/reset" >/dev/null || { echo "run-e8: no mock-server at $mock" >&2; exit 1; }

run_flow() {
  if ! maestro --device "$device" test -e APP_ID="$app_id" "$here/$1" >>"$log" 2>&1; then
    echo "FAIL E8 ($platform): the flow $1 failed (log: $log)"
    tail -15 "$log"
    exit 1
  fi
}
: >"$log"
run_flow e8-signature-expired.yaml
# the sync point: the tester's onSignatureExpired identified again with a fresh signature (a 200)
for _ in $(seq 1 40); do
  if curl -sf "$mock/__mock/requests" | python3 -I -c '
import json, sys
calls = json.load(sys.stdin)
ok = any(c["endpoint"] == "identify-user" and c["headers"].get("X-User-Name") == "e2e-signed"
         and c["headers"].get("X-User-Signature") and c.get("status") == 200 for c in calls)
sys.exit(0 if ok else 1)'; then break; fi
  sleep 0.5
done
run_flow e8-then-update.yaml
sleep 2
stop_app

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -sf "$mock/__mock/requests" >"$work/requests.json" || { echo "FAIL E8 ($platform): can't read $mock/__mock/requests" >&2; exit 1; }
curl -sf "$mock/__mock/hooks" >"$work/hooks.json" || { echo "FAIL E8 ($platform): can't read $mock/__mock/hooks" >&2; exit 1; }
python3 -I -c '
import json, sys
calls = json.load(open(sys.argv[1])); hooks = json.load(open(sys.argv[2]))
user = "e2e-signed"
problems = []

def times_ok(c):
    h = c["headers"]
    return h.get("X-User-Signature-Time") and h.get("X-User-Signature-Time") == h.get("X-User-Session-Time")

ids = [c for c in calls if c["endpoint"] == "identify-user" and c["headers"].get("X-User-Name") == user]
def kind(c):
    signed = "signed" if c["headers"].get("X-User-Signature") else "unsigned"
    what = "update" if (c.get("body") or {}).get("userAttributes") else "identify"
    status = c.get("status")
    return f"{signed} {what} {status}"
kinds = [kind(c) for c in ids]
expected = ["unsigned identify 200", "signed identify 401", "signed identify 200", "signed update 200"]
if kinds != expected:
    problems.append(f"identify-user for {user}: expected {expected}, got {kinds}")
else:
    sign_in, expired, fresh, update = ids
    if (expired.get("response") or {}).get("reason") != "user_signature_expired":
        got = expired.get("response")
        problems.append(f"the 401 should say user_signature_expired, got {got}")
    for name, c in (("expired", expired), ("fresh", fresh), ("update", update)):
        if not times_ok(c):
            problems.append(f"the {name} identify-user should carry X-User-Signature-Time and X-User-Session-Time, equal")
    if fresh["headers"]["X-User-Signature"] == expired["headers"]["X-User-Signature"] or \
            fresh["headers"].get("X-User-Signature-Time") == expired["headers"].get("X-User-Signature-Time"):
        problems.append("the fresh identify carried the expired signature or time again")
    if update["headers"].get("X-User-Signature") != fresh["headers"]["X-User-Signature"]:
        problems.append("the property update did not carry the fresh signature")
    later = [c for c in calls if c["atMs"] > fresh["atMs"] and c["endpoint"] != "identify-user"
             and c["headers"].get("X-User-Name") == user]
    unsigned = [c["endpoint"] for c in later if c["headers"].get("X-User-Signature") != fresh["headers"]["X-User-Signature"]]
    if unsigned:
        problems.append(f"later requests without the fresh signature: {unsigned}")
reported = [h for h in hooks if h.get("hook") == "onSignatureExpired"]
if [h.get("userName") for h in reported] != [user]:
    problems.append(f"expected one onSignatureExpired report for {user}, got {reported}")
other401 = [(c["endpoint"], c["status"]) for c in calls if c.get("status") == 401]
if len(other401) != 1:
    problems.append(f"expected exactly one 401, got {other401}")
if problems:
    print("FAIL E8: " + "; ".join(problems))
    sys.exit(1)
print(f"PASS E8: identify-user {kinds}, the 401 said user_signature_expired, one onSignatureExpired report, the update carried the fresh signature")
' "$work/requests.json" "$work/hooks.json" || { echo "(flow log: $log)"; exit 1; }
