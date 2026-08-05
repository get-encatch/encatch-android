#!/usr/bin/env bash
# Publish ios-native/ to the public SPM distribution mirror github.com/get-encatch/encatch-swift.
#
#   scripts/publish-encatch-swift.sh <version>        e.g. scripts/publish-encatch-swift.sh 0.1.0
#
# Copy-and-squash model: the mirror is write-only; every release replaces its working tree
# wholesale with ios-native/ (minus the exclusion list below) as ONE commit, tagged <version>.
# Development always happens in this monorepo — see ios-native/DEVELOPMENT.md.
set -euo pipefail

MIRROR_URL="https://github.com/get-encatch/encatch-swift.git"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO_ROOT/ios-native"

# Monorepo-only files that must never reach the public mirror.
EXCLUDES=(
  --exclude 'dist/'
  --exclude 'build-dist.sh'
  --exclude 'DEVELOPMENT.md'
  --exclude '.build/'
  --exclude '.swiftpm/'
  --exclude '.xcbuild-dev/'
  --exclude '.xcbuild-sim/'
  --exclude '.DS_Store'
)

VERSION="${1:-}"
[[ -n "$VERSION" ]] || { echo "usage: $0 <version>  (e.g. $0 0.1.0)" >&2; exit 1; }
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "error: '$VERSION' is not plain semver X.Y.Z" >&2; exit 1; }

# Preflight: monorepo tree must be clean so the release matches a real commit.
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain ios-native)" ]]; then
  echo "error: uncommitted changes under ios-native/ — commit or stash first" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
echo "==> Cloning mirror"
git clone --quiet "$MIRROR_URL" "$WORK/mirror"
cd "$WORK/mirror"

if git rev-parse -q --verify "refs/tags/$VERSION" >/dev/null; then
  echo "error: tag $VERSION already exists in the mirror" >&2
  exit 1
fi

echo "==> Staging ios-native/ -> mirror"
# Wipe everything except .git so deletions in the monorepo propagate.
find . -mindepth 1 -maxdepth 1 -not -name '.git' -exec rm -rf {} +
rsync -a "${EXCLUDES[@]}" "$SRC/" .

echo "==> Build gate (swift build && swift test in the staged mirror)"
swift build
swift test
# The build gate itself creates .build/ (full of machine-local paths) — remove build
# by-products BEFORE the leak gate and commit, so they can never ship.
rm -rf .build .swiftpm

echo "==> Leak gate"
if grep -rInE '/Users/|godwin|\.claude' --exclude-dir=.git .; then
  echo "error: leak gate hit — personal/internal references found above; aborting" >&2
  exit 1
fi

echo "==> Committing and tagging $VERSION"
git add -A
if git diff --cached --quiet; then
  echo "error: no changes vs mirror HEAD — nothing to release" >&2
  exit 1
fi
git -c commit.gpgsign=false commit -m "Release $VERSION"
git tag "$VERSION"
git push origin HEAD:main "$VERSION"

echo "==> Creating GitHub release"
gh release create "$VERSION" --repo get-encatch/encatch-swift \
  --title "$VERSION" --generate-notes || \
  echo "warning: gh release creation failed — tag is pushed, create the release manually" >&2

echo "==> Done: https://github.com/get-encatch/encatch-swift/releases/tag/$VERSION"
