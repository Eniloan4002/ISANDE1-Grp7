# Shared Supabase publication validation

November 10, 2026. Built from GitHub main a62a677 with the reviewed enrollment/account/Supabase integration overlaid in an isolated branch. Existing collaborator RBAC/account test coverage is preserved and adapted to versioned writes/paginated responses. The owner-only original requirement API commit is already represented by the reconciled V8 implementation; its history is retained during publication without reintroducing the obsolete duplicate V6 file.

Validation on the actual publication tree:

- **75 backend tests passed**, zero failures/errors/skips, including upstream account and permission regressions.
- **78 frontend tests**, **15 schema/archive checks**, and the production build passed.
- **13 browser tests passed**, zero unexpected failures or flaky tests. One legacy enrollment test requires a separately supplied disposable application ID and was skipped; the same intake-through-confirmation/allocation behavior ran successfully in the end-to-end journeys.
- Shared-launcher normal first-run prompts only for the database password and uses existing accounts; owner bootstrap is explicit. Environment checks confirm stale datasource settings cannot redirect the configured target and passwords are not saved. POSIX private-file permissions are enforced; Windows uses its Maven wrapper and local access controls.
- The demo tool created all nine scenarios through real APIs on disposable PostgreSQL. A simulated fresh clone detected the existing shared demo term and added/reset no records.
- Generic credential scanning and comparison against the live database password and owner signing key found no such values in the proposed files. Private environment files, PID/seed manifests, local AGENTS, generated files and unrelated prototype edits are excluded.
- Published V1–V7 stay intact; V8–V11 are the reconciled additions. Existing Supabase data is not reset by publication/startup.

The first combined run exposed a test expectation difference: unsupported role input now consistently returns 400, while actual role/campus authorization failures remain 403/404. The expectation was aligned and the full 75-test suite passed. No acceptance checks were removed to obtain a pass.

[Structured results](results.json) and [source hashes](source-sha256.json) identify the tested tree. Previous QA folders remain historical snapshots and their local/unpublished status refers to their execution time, not the current release.

Teammates should use [shared Supabase startup](../../run-with-supabase.md), obtain credentials privately and reuse **SPRINT DEMO 65633D**. No teammate needs to create a new Supabase project, recreate staff accounts or seed the shared demo again. Manual ICI2-13–19 acceptance remains in the [quick demo](../../quick-sprint-demo.md); Jira status changes are not part of this publication.
