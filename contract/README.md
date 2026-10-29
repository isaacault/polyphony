# The contract between the clients and continuo

Every Polyphony client (Android now; Apple, desktop and TV to come) talks to the same backend:
[continuo](https://github.com/isaacault/continuo), and plain Subsonic servers through `shared/providers`.
continuo lives in its own repo, so what the clients may rely on is written down here.

- `continuo-openapi.json`: continuo's API as continuo itself describes it (FastAPI's OpenAPI schema;
  the Subsonic façade under `/rest` follows the Subsonic API and isn't in it). Regenerate it with
  `scripts/update_contract.sh` when continuo's API changes, and commit it with the client change that
  needs the new API, so a client never codes against an API that doesn't exist yet.
- `cases/`: JSON test cases for rules every client must apply the same way, read by each platform's
  tests, so the Kotlin and Swift sides can't drift. Change a rule by changing its cases here first:
  - `artist-credits.json`: which artists a track's artist tag credits ("Appears on"). Tested by
    `shared/domain` (ContractCasesTest) and `apple/PolyphonyKit` (ContractCasesTests).
  - `quality.json`: quality badges, album badges and track chips (HI-RES, FLAC, 16-BIT). Tested by
    `android/app` (ContractCasesTest) and `apple/PolyphonyKit` (ContractCasesTests).
