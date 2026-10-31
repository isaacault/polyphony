#!/usr/bin/env bash
# Build and test PolyphonyKit in the Swift toolchain image (Linux, works on the Jetson); exit 0 = green.
# The app targets (SwiftUI, AVFoundation) build only in Xcode on a Mac.
#   scripts/ci.sh            swift build + swift test
set -euo pipefail
cd "$(dirname "$0")/.."
IMAGE=swift:6.2-noble
LOCK=/ssd/Personal/repositories/.locks/docker-ci.lock
# The whole repo is mounted: the tests read contract/cases, which both apps' tests share.
ROOT=$(git rev-parse --show-toplevel)
PKG=${PWD#"$ROOT"/}
# The build (tests included) is tried twice: on the Jetson it now and then dies (signal 7 or 11) on the
# first build after a change, and goes through when run again; a real compile error fails both times.
# The tests then run without building, so a crash in a test is never retried away.
run() {
  docker run --rm --network none --user "$(id -u):$(id -g)" -e HOME=/tmp \
    -v "$ROOT:/src" -w "/src/$PKG" "$IMAGE" \
    sh -c "B='--build-path /src/$PKG/.build/linux -q'; { swift build --build-tests \$B || swift build --build-tests \$B; } && swift test --skip-build \$B"
}
if [ -w "$(dirname "$LOCK")" ]; then exec 9>"$LOCK"; flock 9; fi
if run; then echo "ci: green"; else echo "ci: FAILED"; exit 1; fi
