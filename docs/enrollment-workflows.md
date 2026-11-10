# Enrollment verification and section allocation: ICI2-16-19

Current November 10 reconciliation: published V1–V7 plus local V8–V11; live permission checks, one versioned account API, and persisted enrollment/attendance. See [migration reconciliation](migration-reconciliation.md) and [demo/manual pass](demo-13-19-checklist.md). Earlier delivery notes below retain their historical version references.

Current follow-up: identity resolution, existing-student linking, account editing/recovery and attendance configuration are implemented locally. See [gap closure handoff](gap-closure-handoff.md) and [verification](qa/gap-closure/review-report.md). Earlier scope notes below describe the original delivery.

## Delivered workflows

The production React workspace implements the high-fidelity prototype's credential verification, attendance gate, section configuration and allocation flows. These screens use authenticated PostgreSQL-backed services rather than prototype localStorage.

| Task | Screen / behavior |
| --- | --- |
| ICI2-16 | `/enrollment/requirements`: scoped application lookup, completeness filters, all catalog credentials, four document states, verification notes, staff/time history, applicant/guardian follow-up contact details and conflict handling |
| ICI2-17 | `/enrollment/attendance`: per-date attendance verification, eligibility blockers, explicit confirmation and persisted enrolled standing |
| ICI2-18 | `/enrollment/sections`: section configuration, campus/term/program scope, grade level, room, adviser and capacity; optimistic edits and protected deletion |
| ICI2-19 | `/enrollment/enrolled`: confirmed student directory, district/transport filters, current capacity meters, section assignment/removal and server-side concurrency checks |

Admin and Registrar roles can use these workflows only in campuses where they hold an eligible grant. Other roles receive 403; inaccessible individual application, section and enrollment IDs receive 404. Lists exclude inaccessible campuses. All mutations set the authenticated audit actor; existing immutable audit triggers retain before/after records.

## Intake and duplicate-detection handoff

Application creation and explicit review are now integrated with Pauline’s ICI2-14 screens. The staff intake/review API feeds these verification/allocation services. Full duplicate resolution and existing-student linking remain ICI2-13 follow-up work. See [integration handoff](pauline-enrollment-integration.md) for the intake API and limitations. There are no production demo applicants or default credentials in the migration.

For an application to be confirmed, intake must provide:

- A numeric `applications.id`, campus, academic term, program and linked permanent `student_id`.
- `status='pre_enlisted'` after intake/review. Submitted, under-review, duplicate-flagged, rejected and withdrawn applications cannot be promoted here.
- An active linked student master record. Confirmation never manufactures student identities.
- No open duplicate case involving the application as either primary or matched application.

For allocation, intake must also populate `applications.profile.gradeLevel`. The section must match that grade; `11` and `Grade 11` are treated as equivalent. Missing grade level blocks assignment instead of guessing. The directory filters `profile.district` and `profile.transportRoute` using case-insensitive literal substring searches. Missing district/route values are displayed as unrecorded.

Intake/duplicate writers should lock the application row before changing confirmation-relevant state. Credential updates, attendance and confirmation share that lock. Application edits must increment `applications.version`; the new confirmation API requires the version returned by lookup/detail.

## API contract

