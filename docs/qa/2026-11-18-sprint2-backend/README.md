# Sprint 2 backend integration verification — 2026-11-18

Scope: ICI2-26 through ICI2-33, on the committed ICI2-25 grading controls and latest upstream base. This report concerns backend completion; it does not certify frontend screens or update Jira status.

## Frontend handoff

| Jira item | Backend delivered | API contract |
| --- | --- | --- |
| ICI2-26 | Assigned-teacher correction submission with protected PDF/PNG/JPEG evidence and stale-grade detection | [Corrections/INC](../../grade-corrections-inc-api.md) |
| ICI2-27 | Scoped registrar review, mandatory decision reason, single application and retained audit history | [Corrections/INC](../../grade-corrections-inc-api.md) |
| ICI2-28 | Durable INC cases, follow-ups, teacher proposals and registrar completion; locked periods require correction approval | [Corrections/INC](../../grade-corrections-inc-api.md) |
| ICI2-29 | Scoped student directory and consolidated academic history including credentials, INC and withdrawals | [History/withdrawal](../../history-withdrawal-api.md) |
| ICI2-30 | Ordered adviser/principal/cashier clearance, versioned transitions and document release gate | [History/withdrawal](../../history-withdrawal-api.md) |
| ICI2-31 | Combined report filters, summary counts, authorization and bounded full exports | [Reporting/guidance](../../reporting-guidance-api.md) |
| ICI2-32 | Downloadable CSV/PDF from the filtered report dataset | [Reporting/guidance](../../reporting-guidance-api.md) |
| ICI2-33 | Configurable risk policy, explicit academic school days/daily attendance, confidential counseling cases and exports | [Reporting/guidance](../../reporting-guidance-api.md) |

Use existing Bearer authentication. Server grants, campus scope and class assignments determine permissions. Mutations use expected versions; refetch after 409. Request bodies never choose the acting staff member. Each linked contract documents paths, request bodies, validation and permission requirements.

Frontend changes preserve existing markup layout and styles. Academic History uses the authorized history directory. The existing INC screen consumes the real service, passes versions and displays authorized actions, required notes and truthful persistence labels. No correction, reporting, withdrawal or guidance screens were added.

## Verification evidence

Final result: **121 backend tests, 111 frontend unit tests and 15 schema checks passed; the frontend production build passed.** `./mvnw clean verify`, `npm test` and `npm run build` all exited 0. Final commands, per-suite counts and boundaries are recorded in `results.json`. Published V1–V13 migration bytes are compared with Git HEAD in `published-migrations.json`. `source-sha256.json` fingerprints the verified source set; logs capture the actual final executions.

The V13 upgrade test starts from a real prior schema and retains an existing submitted INC grade, pending correction/evidence reference and locked class configuration while verifying INC backfill and correction grade-version backfill. Tests use disposable embedded PostgreSQL rather than the shared school database.

Independent quality findings were addressed with regression checks for calendar/import lock ordering, PDF line width and concurrent withdrawal/document release. The final anti-pattern audit reported no blockers. The final source fingerprint matches the implementation present at verification start.

## Boundaries

Institution-approved risk thresholds, credential due dates and school calendar dates must be configured; none are guessed or seeded. Daily attendance is distinct from orientation attendance. Reports are operational exports, not certified Form 137/TOR documents. Locked INC completion still requires formal correction evidence and approval. Counseling notes are confidential and excluded from ordinary reports. Frontend acceptance and deployment remain separate from these backend checks.
