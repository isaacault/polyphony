#!/usr/bin/env bash
# Type-checks the Mac app's logic files (the model, downloads) on Linux, against PolyphonyKit and the
# stand-ins in scripts/linux-stand-ins for what only a Mac has. Views and Platform glue need a Mac.
# Builds PolyphonyKit first (its ci.sh). Exit 0 = they type-check.
set -euo pipefail
cd "$(dirname "$0")/.."
PolyphonyKit/scripts/ci.sh >/dev/null || { echo "typecheck-app: PolyphonyKit's ci.sh failed (run it to see why)" >&2; exit 1; }
TMP=$(mktemp -d "${TMPDIR:-/tmp}/typecheck-app.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
FILES=(Polyphony/App/AppModel.swift Polyphony/Platform/Downloads.swift)
for f in "${FILES[@]}"; do sed '/^import SwiftUI$/d; /^import AppKit$/d' "$f" > "$TMP/$(basename "$f")"; done
cp scripts/linux-stand-ins/StandIns.swift "$TMP/"
docker run --rm --network none --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD:/src:ro" -v "$TMP:/check:ro" -w /src swift:6.2-noble \
  sh -c 'swiftc -typecheck -swift-version 5 -I PolyphonyKit/.build/linux/debug/Modules /check/*.swift'
echo "typecheck-app: ok (${FILES[*]})"
