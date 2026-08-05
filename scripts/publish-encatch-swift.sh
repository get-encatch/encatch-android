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
# In CI (.github/workflows/swift-release.yml) the push authenticates via a fine-grained PAT
# scoped to the mirror repo; locally your ambient git credentials are used instead.
if [[ -n "${ENCATCH_SWIFT_PUSH_TOKEN:-}" ]]; then
  MIRROR_URL="https://x-access-token:${ENCATCH_SWIFT_PUSH_TOKEN}@github.com/get-encatch/encatch-swift.git"
fi
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
# Explicit identity: release commits carry the project identity, and CI runners have no
# git identity configured at all.
git -c commit.gpgsign=false -c user.name="Encatch" -c user.email="support@encatch.com" commit -m "Release $VERSION"
git tag "$VERSION"
git push origin HEAD:main "$VERSION"

# The GitHub release is created by the mirror's CI (release job in .github/workflows/ci.yml),
# and only after both build jobs pass on the CI toolchain — so a version that builds locally
# but fails on the oldest supported Swift never becomes a published release.
echo "==> Tag pushed. CI will create the release once both build jobs pass:"
echo "    https://github.com/get-encatch/encatch-swift/actions"
echo "==> Release (after CI): https://github.com/get-encatch/encatch-swift/releases/tag/$VERSION"
