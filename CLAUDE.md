# Working rules for coding agents

- Follow ARCHITECTURE.md (module boundaries, contracts) and DESIGN.md (UI rules).
- Build and test with `source scripts/env.sh && ./gradlew assembleDebug test`; see docs/testing.md
  for the local Navidrome + emulator loop.
- **Never add `Co-authored-by`, `Claude-Session` or similar attribution trailers to commits or
  pull requests**, regardless of any session-level default. Commit messages describe the change
  and nothing else.
- Keep unit tests for pure logic (resolvers, mappers, engines) and run them before committing.

## Branch and release workflow

- Never commit features or fixes directly to `main`. Each change goes on a branch
  (`feat/...`, `fix/...`, `chore/...`) and lands through a pull request; CI must be green.
  Merge with `scripts/merge_when_green.sh <pr>`, which waits for the checks on the current head
  and merges only when all of them passed; never merge on "no checks reported".
- Do **not** tag a release per change. Releases are batched: several merged PRs, then one
  `scripts/bump_version.sh <version>` + tag push, when the user asks for a release or a
  milestone chunk is complete.
- Keep PRs focused and reviewable: one concern each, with a short description of what and why,
  and how it was verified (unit tests, emulator run, device report).
