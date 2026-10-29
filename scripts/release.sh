#!/usr/bin/env bash
# Build, sign, verify and publish a release from this machine.
#
#   scripts/release.sh 0.1.0-alpha.25            # release: bump, build, sign, verify, push, publish
#   scripts/release.sh 0.1.0-alpha.25 --dry-run  # the same build and checks, nothing committed or pushed
#
# Run it on a clean `main` that matches GitHub and has a green `local-ci` status (it runs the full
# local CI first if there is none). No passwords are typed: signing reads
# $POLYPHONY_SIGNING_DIR/keystore.properties (default ~/.config/polyphony, mode 600; keys
# storePassword, keyAlias, keyPassword) and the release.jks next to it, inside the build container.
#
# Steps: bump version.properties, commit "Release vX" and tag vX locally; build the signed APK and
# bundle in the polyphony-build image (under the shared CI lock); check the signer is the Polyphony
# release key and the APK carries the new version; then push main and the tag and create the
# GitHub release with the APK, AAB, R8 mapping and a .sha256 list. If anything fails before the
# push, the local commit and tag are undone. Tags with a dash are published as pre-releases.
set -euo pipefail

# The Polyphony release certificate. Updates only install over an existing app signed with the
# same key, so a build signed with anything else must never ship.
EXPECTED_CERT=169d5d823dac76da903956fa0cb0c0eb7feac3fdcd865b115ad3a3595b8f6dca

version=""
dry=false
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry=true ;;
    -*) echo "release.sh: unknown option $arg" >&2; exit 2 ;;
    *) version=$arg ;;
  esac
done
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "usage: $0 <semver>[-prerelease] [--dry-run]" >&2; exit 2; }
tag="v$version"

cd "$(git rev-parse --show-toplevel)"
repo=$PWD
signing=${POLYPHONY_SIGNING_DIR:-$HOME/.config/polyphony}
lock=${CI_LOCK:-/ssd/Personal/repositories/.locks/docker-ci.lock}
say() { echo "release.sh: $*"; }
die() { echo "release.sh: $*" >&2; exit 1; }

# ---- preflight -------------------------------------------------------------------------------
[ -f "$signing/release.jks" ] && [ -f "$signing/keystore.properties" ] || die "no $signing/release.jks + keystore.properties"
for key in storePassword keyAlias keyPassword; do
  grep -q "^$key=." "$signing/keystore.properties" || die "$signing/keystore.properties has no $key"
done
[ "$(git branch --show-current)" = main ] || die "run it on main"
[ -z "$(git status --porcelain)" ] || die "the working tree has changes"
git fetch -q origin main --tags
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || die "main is not in sync with origin/main (pull or push first)"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "$tag already exists"
gh auth status >/dev/null 2>&1 || die "gh is not signed in"

head=$(git rev-parse HEAD)
local_ci() { gh api "repos/{owner}/{repo}/commits/$1/status" -q '.statuses[] | select(.context == "local-ci") | .state' 2>/dev/null | head -1; }
# A squash merge is a new commit with no status of its own; the PR head it came from counts when
# its tree is identical, i.e. exactly the code that passed.
green_for_head() {
  [ "$(local_ci "$head")" = success ] && return 0
  local pr_head
  pr_head=$(gh api "repos/{owner}/{repo}/commits/$head/pulls" -q '.[0].head.sha' 2>/dev/null) || return 1
  [ -n "$pr_head" ] && git fetch -q origin "$pr_head" 2>/dev/null \
    && [ "$(git rev-parse "$pr_head^{tree}")" = "$(git rev-parse "HEAD^{tree}")" ] \
    && [ "$(local_ci "$pr_head")" = success ]
}
if $dry; then
  say "dry run: not checking local-ci"
elif green_for_head; then
  say "local-ci is green for ${head:0:7}"
else
  say "no green local-ci for ${head:0:7}; running the full local CI first"
  scripts/ci.sh --status || die "local CI failed; not releasing"
fi

code=$(( $(grep '^VERSION_CODE=' version.properties | cut -d= -f2) + 1 ))
say "$tag (versionCode $code) from ${head:0:7}$($dry && echo ', dry run')"

# ---- release commit and tag (local until the build is verified) --------------------------------
wt=$(mktemp -d "${TMPDIR:-/tmp}/polyphony-release.XXXXXX")
undo() {
  git worktree remove --force "$wt" 2>/dev/null || rm -rf "$wt"
  if ! $dry && [ -z "${published:-}" ]; then
    git tag -d "$tag" >/dev/null 2>&1 || true
    [ "$(git rev-parse HEAD)" != "$head" ] && git reset -q --hard "$head"
    say "undid the local release commit and tag"
  fi
}
trap undo EXIT

if $dry; then
  git worktree add -q --detach "$wt" HEAD
  printf 'VERSION_NAME=%s\nVERSION_CODE=%s\n' "$version" "$code" > "$wt/version.properties"
