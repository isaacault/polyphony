# Working rules for coding agents

- Follow ARCHITECTURE.md (module boundaries, contracts) and DESIGN.md (UI rules).
- One repo for every client (ARCHITECTURE.md): `shared/` must not depend on `android/` or on Android
  APIs; `apple/PolyphonyKit` builds and tests with `apple/PolyphonyKit/scripts/ci.sh` (Swift in Docker),
  the Apple apps only in Xcode on a Mac. continuo's API is `contract/continuo-openapi.json`
  (`scripts/update_contract.sh`).
- Build and test with `scripts/ci.sh` (Docker, any checkout or worktree; exit 0 = green), or natively
  with `source scripts/env.sh && ./gradlew assembleDebug test`; see docs/testing.md for local CI and
  the local Navidrome + emulator loop.
- **Never add `Co-authored-by`, `Claude-Session` or similar attribution trailers to commits or
  pull requests**, regardless of any session-level default. Commit messages describe the change
  and nothing else.
- Keep unit tests for pure logic (resolvers, mappers, engines) and run them before committing.

## Branch and release workflow

- Never commit features or fixes directly to `main`. Each change goes on a branch
  (`feat/...`, `fix/...`, `chore/...`) and lands through a pull request; CI must be green.
  CI is local: the pre-push hook (`scripts/install-hooks.sh`) runs the quick check
  (`scripts/ci.sh --quick`) and `scripts/ci.sh --status` posts the full `local-ci` commit status.
  Merge with `scripts/merge_when_green.sh <pr>`, which merges only when `local-ci` is success on
  the current head (running CI itself if there is no status yet); never merge without it.
- Do **not** tag a release per change. Releases are batched: several merged PRs, then one
  `scripts/release.sh <version>` (it bumps, builds, signs, verifies and publishes from this
  machine), when the user asks for a release or a milestone chunk is complete.
- Keep changes focused and reviewable: one concern per commit, with a message saying what and
  why, and a PR description of how it was verified (unit tests, emulator run, device report).
- A full app CI run is the bottleneck, so app work is batched: finished concerns are committed
  (unbuilt) one per commit on a batch branch (`feat/app-batch-<release>`), and when the batch is
  ready it gets one PR, one CI run on its head, and `scripts/merge_when_green.sh <pr> --rebase`,
  which keeps each commit on `main`. A single urgent fix can still go alone (squash merge).
