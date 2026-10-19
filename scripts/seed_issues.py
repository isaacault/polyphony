#!/usr/bin/env python3
"""Create or update the roadmap labels, milestones and issues on GitHub from docs/roadmap/issues.json.

Idempotent: existing labels are updated, existing milestones and issues (matched by title) are
left alone. Requires an authenticated GitHub CLI (`gh auth login`).

    python3 scripts/seed_issues.py            # against the repo's origin
    python3 scripts/seed_issues.py --dry-run
"""
import json
import pathlib
import subprocess
import sys

DRY = "--dry-run" in sys.argv
ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA = json.loads((ROOT / "docs/roadmap/issues.json").read_text())


def gh(*args, input_json=None, ok_codes=(0,)):
    cmd = ["gh", *args]
    if DRY and args[0] in ("api",) and any(a in ("-X", "--method") for a in args) and "GET" not in args:
        print("  dry-run:", " ".join(cmd))
        return ""
    if DRY and args[0] in ("issue", "label") and args[1] in ("create", "edit"):
        print("  dry-run:", " ".join(cmd))
        return ""
    proc = subprocess.run(cmd, input=input_json, capture_output=True, text=True)
    if proc.returncode not in ok_codes:
        sys.exit(f"command failed: {' '.join(cmd)}\n{proc.stderr}")
    return proc.stdout


def repo_slug():
    out = gh("repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner")
    return out.strip()


def ensure_labels():
    existing = {l["name"] for l in json.loads(gh("label", "list", "--limit", "200", "--json", "name"))}
    for label in DATA["labels"]:
        if label["name"] in existing:
            gh("label", "edit", label["name"], "--color", label["color"], "--description", label["description"])
        else:
            gh("label", "create", label["name"], "--color", label["color"], "--description", label["description"])
    print(f"labels: {len(DATA['labels'])} ensured")


def ensure_milestones(slug):
    existing = json.loads(gh("api", f"repos/{slug}/milestones?state=all&per_page=100"))
    by_title = {m["title"]: m for m in existing}
    for ms in DATA["milestones"]:
        if ms["title"] not in by_title:
            created = gh("api", "-X", "POST", f"repos/{slug}/milestones",
                         "-f", f"title={ms['title']}", "-f", f"description={ms['description']}")
            if created:
                by_title[ms["title"]] = json.loads(created)
    print(f"milestones: {len(DATA['milestones'])} ensured")
    return by_title


def ensure_issues():
    existing = {i["title"] for i in json.loads(gh("issue", "list", "--state", "all", "--limit", "500", "--json", "title"))}
    created = skipped = 0
    for issue in DATA["issues"]:
        if issue["title"] in existing:
            skipped += 1
            continue
        args = ["issue", "create", "--title", issue["title"], "--body", issue["body"],
                "--milestone", issue["milestone"]]
        for label in issue["labels"]:
            args += ["--label", label]
        gh(*args)
        created += 1
    print(f"issues: {created} created, {skipped} already existed")


if __name__ == "__main__":
    slug = repo_slug()
    print(f"repository: {slug}{' (dry run)' if DRY else ''}")
    ensure_labels()
    ensure_milestones(slug)
    ensure_issues()
