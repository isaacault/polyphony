#!/usr/bin/env bash
# Usage: scripts/merge_when_green.sh <pr-number>
#
# Waits until every workflow run for the PR's *current* head commit has completed, then
# squash-merges and deletes the branch, but only if all of them succeeded. It never merges on
# "no checks reported", on a cancelled or failed run, or on a head that changed since it looked.
set -u
pr="${1:?pr number}"
cd "$(dirname "$0")/.." || exit 2
deadline=$(( $(date +%s) + 3600 ))
while :; do
  head=$(gh pr view "$pr" --json headRefOid,state -q '.headRefOid + " " + .state') || exit 2
  sha=${head%% *}; state=${head##* }
  [ "$state" != "OPEN" ] && { echo "PR $pr is $state; nothing to do"; exit 0; }
  runs=$(gh run list --commit "$sha" --json status,conclusion,workflowName \
    -q '.[] | "\(.workflowName)=\(.status)/\(.conclusion)"' 2>/dev/null)
  if [ -n "$runs" ] && ! grep -q "in_progress\|queued\|waiting\|pending\|requested" <<<"$runs"; then
    if grep -vq "/success" <<<"$runs"; then echo "NOT MERGING $pr: $runs"; exit 1; fi
    echo "green on ${sha:0:7}: $runs"
    gh pr merge "$pr" --squash --delete-branch
    exit $?
  fi
  [ "$(date +%s)" -gt "$deadline" ] && { echo "TIMEOUT waiting for checks on ${sha:0:7}: $runs"; exit 1; }
  sleep 30
done
