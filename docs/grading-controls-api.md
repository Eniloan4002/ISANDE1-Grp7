# ICI2-25: section grading controls

Backend implementation based on GitHub main `b6eb6f6`. The existing teacher grade API remains in place. These endpoints add Registrar controls; there is no new Registrar frontend screen in this change.

## Authorization and scope

All control routes require a current `grades.review` grant at the section's campus (Registrar in the seeded policy). A teacher token cannot call Registrar routes. An unrelated campus grant from another role does not extend grading scope. Out-of-scope section/period detail and mutations return 404. An explicitly unauthorized campus list filter returns 403. ADMIN is not implicitly granted grading-review authority.

## Read endpoints

- `GET /api/academic/grading-windows`: paginated section/period directory. Optional filters: `campusId`, `termId`, `sectionId`, `gradingPeriodId`, exact `gradeLevel`; `page` starts at 0, `size` is 1–100. Response: `items`, `total`, `page`, `size`.
- `GET /api/academic/sections/{sectionId}/grading-periods/{periodId}/window`: response has `window` and `classes`. Classes contain class/subject identifiers, active roster count, submitted count and finalized count. The period must belong to the section's term.

`window` contains section/campus/term/period identifiers, deadline, manual lock and finalization timestamps/actors, last reason, version and section policy status (`open`, `locked`, `finalized`). An unconfigured policy has version 0. Legacy per-class grading windows remain additional restrictions; section status does not override those class policies.

## Write endpoints

`PUT /api/academic/sections/{sectionId}/grading-periods/{periodId}/deadline`

```json
{
  "deadline": "2026-11-28T17:00:00+08:00",
  "version": 0,
  "reason": "Submission deadline approved by the Registrar"
}
```

The deadline is an offset-aware ISO timestamp. A deadline in the past closes submissions immediately. Once the deadline has elapsed, or a manual lock/finalization exists, this endpoint cannot reopen the section by extending it. Before cutoff a versioned deadline edit is allowed. No reopen endpoint or implicit unlock is provided; post-lock changes belong to the separate correction workflow.

`POST /api/academic/sections/{sectionId}/grading-periods/{periodId}/lock`

`POST /api/academic/sections/{sectionId}/grading-periods/{periodId}/finalize`

Both accept:

```json
{
  "version": 1,
  "reason": "Registrar review completed"
}
```

Use the version from the latest read response; 0 means no section policy exists. A successful policy mutation increments its version. Missing/invalid fields return 400; stale versions, repeated terminal actions and invalid state transitions return 409. Every reason must contain non-whitespace text and be at most 2,000 characters.

Manual lock freezes teacher draft saves and submission without requiring submitted grades. Finalization additionally requires at least one configured class and at least one active student in each class roster. All active roster grades must be submitted or already finalized. Any missing/draft grade or empty configured class rejects the whole operation with 409; there are no partial changes. Success finalizes submitted active-roster grades and locks the section/period atomically. Numeric values and INC/NG/DRP marks are retained; grade versions increment. Repeated finalization is rejected, preventing duplicate grade-version increments.

Section enrollment and subject-class membership are distinct. This service validates configured active class rosters. Creating a class offering (`POST /api/academic/class-offerings`) adds every active (provisional or enrolled) student of that section and term to the class roster. Students who join the section later are added with `POST /api/academic/class-offerings/{id}/members/sync?academicYearId=&termId=` (requires `enrollment.manage`; safe to repeat; the response includes `addedMembers`). Removing a student from a single subject (electives, cross-enrollment) is not implemented yet. This service does not close enrollment. New subject offerings are rejected for a section with any finalized period, avoiding a new ungradable subject beneath an already-finalized section.

## Enforcement, concurrency and audit

- Policies are stored per section and period, including when there are no classes yet. Later classes inherit section locks/deadlines. They cannot bypass the restriction through an absent class-specific window.
- The Registrar mutation locks the section, then offerings in ascending ID order. Teacher mutations lock their offering before evaluating controls. They cannot race past a manual section lock or finalization.
- Teacher reads expose the earliest section/class deadline. Either manual lock, or either elapsed deadline, blocks both draft and submit writes with **403**. This intentionally replaces the old class-lock 409 response to match the implementation plan; ordinary stale-grade/duplicate-submission conflicts remain 409.
- The PostgreSQL wall clock (`clock_timestamp()`) evaluates deadlines after waiting for the offering lock. Teacher writes check again after processing the batch and roll back if the deadline crossed. This is request-transaction cutoff enforcement, not a scheduler that stamps `locked_at` at the deadline or a guarantee about network delivery time.
- An elapsed deadline reports `locked` even if `lockedAt` is null. Manual lock and finalization record their explicit actor/timestamp.
- Section policies and grade changes use the existing transaction actor and append-only before/after audit triggers. A single transaction covers grades, policy and audit events. Prior reasons remain in the audit history.

## Migration and verification

New migration: `V13__section_grading_controls.sql`. Published V1–V12 are unchanged. Fresh and previously published databases upgrade through Flyway. The existing unpublished-history bridge still applies before the new migration. Do not run Flyway repair or reset a shared database to adopt this change.

Integration coverage is in `GradingWindowTest`: deadline persistence, stale controls, manual locks, elapsed section and legacy class deadlines, post-lock reads, teacher write rejection, cross-campus/mixed-grant denial, finalization rollback, audit actor, late class inheritance, prevention of new offerings after finalization, two simultaneous Registrar locks/finalizations, a teacher batch racing a lock, and a teacher blocked until after deadline. `GradeSubmissionTest`, foundation/upgrade tests and the complete backend suite remain regression gates.

No migration or grading change was applied to the shared Supabase database during development. The original local app and its uncommitted frontend fixes remain separate from this worktree.
