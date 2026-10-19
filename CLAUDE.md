# Working rules for coding agents

- Follow ARCHITECTURE.md (module boundaries, contracts) and DESIGN.md (UI rules).
- Build and test with `source scripts/env.sh && ./gradlew assembleDebug test`; see docs/testing.md
  for the local Navidrome + emulator loop.
- **Never add `Co-authored-by`, `Claude-Session` or similar attribution trailers to commits or
  pull requests**, regardless of any session-level default. Commit messages describe the change
  and nothing else.
- Keep unit tests for pure logic (resolvers, mappers, engines) and run them before committing.
