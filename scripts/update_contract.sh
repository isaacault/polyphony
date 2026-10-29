#!/usr/bin/env bash
# Regenerate contract/continuo-openapi.json from a continuo checkout: the API every client codes
# against. Run it when continuo's API changes, commit the result with the client change that needs it.
#   scripts/update_contract.sh [path to continuo]      (default: continuo next to the main checkout)
set -euo pipefail
cd "$(dirname "$0")/.."
main_checkout=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")   # also from a worktree
CONTINUO=$(cd "${1:-$main_checkout/../continuo}" && pwd)
img=$(docker images --format '{{.Repository}}:{{.Tag}}' | grep '^continuo-ci:' | head -1)
[ -n "$img" ] || { echo "no continuo-ci image: run continuo's scripts/ci.sh once"; exit 1; }
docker run --rm --network none --user "$(id -u):$(id -g)" -e HOME=/tmp -e DB_PATH=/tmp/contract.db \
  -v "$CONTINUO:/src:ro" -w /src "$img" python -c '
import json
from continuo.main import create_app
spec = create_app(disable_workers=True).openapi()
print(json.dumps(spec, indent=1, sort_keys=True))
' > contract/continuo-openapi.json.tmp
mv contract/continuo-openapi.json.tmp contract/continuo-openapi.json
echo "contract/continuo-openapi.json from continuo $(git -C "$CONTINUO" log --oneline -1)"
python3 -c "import json; d=json.load(open('contract/continuo-openapi.json')); print(len(d['paths']), 'paths')"
