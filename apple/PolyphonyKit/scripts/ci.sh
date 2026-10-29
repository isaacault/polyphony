#!/usr/bin/env bash
# Build and test PolyphonyKit in the Swift toolchain image (Linux, works on the Jetson); exit 0 = green.
# The app targets (SwiftUI, AVFoundation) build only in Xcode on a Mac.
#   scripts/ci.sh            swift build + swift test
set -euo pipefail
cd "$(dirname "$0")/.."
IMAGE=swift:6.2-noble
LOCK=/ssd/Personal/repositories/.locks/docker-ci.lock
run() {
  docker run --rm --network none --user "$(id -u):$(id -g)" -e HOME=/tmp \
    -v "$PWD:/src" -w /src "$IMAGE" sh -c 'swift build --build-path /src/.build/linux -q && swift test --build-path /src/.build/linux -q'
}
if [ -w "$(dirname "$LOCK")" ]; then exec 9>"$LOCK"; flock 9; fi
if run; then echo "ci: green"; else echo "ci: FAILED"; exit 1; fi
