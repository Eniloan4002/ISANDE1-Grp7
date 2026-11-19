# ICI2-29/30: academic history and withdrawal backend

## Academic history

The existing history response and frontend PDF summary are preserved. A dedicated directory now allows scoped Principal read access without granting access to the student-master management API.

- `GET /api/academic/history/students?search=&page=0&size=10`: snake_case student picker rows (`id`, `first_name`, `middle_name`, `last_name`, `student_number`, `lrn`, `active`) plus `items`, `total`, `page`, `size`. Search is literal, parameterized and bounded to 150 characters. Size is 1–100.
- `GET /api/academic/history/students/{studentId}`: original `student`, `terms`, `sectionChanges`, `credentials`, plus `adviserChanges`, `incompleteCases`, `withdrawals`, `withdrawalReleaseEligible` and a `finalRatingPolicy` explanation.

Read authorization is the union of the caller's current `enrollment.manage` and `reports.read` campus grants. Each permission remains bound to its own granting campus. The seeded Principal can read academic history at authorized campuses; teachers/advisers/guidance/cashier roles do not receive unrestricted history access through these endpoints. Foreign or absent student IDs return 404. Only scoped terms and records are included.

History returns recorded submitted/finalized grade values and marks. It does not invent an institution-specific average or final-rating weighting policy. Adviser changes are derived from the existing section audit trail. Counseling, guidance and incomplete follow-up note bodies are not exposed by this general history response.

`AcademicHistory.jsx` uses the new directory URL; its existing detail rendering and PDF summary remain unchanged.

## Withdrawal authorization

New migration V15 adds two narrowly scoped write permissions:

- `withdrawals.approve`: seeded to Principal for principal approval.
- `withdrawals.clear`: seeded to Cashiering Reference for recording a clearance reference. This does not implement collections, payments or billing.

Reporting permission alone cannot perform withdrawal writes. Registrar/Administrator request creation continues to use `enrollment.manage`. Adviser decisions require `advisory.read` at the enrollment campus and the caller must be the section's currently assigned adviser. All actors and timestamps are derived server-side.

## Withdrawal endpoints

`POST /api/academic/withdrawals`

```json
{
  "enrollmentId": 123,
  "enrollmentVersion": 0,
  "reason": "Verified withdrawal request reference"
}
```

Creates one request and three pending office clearances. Enrollment must be active (`provisional` or `enrolled`), its expected version must match, and its section must have an adviser. Repeated requests for the same enrollment return 409 rather than replacing existing history.

`GET /api/academic/withdrawals?campusId=&termId=&status=&page=0&size=25`

Returns a scoped paginated queue. Valid statuses: `pending`, `approved`, `rejected`, `completed`. Adviser readers see only their assigned sections; Registrar, Principal and Cashiering readers see only the campuses authorized by the corresponding permission. Explicitly unauthorized campus filters return 403.

`GET /api/academic/withdrawals/{id}`

Returns request identity/status/version, enrollment identity/status/version, student number/name, reason/requested time, ordered `clearances`, attributed `history` and `credentialReleaseEligible`. Each clearance has its own `version`, actor, acted time and notes. Foreign or unassigned adviser access returns 404.

`PUT /api/academic/withdrawals/{id}/clearances/{office}`

Office is `adviser`, `principal` or `cashier`:

```json
{
  "withdrawalVersion": 1,
  "version": 0,
  "decision": "cleared",
  "notes": "Verified office clearance reference"
}
```

Both expected versions are required. Notes must be nonblank and no more than 2,000 characters. Decision is `cleared` or `rejected`. The server enforces Adviser → Principal → Cashier order for both approval and rejection, with each office accepting only a still-pending clearance. Stale versions, out-of-order decisions and repeated terminal actions return 409. Wrong office authority returns 403; foreign campus or unassigned adviser returns 404.

The request state is `pending` after adviser clearance, `approved` after principal clearance, and `completed` after cashier clearance. Any permitted rejection makes the request terminal `rejected` and leaves enrollment standing unchanged. The final cleared cashier decision atomically marks the enrollment and its application `withdrawn`, incrementing their versions. The permanent student remains active: another campus or term can still be valid, and grades/class memberships/history are retained.

The published adviser compatibility routes remain:

- `GET /api/academic/withdrawal-endorsements`
- `PUT /api/academic/withdrawal-endorsements/{withdrawalId}`

The decision route now delegates to the same lock/state service. It preserves its existing clearance-version payload (`version`, `decision`, optional `notes`) and result (`endorsementStatus`, `withdrawalStatus`, `version`), adding `withdrawalVersion`. It cannot bypass terminal/assignment checks. Newly integrated clients should use both versions on the full workflow routes.

## Credential release and concurrency

`GET /api/academic/withdrawals/{id}/release-eligibility` returns `{withdrawalId, eligible, reason}`. An unresolved or rejected withdrawal for that permanent student blocks release. Completion removes this withdrawal gate only; mandatory credentials, INC and SO rules still apply through their own workflows. This endpoint does not generate or issue documents.

V15 also installs a PostgreSQL guard on document request approval/issuance, so future formatters cannot silently bypass an incomplete/rejected withdrawal. Historical approved/issued records are preserved. Withdrawal insertion and document approval/issuance share a permanent-student row lock. If insertion wins, a concurrent approval waits and then fails; if approval wins, it predates the new request and remains valid history. The document guard takes only the student lock and performs non-locking enrollment/withdrawal reads, avoiding reverse acquisition of workflow locks. These two writes require READ COMMITTED isolation (the application default); stricter snapshot isolation is rejected rather than risk a stale clearance snapshot. The two-order direct-SQL concurrency test exercises this temporal boundary on disposable PostgreSQL.

Mutation locks follow application → enrollment → section → withdrawal → clearances, matching enrollment assignment and its database guard. Existing adviser decisions use the same hierarchy. Office decision, request state, terminal standing and immutable audit changes commit together. Concurrent decisions with the same expected versions have one winner and one conflict.

## Delivery scope

No live Supabase data, credentials, Jira status or deployed process is changed by this implementation. Automated tests use disposable PostgreSQL and fictional records. Frontend withdrawal screens are not created in this backend task. `HistoryWithdrawalTest` covers the complete office chain, ordering, stale/repeated/concurrent decisions, cross-campus and wrong-office denial, retained student identity, database release gating, and Principal-scoped history without confidential notes.
