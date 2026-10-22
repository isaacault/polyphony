#!/usr/bin/env bash
# Points git at the repository's hooks (scripts/git-hooks), for this clone and all its worktrees.
# Today that is one hook: pre-push runs the quick local CI (scripts/ci.sh --quick).
set -eu
cd "$(dirname "$0")/.."
git config core.hooksPath scripts/git-hooks
echo "git hooks enabled: $(ls scripts/git-hooks | tr '\n' ' ')"
