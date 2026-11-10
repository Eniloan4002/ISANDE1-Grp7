# Team integration review: November 9, 2026

Reviewed fetched `origin/main` at `44eb069` against local `main` at `2a82b07` and the existing uncommitted workspace. No merge, reset, board update, or push was performed.

## Duplicate detection: Elijah's PR #6

[PR #6](https://github.com/Eniloan4002/ISANDE1-Grp7/pull/6) was merged November 4. Its head was `7e0dc9f`, with implementation commit `16b4214`. It changes only `prototype/assets/js/app.js`, `data-store.js`, and `evaluate.js`. It adds exact LRN and fuzzy name plus DOB matching, flags records for review, and routes to the prototype comparison screen. It uses browser localStorage rather than Java/PostgreSQL. No backend, migration, authentication, or build configuration files were changed by this PR.

The PR has no recorded automated status checks. Six read-only checks against its exact head commit now cover cross-campus exact LRN matching, fuzzy name plus normalized DOB matching, different-DOB rejection, blank identifiers, self-exclusion, and flag/pair creation with resolved-pair handling. Run:

```sh
node scripts/check-duplicate-pr.mjs
```

All six passed during this review. This is targeted store-logic verification, not a full browser regression or production duplicate-service certification. No backend disruption was identified from this PR's diff or these checks. The planned Spring Boot duplicate service, server-side state enforcement and durable cross-campus resolution remain separate integration work.

## Published version versus this workspace

- Remote `main` includes PR #6, [PR #9](https://github.com/Eniloan4002/ISANDE1-Grp7/pull/9) intake screens, and [PR #10](https://github.com/Eniloan4002/ISANDE1-Grp7/pull/10) requirement screens. The latter two are mock-data React implementations.
- [PR #7](https://github.com/Eniloan4002/ISANDE1-Grp7/pull/7) RBAC/workflow changes were reverted by [PR #8](https://github.com/Eniloan4002/ISANDE1-Grp7/pull/8). The revert body gives no explanation beyond linking the original PR. Do not attribute a failure to a contributor without evidence.
- Local `main` contains requirement API commit `2a82b07`, which is absent from fetched remote `main`. Remote currently has migrations through V5; this workspace has V6-V8 and database-backed enrollment work.
- The workspace has substantial uncommitted React, backend, and prototype changes. A blanket pull or replacement would require conflict resolution across those changes and the team's mock screens. Publishing requires preserving both contributions, replacing mock providers with authenticated API integration, and reviewing migration versions.

The board's Done label for ICI2-13 establishes reported prototype delivery. It does not establish completion of the backend deliverable in the backlog. Conversely, the board's To Do labels for locally implemented requirements and allocation work do not establish that no code exists.

## Team startup

Follow [Run the complete app](run-complete-app.md) for PostgreSQL, backend, frontend, initial administrator setup, Windows/macOS commands, and troubleshooting. A fresh clone receives the published version, not this workspace's uncommitted work.

## Verification performed

- `./mvnw verify`: 40 backend tests passed, zero failures/errors/skips; package build succeeded using native disposable PostgreSQL 17.6 and migrations V1-V8.
- `npm test`: 57 React unit tests and 15 schema/archive checks passed.
- `npm run build`: production frontend build passed.
- `node scripts/check-duplicate-pr.mjs`: all six checks against PR #6's head passed.
- Started `run-local-postgres.sh` on temporary port 18081 with a throwaway bootstrap administrator and Vite on 15173 targeting that backend. The frontend served `/sign-in`; login through its `/api/auth/login` proxy returned a token, and `/api/auth/me` accepted that token. Token/password values were not printed.

Startup verification used the disposable database alternative, not Docker Compose. Windows instructions were reviewed but not executed on this macOS machine. The checks do not represent a new full-browser workflow regression or certification of remote `main` as production-ready.
