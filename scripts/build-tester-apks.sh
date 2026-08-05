#!/usr/bin/env bash
# Builds release APKs of the three Android tester apps and collects them in dist/testers/
# (git-ignored) for handing to manual testers.
#
#   scripts/build-tester-apks.sh
#
# The testers are debug-keystore signed release builds (see each module's build.gradle.kts) —
# installable directly on any device, not Play Store artifacts. Each exercises a different SDK:
#   encatch-android-tester  -> com.encatch:android (native Views)
#   encatch-kmp-tester      -> com.encatch:kmp-sdk
#   encatch-compose-tester  -> com.encatch:compose-sdk
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO_ROOT/dist/testers"
TESTERS=(encatch-android-tester encatch-kmp-tester encatch-compose-tester)

echo "==> Building release APKs"
tasks=()
for t in "${TESTERS[@]}"; do tasks+=(":integrations:$t:assembleRelease"); done
(cd "$REPO_ROOT" && ./gradlew "${tasks[@]}" --console=plain -q)

echo "==> Collecting into dist/testers/"
mkdir -p "$OUT"
for t in "${TESTERS[@]}"; do
  cp "$REPO_ROOT/integrations/$t/build/outputs/apk/release/$t-release.apk" "$OUT/"
done

# Signature check: an unsigned APK silently fails to install on device — catch it here instead.
APKSIGNER=""
if [[ -n "${ANDROID_HOME:-}" || -d "$HOME/Library/Android/sdk" ]]; then
  SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  # newest installed build-tools wins
  APKSIGNER=$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
fi
if [[ -n "$APKSIGNER" ]]; then
  echo "==> Verifying signatures"
  for t in "${TESTERS[@]}"; do
    "$APKSIGNER" verify "$OUT/$t-release.apk" >/dev/null
    echo "    signed-ok: $t-release.apk"
  done
else
  echo "warning: apksigner not found (no Android SDK?) — skipping signature verification" >&2
fi

echo "==> Done:"
ls -lh "$OUT"/*.apk
