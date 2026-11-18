# ICI2-25 backend verification

18 November 2026. Base revision: `b6eb6f6`, branch `codex/sprint2-grade-locks`.

Implemented Registrar section/period deadline, lock and finalization APIs, including teacher API enforcement, transactional audits, optimistic versions, V13 migration and API documentation. There is no new Registrar frontend in this backend slice.

## Results

- Full backend `./mvnw -q verify` passed (97 tests and packaging at that point).
- A subsequent explicit V12-to-V13 preservation test passed in the migration suite.
- A subsequent mid-request deadline rollback test passed in the grading-control suite.
- The final report set covers **99 unique backend tests, zero failures/errors/skips**, including 11 ICI2-25 tests. The two added tests were verified in focused reruns; application sources were unchanged after the full verify.
- Frontend `npm test`: **107 unit tests and 15 schema/archive checks passed** on the upstream-based worktree, using the existing local dependency installation via a temporary symlink which was removed afterwards.
- `git diff --check` passed. Published migration bytes V1–V12 were compared against base HEAD and remain unchanged.

The first sandboxed database attempt could not open a socket; the disposable PostgreSQL/HTTP checks were rerun with the required execution permission. The first targeted integration run also found a test URL that double-encoded a grade-level space; the fixture was corrected and the full/final checks passed. No application validation or acceptance assertion was removed.

## Critical behavior verified

1. Registrar deadline save/read, monotonic versions and stale-write rejection.
2. Manual lock attribution and persistence; teacher read access remains.
3. Both teacher draft and submit return 403 for locked or expired periods; other periods remain writable.
4. Expired policies cannot be extended to reopen submissions; legacy class deadlines also apply.
5. Finalization preserves values/marks, increments grade versions once, records actors and persists finalized state.
6. Missing submissions in any configured active class roster reject the entire section finalization without a policy or partial finalized grades.
7. A class added after manual lock inherits it; new offerings after finalization are rejected.
8. Wrong permission, foreign campus and unrelated local-role grants cannot access controls; missing version/reason and cross-term periods are rejected.
9. Two Registrar locks or finalizations have one winner and one conflict.
10. A teacher batch racing a manual lock either completes first in full or is rejected with zero partial grades.
11. A teacher blocked on an offering beyond cutoff is denied; a draft blocked on a grade row rolls back value/version changes when its deadline crosses.

## Database and delivery boundaries

All backend tests used embedded disposable PostgreSQL with fictional accounts and random fixture records. Shared Supabase, live demo data and the original running app were not modified or restarted. No migrations were deployed, code pushed, or Jira statuses changed. The original checkout retains its unpublished Sprint 1 frontend fixes.

The backend is ready for code/API review. A Registrar frontend and shared-environment acceptance are separate delivery steps. Review the documented 403 contract change with frontend collaborators; ordinary stale-grade conflicts remain 409. Coordinate subsequent INC/correction services with these section controls rather than bypassing the reviewed grade-change path.

See [API contract](../../grading-controls-api.md), [structured results](results.json), and [source hashes](source-sha256.json).