All endpoints require a Bearer JWT. JSON uses camelCase names; existing database names remain unchanged.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/enrollment/reference-data` | Scoped active campuses, calendar terms, programs and active campus-qualified advisers |
| GET | `/api/enrollment/applications` | Paged application lookup, credential completeness and assignment summaries |
| GET | `/api/applications/{id}/enrollment` | Application version, checklist, per-date attendance, enrollment and confirmation blockers |
| PUT | `/api/applications/{id}/attendance/{YYYY-MM-DD}` | Save attendance status with the date row's version |
| POST | `/api/applications/{id}/confirm-enrollment` | Recheck eligibility and confirm the enrollment transactionally |
| GET | `/api/sections` | Paged sections with occupied and available seats |
| GET | `/api/sections/{id}` | One scoped section with current capacity |
| POST | `/api/sections` | Configure a section |
| PUT | `/api/sections/{id}` | Edit mutable section details with its version |
| DELETE | `/api/sections/{id}?version={version}` | Delete an unreferenced section |
| PUT | `/api/enrollments/{id}/section` | Assign, reassign or remove a section with enrollment version |

The checklist and audit endpoints remain documented in [requirement-tracking-api.md](requirement-tracking-api.md).

### Lookup and pagination

Application query parameters: `search`, `campusId`, `termId`, `programId`, `requirementsStatus` (`Complete`, `Incomplete`, `Pending`), application `status`, `district`, `transportRoute`, `page`, `size`. Section lists accept `campusId`, `termId`, `programId`, `page`, `size`. Responses are `{items,total,page,size}`; page is zero-based and size is 1 to 100 (default 25). Search covers application number, name, LRN and student number. No SQL wildcard interpretation is used.

### Attendance and confirmation

Attendance body: `{"status":"present","version":0}`. Allowed statuses: `present`, `absent`, `excused`, `pending`. Unrecorded dates start at virtual version 0; first saves create version 1. An identical save with the current version is a no-op. Future dates are rejected. Rejected/withdrawn applications are closed to attendance edits. Removing the last present attendance from a confirmed application is blocked.

Confirmation body: `{"version":0}` using the **application** version. It requires all mandatory credentials submitted, at least one recorded present orientation/first-week attendance, reviewed pre-enlisted standing, an active linked student and no open duplicate case. Absent and excused do not count as present. No arbitrary school week dates are invented: institution-specific scheduling remains a policy decision.

Confirmation inserts an enrollment or promotes an existing provisional one, stores attendance-verification/confirmation staff and timestamps, and changes the application to enrolled with its next version in one transaction. A repeated request with the refreshed current version is harmless; an old version returns 409. Existing enrollment uniqueness prevents a second enrollment for the same student and term. Document verification itself does not automatically enroll anyone.

### Section configuration and allocation

Section body:

```json
{
  "campusId": 1,
  "termId": 2,
  "programId": 1,
  "name": "STEM A",
  "gradeLevel": "11",
  "room": "Room 204",
  "capacity": 40,
  "adviserId": null
}
```

Edits also require `version`. Campus, term and program cannot be moved after creation; create a separate section instead. A referenced section's grade level cannot change. Capacity is 1-10,000 and cannot fall below occupied seats. A chosen adviser must be an active adviser in the same campus. Referenced sections are protected by foreign keys rather than cascading deletions.

Assignment body: `{"sectionId":12,"version":0}` using the **enrollment** version; use `sectionId:null` to remove an assignment. Only confirmed enrolled students may be allocated. Campus, term, program, applicant grade and available capacity must match. Enrollments with academic class memberships cannot move through this screen; that requires an academic transfer workflow preserving grades.

Transactions lock the application, enrollment and old/new sections in stable section-ID order. The capacity check and database enrollment trigger serialize concurrent reservations; two registrars cannot both take the last seat. Provisional and enrolled students count as occupied seats. Version conflicts, full sections, blocked confirmation and referenced deletion return 409. Invalid fields return 400. UI conflict messages retain the user's unsaved document notes and offer explicit reload; capacity can be refreshed before assigning and is rechecked on every write.

## Migration and verification

V7 adds version columns to applications, attendance, sections and enrollments and supporting indexes. V1-V6 remain unchanged. New migrations should use V9 or later after coordinating with other developers.

Run `./mvnw verify` in the backend and `npm test && npm run build` in the frontend. Backend integration coverage includes confirmation failure/rollback, persisted attribution, attendance validation, scope/role restrictions, section versions, protected history, grade/program matching, concurrent confirmation and last-seat races.

`tests/enrollment.spec.js` is an opt-in browser test that changes a dedicated disposable application. Supply `ICI_LIVE_API=1`, `ICI_ENROLLMENT_APPLICATION_ID`, `ICI_TEST_USERNAME`, `ICI_TEST_PASSWORD` and the backend's `ICI_API_URL`, then run `npx playwright test tests/enrollment.spec.js`. The fixture must be pre-enlisted with an active linked student and `profile.gradeLevel='Grade 11'`, with no existing enrollment or open duplicate case. The test verifies documents, attendance, confirmation, section creation, allocation, persistence after reload and mobile overflow. Do not point it at real school records.
