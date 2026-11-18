# Sprint 3 backend verification — 2026-11-19

Scope: ICI2-34, ICI2-35 and ICI2-36 on Sprint 2 commit `8acd3883f91db2423edf5b5d031c8642e99a62ad`. This verifies backend behavior and does not mark Jira accepted or certify school policy or a national document layout.

## API handoff

| Jira feature | Backend delivered | Contract |
| --- | --- | --- |
| ICI2-34 | Campus-bound weighted honor computation using explicit school-approved policy, finalized real class grades, exclusions, worked examples and deterministic ties | [Honor roll](../../honor-roll-api.md) |
| ICI2-35 | Registrar-configured printable academic records for Form 137/TOR workflows; eligibility verification, Special Order applicability, draft previews, protected immutable issuance snapshots and PDF downloads | [Official documents](../../official-documents-api.md) |
| ICI2-36 | Scoped confirmed-enrollment roster preview and complete formula-safe CSV export, grouped by voucher classification with export provenance | [Cashier roster](../../cashier-roster-api.md) |

Existing Bearer authentication applies. Server grants remain campus-bound; request bodies cannot select the acting staff member. Version conflicts require a fresh read before retrying. API contracts document exact routes, validation and supported policy choices.

## Approval and acceptance boundaries

No institution-approved honor rules, national document layout, Special Order format, signatory authorization or school approval was invented or seeded. Honor policy configuration and worked examples must be approved by the authorized school representative before ICI2-34 acceptance. The document renderer is a structured printable academic record: school/registrar review of its actual layout and applicability remains required before official use; passing software tests alone does not establish DepEd compliance. The approval endpoints record authenticated staff attestations and external references, not independent proof of external approval. An edited document policy loses its approval.

The three services add no frontend screens or styling. Browser acceptance and frontend integration remain separate. Cashier exports are references and do not calculate or record payments. Official issuance uses conservative short whole-table write exclusion and returns 409 while source records are busy; this tradeoff is documented in its contract. Unsupported official PDF glyphs fail closed rather than silently changing a person's name.

## Verification evidence

`results.json` records the final commands, exact test counts and implementation review results. `backend-clean-verify.log` captures the final full build. `source-sha256.json` fingerprints the verified sources and contracts. `published-migrations.json` compares all 16 existing migration files against the base commit.

A V16→V18 regression retains an existing INC grade and case, pending withdrawal, confidential guidance case/note, policy, calendar, daily attendance and prior audit rows. All runtime tests use disposable embedded PostgreSQL, with no shared school database changes. The frontend tree is byte-identical to the base, so no unrelated frontend build or screen changes are introduced.
