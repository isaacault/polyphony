#!/usr/bin/env bash
# Usage: scripts/merge_when_green.sh <pr-number>
#
# Squash-merges the PR and deletes its branch, but only once the `local-ci` commit status (posted
# by scripts/ci.sh --status) is success on the PR's *current* head. If that head has no status yet,
# this runs scripts/ci.sh --status on it in a throwaway worktree first. It never merges on a
# missing, failed or pending status, nor on a head that changed after it looked (the merge is
# pinned to the commit that passed). GitHub Actions runs are not consulted: they only run for
# pull requests from forks now.
set -u
pr="${1:?pr number}"
cd "$(dirname "$0")/.." || exit 2
# This checkout's ci.sh, so a branch cut before ci.sh existed can still be checked.
ci_sh="$PWD/scripts/ci.sh"
context=local-ci
deadline=$(( $(date +%s) + 3 * 3600 ))
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
      gh pr merge "$pr" --squash --delete-branch --match-head-commit "$sha"
      exit $?
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
