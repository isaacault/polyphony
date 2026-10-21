#!/usr/bin/env bash
# Local CI: what .github/workflows/build.yml runs, in the polyphony-build Docker image.
#
#   scripts/ci.sh            build and test the checkout you are in (a worktree is fine)
#   scripts/ci.sh --status   the same on a clean, pushed HEAD, then post the result to GitHub as
#                            the `local-ci` commit status, which scripts/merge_when_green.sh gates on
#
# Exit code 0 = green. The full log is build/local-ci/<sha>.log. CI_TASKS overrides the Gradle
# tasks (default: test lintDebug :app:assembleDebug).
#
# Builds are serialised machine-wide with an exclusive flock on $CI_LOCK (shared with other Docker
# CI on this host) and wait for $CI_MIN_MEM_MB (default 1.5 GB) of available memory before Gradle
# starts. Gradle itself is capped at 3 GB of heap (gradle.properties); swap absorbs the rest.
set -euo pipefail

post=false
if [ "${1:-}" = "--status" ]; then post=true; shift; fi

root=$(git rev-parse --show-toplevel)
cd "$root"
sha=$(git rev-parse HEAD)
short=${sha:0:7}
tasks=${CI_TASKS:-"test lintDebug :app:assembleDebug"}
lock=${CI_LOCK:-/ssd/Personal/repositories/.locks/docker-ci.lock}
min_mem_mb=${CI_MIN_MEM_MB:-1536}
context=local-ci

if $post; then
  if [ -n "$(git status --porcelain)" ]; then
    echo "ci.sh --status: the working tree has changes; commit them so the status matches what ran" >&2
    exit 2
  fi
  if ! gh api "repos/{owner}/{repo}/commits/$sha" --silent 2>/dev/null; then
    echo "ci.sh --status: $short is not on GitHub; push it first" >&2
    exit 2
  fi
fi

status() { # state description
  $post || return 0
  gh api "repos/{owner}/{repo}/statuses/$sha" -f state="$1" -f context="$context" -f description="$2" --silent \
    || echo "ci.sh: could not post the $1 status" >&2
}

# The image is tagged by the Dockerfile's hash, so editing the Dockerfile rebuilds it once.
image="polyphony-build:$(sha256sum Dockerfile | cut -c1-12)"

mkdir -p "$(dirname "$lock")" build/local-ci
log="build/local-ci/$sha.log"
exec 9>"$lock"
if ! flock -n 9; then
  echo "ci.sh: waiting for another CI run on this machine ($lock)..."
  flock 9
fi

waited=0
while :; do
  avail=$(awk '/MemAvailable/ { print int($2 / 1024) }' /proc/meminfo)
  [ "$avail" -ge "$min_mem_mb" ] && break
  [ "$waited" -ge 1800 ] && { echo "ci.sh: only ${avail} MB free after 30 min; giving up" >&2; exit 3; }
  [ "$waited" -eq 0 ] && echo "ci.sh: ${avail} MB available, waiting for ${min_mem_mb} MB..."
  sleep 20; waited=$((waited + 20))
done

docker image inspect "$image" >/dev/null 2>&1 || docker build -t "$image" -t polyphony-build . >&2

# Google ships aapt2 for x86_64 only; the image wraps it in qemu on arm64 (see Dockerfile).
props=""
[ "$(uname -m)" = "aarch64" ] && props="-Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2"

# A worktree's .git points into the main repository, so mount that too, at the same path.
common=$(git rev-parse --path-format=absolute --git-common-dir)

status pending "Running $tasks"
echo "ci.sh: $short: ./gradlew $tasks (log: $log)"
start=$(date +%s)
set +e
docker run --rm \
  -v "$root:$root" -v "$common:$common" -w "$root" \
  -v polyphony-gradle:/root/.gradle \
  "$image" sh -c "./gradlew --no-daemon --console=plain $props $tasks; rc=\$?; find . -user 0 -exec chown $(id -u):$(id -g) {} + 2>/dev/null; exit \$rc" \
  > "$log" 2>&1
rc=$?
set -e
mins=$(( ($(date +%s) - start + 59) / 60 ))

if [ "$rc" -eq 0 ]; then
  echo "ci.sh: $short green in ${mins} min"
  status success "test, lint and assemble passed in ${mins} min"
else
  grep -E "^e: |FAILED|What went wrong|Lint found|There were failing tests" "$log" | head -20 >&2 || true
  echo "ci.sh: $short FAILED (exit $rc) after ${mins} min; see $log" >&2
  status failure "Failed after ${mins} min; see build/local-ci/$short… on the CI host"
fi
exit "$rc"
