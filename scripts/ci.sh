#!/usr/bin/env bash
# Local CI, in the polyphony-build Docker image.
#
#   scripts/ci.sh             full: what .github/workflows/build.yml runs (test, lintDebug,
#                             :app:assembleDebug), on the checkout you are in (a worktree is fine)
#   scripts/ci.sh --quick     compile every module and run the unit tests (no lint, no APK):
#                             the pre-push check (scripts/git-hooks/pre-push)
#   scripts/ci.sh --status    full, on a clean, pushed HEAD, then post the result to GitHub as the
#                             `local-ci` commit status, which scripts/merge_when_green.sh gates on
#   --no-wait                 if another CI run holds the machine lock, skip (exit 75) instead of
#                             queueing behind it
#
# Exit code 0 = green. The full log is build/local-ci/<sha>.log. CI_TASKS overrides the Gradle
# tasks.
#
# Builds are serialised machine-wide with an exclusive flock on $CI_LOCK (shared with other Docker
# CI on this host) and wait for $CI_MIN_MEM_MB (default 1.5 GB) of available memory before Gradle
# starts. The Kotlin compiler runs inside Gradle's JVM (one heap, $CI_GRADLE_HEAP, default 3 GB)
# instead of a second daemon with its own 3 GB, which keeps a run off swap on a small box.
set -euo pipefail

post=false
quick=false
wait_for_lock=true
for arg in "$@"; do
  case "$arg" in
    --status) post=true ;;
    --quick) quick=true ;;
    --no-wait) wait_for_lock=false ;;
    *) echo "ci.sh: unknown option $arg" >&2; exit 2 ;;
  esac
done
if $post && $quick; then
  echo "ci.sh: --status posts the merge gate, which needs the full run; drop --quick" >&2
  exit 2
fi

root=$(git rev-parse --show-toplevel)
cd "$root"
sha=$(git rev-parse HEAD)
short=${sha:0:7}
if $quick; then
  # core:domain is plain Kotlin (`test`); every other module is Android (`testDebugUnitTest`).
  # The app has unit tests, so this compiles it too.
  tasks=${CI_TASKS:-":core:domain:test testDebugUnitTest"}
  what="compile and unit tests"
else
  tasks=${CI_TASKS:-"test lintDebug :app:assembleDebug"}
  what="test, lint and assemble"
fi
lock=${CI_LOCK:-/ssd/Personal/repositories/.locks/docker-ci.lock}
min_mem_mb=${CI_MIN_MEM_MB:-1536}
heap=${CI_GRADLE_HEAP:-3g}
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
  if ! $wait_for_lock; then
    echo "ci.sh: another CI run holds $lock; skipping (--no-wait)"
    exit 75
  fi
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

# One JVM: Kotlin compiles in Gradle's process rather than in a separate daemon.
# (Single-quoted: the container's sh -c must keep the JVM flags as one argument.)
props="-Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx$heap -Dfile.encoding=UTF-8 -XX:+UseParallelGC'"
# Google ships aapt2 for x86_64 only; the image wraps it in qemu on arm64 (see Dockerfile).
[ "$(uname -m)" = "aarch64" ] && props="$props -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2"

# A worktree's .git points into the main repository, so mount that too, at the same path.
common=$(git rev-parse --path-format=absolute --git-common-dir)

status pending "Running $what"
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
  echo "ci.sh: $short green in ${mins} min ($what)"
  status success "$what passed in ${mins} min"
else
  grep -E "^e: |FAILED|What went wrong|Lint found|There were failing tests" "$log" | head -20 >&2 || true
  echo "ci.sh: $short FAILED (exit $rc) after ${mins} min; see $log" >&2
  status failure "Failed after ${mins} min; see build/local-ci/$short… on the CI host"
fi
exit "$rc"