else
  printf 'VERSION_NAME=%s\nVERSION_CODE=%s\n' "$version" "$code" > version.properties
  git commit -q -m "Release $tag" version.properties
  git tag -a "$tag" -m "Polyphony $version"
  git worktree add -q --detach "$wt" "$tag"
fi

# ---- signed build ----------------------------------------------------------------------------
image="polyphony-build:$(sha256sum Dockerfile | cut -c1-12)"
docker image inspect "$image" >/dev/null 2>&1 || docker build -t "$image" -t polyphony-build . >&2
props="-Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx${CI_GRADLE_HEAP:-3g} -Dfile.encoding=UTF-8 -XX:+UseParallelGC'"
[ "$(uname -m)" = aarch64 ] && props="$props -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2"
common=$(git rev-parse --path-format=absolute --git-common-dir)
mkdir -p "$(dirname "$lock")"
exec 9>"$lock"
flock -n 9 || { say "waiting for another CI run on this machine ($lock)..."; flock 9; }

log="$repo/build/release-$version.log"
mkdir -p "$repo/build"
say "building signed APK and bundle (log: build/release-$version.log)"
# Passwords are read from the mounted file inside the container, so they never appear on a
# command line or in the log.
set +e
docker run --rm \
  -v "$wt:$wt" -v "$common:$common" -w "$wt" \
  -v "$signing:/signing:ro" \
  -v polyphony-gradle:/root/.gradle \
  "$image" sh -c '
    p() { grep "^$1=" /signing/keystore.properties | cut -d= -f2-; }
    export POLYPHONY_KEYSTORE_PATH=/signing/release.jks
    export POLYPHONY_KEYSTORE_PASSWORD="$(p storePassword)" POLYPHONY_KEY_ALIAS="$(p keyAlias)" POLYPHONY_KEY_PASSWORD="$(p keyPassword)"
    ./gradlew --no-daemon --console=plain '"$props"' :android:app:assembleRelease :android:app:bundleRelease
    rc=$?; find . -user 0 -exec chown '"$(id -u):$(id -g)"' {} + 2>/dev/null; exit $rc' \
  > "$log" 2>&1
rc=$?
set -e
exec 9>&-
[ "$rc" -eq 0 ] || { grep -E "^e: |FAILED|What went wrong" "$log" | head -10 >&2; die "build failed; see $log"; }

# ---- verify ----------------------------------------------------------------------------------
apk="$wt/android/app/build/outputs/apk/release/app-release.apk"
aab="$wt/android/app/build/outputs/bundle/release/app-release.aab"
mapping="$wt/android/app/build/outputs/mapping/release/mapping.txt"
for f in "$apk" "$aab" "$mapping"; do [ -f "$f" ] || die "missing ${f#"$wt"/}"; done
aapt2=/opt/android-sdk/build-tools/37.0.0/aapt2
[ "$(uname -m)" = aarch64 ] && aapt2=/opt/aapt2/aapt2
checks=$(docker run --rm -v "$wt:$wt" "$image" sh -c "
  /opt/android-sdk/build-tools/37.0.0/apksigner verify --print-certs '$apk' | sed -n 's/.*certificate SHA-256 digest: //p' | head -1
  $aapt2 dump badging '$apk' | head -1")
cert=$(sed -n 1p <<<"$checks")
badging=$(sed -n 2p <<<"$checks")
[ "$cert" = "$EXPECTED_CERT" ] || die "signed with the wrong key (SHA-256 ${cert:-none}); expected the Polyphony release key"
grep -q "versionCode='$code'" <<<"$badging" && grep -q "versionName='$version'" <<<"$badging" \
  || die "the APK does not carry $version ($code): $badging"
say "signed with the Polyphony release key; APK is $version ($code)"

dist="$repo/build/dist/$version"
rm -rf "$dist" && mkdir -p "$dist"
cp "$apk" "$dist/polyphony-$version.apk"
cp "$aab" "$dist/polyphony-$version.aab"
cp "$mapping" "$dist/polyphony-$version-mapping.txt"
(cd "$dist" && sha256sum polyphony-* > "polyphony-$version.sha256")
cat "$dist/polyphony-$version.sha256"

if $dry; then
  say "dry run OK; artifacts in build/dist/$version (nothing committed or pushed)"
  exit 0
fi

# ---- publish ---------------------------------------------------------------------------------
# The release commit only changes version.properties and CI already passed on its parent, so the
# pre-push quick check is skipped.
CI_SKIP=1 git push -q --atomic origin main "$tag"
published=1
pre=""; [[ "$version" == *-* ]] && pre="--prerelease"
create=(gh release create "$tag" "$dist"/* --title "Polyphony $version" --generate-notes --verify-tag $pre)
"${create[@]}" || die "pushed $tag, but creating the GitHub release failed; retry with: ${create[*]}"
say "published $tag: $(gh release view "$tag" --json url -q .url)"
