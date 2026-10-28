#!/usr/bin/env bash
# Usage: scripts/merge_when_green.sh <pr-number> [--rebase]
#
# Squash-merges the PR (--rebase: rebase-merges it, keeping each commit; for a batch PR whose
# commits are one concern each) and deletes its branch, but only once the `local-ci` commit status (posted
# by scripts/ci.sh --status) is success on the PR's *current* head. If that head has no status yet,
# this runs scripts/ci.sh --status on it in a throwaway worktree first. It never merges on a
# missing, failed or pending status, nor on a head that changed after it looked (the merge is
# pinned to the commit that passed). After a merge that leaves main with exactly the tree that
# passed, the status is carried over to main's new head (carry_status), so release.sh doesn't
# run the same CI again. GitHub Actions runs are not consulted: they only run for pull requests
# from forks now.
set -u
pr="${1:?pr number}"
method=--squash
[ "${2:-}" = "--rebase" ] && method=--rebase
cd "$(dirname "$0")/.." || exit 2
# This checkout's ci.sh, so a branch cut before ci.sh existed can still be checked.
ci_sh="$PWD/scripts/ci.sh"
context=local-ci
deadline=$(( $(date +%s) + 3 * 3600 ))

# A merge rewrites the commits (a rebase or squash gives them new ids); when main ended up with
# exactly the tree that passed (it hadn't moved), the code is the same, so the status is carried
# over to main's new head and release.sh needn't run the same CI again (about 18 min). Any other
# tree keeps no status: release.sh runs CI on it as before.
carry_status() {
  local tested=$1 merged
  git fetch -q origin main || return 0
  merged=$(git rev-parse origin/main)
  [ "$merged" = "$tested" ] && return 0
  if [ "$(git rev-parse "$tested^{tree}" 2>/dev/null)" = "$(git rev-parse "origin/main^{tree}")" ]; then
    gh api "repos/{owner}/{repo}/statuses/$merged" -f state=success -f context="$context" \
      -f description="same tree as ${tested:0:7}, green in local CI" --silent \
      && echo "main ${merged:0:7} is ${tested:0:7}'s tree: $context=success carried over"
  else
    echo "main ${merged:0:7} differs from ${tested:0:7}: release.sh will run CI on it"
  fi
}
ran_for=""

while :; do
  head=$(gh pr view "$pr" --json headRefOid,state -q '.headRefOid + " " + .state') || exit 2
  sha=${head%% *}; state=${head##* }
  [ "$state" != "OPEN" ] && { echo "PR $pr is $state; nothing to do"; exit 0; }
  ci=$(gh api "repos/{owner}/{repo}/commits/$sha/status" \
    -q ".statuses[] | select(.context == \"$context\") | .state" 2>/dev/null | head -1)
  case "$ci" in
    success)
      echo "green on ${sha:0:7}: $context=success"
      git fetch -q origin "pull/$pr/head" 2>/dev/null   # the tested commit, to compare trees after
      gh pr merge "$pr" "$method" --delete-branch --match-head-commit "$sha" || exit $?
      carry_status "$sha"
      exit 0
      ;;
    failure|error)
      echo "NOT MERGING $pr: $context=$ci on ${sha:0:7}"
      exit 1
      ;;
    "")
      if [ "$ran_for" = "$sha" ]; then echo "NOT MERGING $pr: ci.sh ran on ${sha:0:7} but posted no status"; exit 1; fi
      ran_for=$sha
      echo "no $context status on ${sha:0:7}; running scripts/ci.sh --status on it"
      git fetch -q origin "pull/$pr/head" || exit 2
      wt=$(mktemp -d "${TMPDIR:-/tmp}/polyphony-ci-$pr.XXXXXX")
      git worktree add -q --detach "$wt" "$sha" || exit 2
      (cd "$wt" && "$ci_sh" --status)
      git worktree remove --force "$wt" 2>/dev/null || rm -rf "$wt"
      continue
      ;;
  esac
  # pending: another ci.sh is running on this head (maybe queued behind the machine lock).
  [ "$(date +%s)" -gt "$deadline" ] && { echo "TIMEOUT waiting for $context on ${sha:0:7}: $ci"; exit 1; }
  sleep 30
done
