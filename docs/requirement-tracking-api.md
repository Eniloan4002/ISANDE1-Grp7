# ICI2-15 — Requirement Tracking Backend & Audit Logger

## Delivered scope

Implements Epic 2, Feature 2.3: per-application verification of PSA Birth Certificate, Form 137 / Report Card, Certificate of Completion and Good Moral Certificate. The requirement_types catalog is the source of labels and mandatory flags. Extends the existing application_requirements table and database audit triggers; no duplicate audit logger or student identity table is introduced.

The API supports submitted, missing, pending and invalid item states, optional verification notes, staff/time attribution, overall completeness and immutable history. It does not implement the ICI2-16 checklist screen, applicant intake, file uploads or ICI2-17 enrollment confirmation.

## API

All calls require the Bearer JWT from /api/auth/login.

| Method | Endpoint | Behavior |
| --- | --- | --- |
| GET | /api/requirement-types | Catalog with code, name and mandatory flag |
| GET | /api/applications/{applicationId}/requirements | Full checklist and computed summary |
| PUT | /api/applications/{applicationId}/requirements/{code} | Save one item's status and notes; returns refreshed checklist |
| GET | /api/applications/{applicationId}/requirements/audit | Paginated verification history |

Use the numeric database application ID, not a student ID or the display application_number. Checklist responses include applicationNumber, campusId and nullable studentId so the UI can label the correct applicant. Intake/queue work must supply the ID from applications.

Update example:

```json
{"status":"submitted","version":0,"notes":"Original certificate inspected."}
```

Use exact case-sensitive codes returned by the catalog (seeded codes: PSA, FORM137, COMPLETION, GOOD_MORAL). Codes are trimmed, not uppercased: configured custom codes remain usable. version is required and nonnegative; notes is optional, at most 2,000 characters. Omitting notes clears previous notes. No attachment storage keys or actor/verifier IDs can be supplied by the client.

A successful PUT returns 200 with the full checklist. Invalid input returns 400; unknown/inaccessible applications and unknown requirement codes return 404. Missing/invalid authentication returns 401; authenticated roles outside admin/registrar receive 403. A stale version returns 409 and never overwrites another verifier's work.

## Checklist response and completeness

Each item includes code, name, mandatory, status, version, recorded, notes, verifiedBy, verifiedAt, updatedBy, updatedAt and originalFilename. File bytes/storage keys are not exposed. Both timestamps and actor IDs may be null for unrecorded or historical records.

No rows are created by GET. An unrecorded item has status pending, version 0, recorded false and null verification metadata. Its first PUT creates a real row at version 1. Previously stored/imported rows retain their actual state and initial version 0 until edited. The recorded flag distinguishes these cases.

summary fields: overallStatus, mandatoryTotal, submitted, missing, pending, invalid, credentialsComplete. Counts cover mandatory items only. Every configured item remains visible in items, including optional documents.

The completeness rule follows the mockup's deriveRequirements behavior:

1. No mandatory catalog items: Pending and credentialsComplete=false.
2. Every mandatory item submitted: Complete and credentialsComplete=true.
3. Any mandatory item missing or invalid: Incomplete.
4. All other combinations: Pending.

Optional missing/invalid documents do not block mandatory completeness. credentialsComplete describes credentials only; it does not assert attendance verification or enrollment eligibility. This service does not change application/enrollment standing automatically.

## Verification history and concurrency

The API takes the verifier from the authenticated StaffIdentity and sets app.actor_id inside the write transaction. Inserts and actual updates are logged by the existing append-only PostgreSQL trigger with previous/new status, notes, staff ID and time. Exact repeat saves with the current version are no-ops; they do not manufacture audit events or increment versions. Note-only changes are audited but retain the original submitted verification time. Transitioning away from submitted clears its verification stamp; a later transition back records the new verifier and time.

The parent application row is locked for updates, matching the existing enrollment-confirmation guard's lock. Concurrent first saves for the same item and version produce one success and one 409. Individual updates and their audit events commit/rollback together. Read responses use a repeatable-read transaction so summary/history counts match their returned rows.

History query parameters: optional code, zero-based page (default 0), size (default 25; maximum 100). Responses include applicationId, items, total, page and size, newest first by audit ID. Events contain id, operation, occurredAt, actorId, actorName, requirementCode, oldStatus, newStatus, oldNotes and newNotes. Legacy/system events may have no actor. History covers legacy INSERT/UPDATE/DELETE events as well as new writes. There are no history-edit/delete API endpoints.

## Authorization handoff

Baseline policy matches student master management: only ROLE_ADMIN or ROLE_REGISTRAR grants at the application's campus permit these operations. Cross-campus read, write and audit requests return 404 without disclosing the applicant. Teacher/adviser/guidance/principal/cashiering visibility must be implemented through their scoped business workflows under ICI2-7; this API does not give them unrestricted credential access.

## Migration and tests

V6 adds per-item version, notes, verified_at and an application-history index. It preserves existing data, timestamps, source files and audit triggers. It deliberately does not invent historical verification times or users. Run the backend normally to apply Flyway, or ./mvnw verify to use disposable PostgreSQL tests. Add V7 and later migrations after sharing this version.

Verification: all 26 PostgreSQL 17.6 HTTP/backend tests pass (9 new requirement tests + 17 regressions), the backend package builds, and 15 archive/PGlite checks pass with V6. Coverage includes all summary states, optional documents, read-only GETs, persisted verifier/history values, no-ops, stale writes, malformed requests, campus/role checks, audit paging/immutability and simultaneous saves.

The archive regression now validates frozen checksums independently of the evolving prototype directory. This allows collaborators to improve the working prototype while preserving the original snapshot.
