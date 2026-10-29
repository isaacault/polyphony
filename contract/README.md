# The contract between the clients and continuo

Every Polyphony client (Android now; Apple, desktop and TV to come) talks to the same backend:
[continuo](https://github.com/isaacault/continuo), and plain Subsonic servers through `shared/providers`.
continuo lives in its own repo, so what the clients may rely on is written down here.

- `continuo-openapi.json`: continuo's API as continuo itself describes it (FastAPI's OpenAPI schema;
  the Subsonic façade under `/rest` follows the Subsonic API and isn't in it). Regenerate it with
  `scripts/update_contract.sh` when continuo's API changes, and commit it with the client change that
  needs the new API, so a client never codes against an API that doesn't exist yet.
- `cases/` (to come, with the move of the rules into `shared/`): JSON test cases for rules every client
  must apply the same way (quality badges, server addresses, …), read by each platform's tests, so the
  Kotlin and Swift sides can't drift.
