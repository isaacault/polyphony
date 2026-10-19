#!/usr/bin/env bash
# Bump the app version, commit and tag it. Push the tag to trigger the Release workflow.
#   scripts/bump_version.sh 0.2.0            # stable
#   scripts/bump_version.sh 0.2.0-beta.1     # pre-release (tag contains a dash)
set -euo pipefail
NEW="${1:-}"
[[ "$NEW" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "usage: $0 <semver>[-prerelease]" >&2; exit 1; }
cd "$(dirname "$0")/.."
[ -z "$(git status --porcelain)" ] || { echo "working tree not clean" >&2; exit 1; }
CODE="$(grep '^VERSION_CODE=' version.properties | cut -d= -f2)"
NEXT_CODE=$((CODE + 1))
printf 'VERSION_NAME=%s\nVERSION_CODE=%s\n' "$NEW" "$NEXT_CODE" > version.properties
git add version.properties
git commit -q -m "Release v$NEW"
git tag -a "v$NEW" -m "Polyphony $NEW"
echo "Bumped to $NEW (versionCode $NEXT_CODE). Publish with:"
echo "  git push origin main v$NEW"
