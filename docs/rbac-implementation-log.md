# RBAC and paper-workflow implementation log

Updated: 2026-11-05

## Scope and constraints

This log records the RBAC foundation already present before this phase and the backend work completed in this phase. The implementation uses the existing V1–V5 schema and the paper's enrollment, academic-record, withdrawal, credential, reporting, and access-management workflows. It adds no database migration, role, permission code, or attendance endpoint. Frontend workflow routes remain placeholders.

The paper is the requirements source; migration-defined tables are the implementation boundary. This work does not connect to or claim to verify a live institutional database.

## Earlier RBAC foundation

The ICI2-7 foundation already provided:

- Staff login, JWT authentication, logout revocation, `StaffIdentity`, and campus-role grants for the seven seeded roles.
- Authentication checks on protected routes, live account/grant refresh, initial role restrictions for Setup and Student APIs, and student-campus isolation.
- React role recognition and route guards, plus backend/frontend/schema tests and RBAC integration/decision documentation.
- A documented decision to keep the existing roles and schema, defer unapproved workflow rules, and avoid implementing empty endpoints or expanding the schema.

At that point, `role_permissions` existed as seeded metadata but did not create Spring authorities. Setup and Student APIs authorized by role. The previous implementation and verification record is described in the ICI2-7 rollout history; this phase supersedes its unresolved permission-activation decisions.

## Changes in this phase

### Live permission authorization

- The JWT filter reloads active campus grants and the current `role_permissions` mapping on each request. Role and permission edits therefore affect already-issued tokens on their next request.
- `AccessScope` derives authorized campuses from the current grants, active campuses, and requested existing permission code. Business services also apply those campus limits in record queries and writes.
- Existing `/api/students` and `/api/setup` access now uses `enrollment.manage`. Student reads and final writes use current permission-scoped campus grants. Academic setup is global because academic years, terms, periods, and subjects have no campus key in the current schema.

### Staff and permission administration

`/api/admin` provides account list/detail/create/update/deactivate/password/grant operations and read/update operations for role-permission mappings. It requires `ROLE_ADMIN` and `users.manage`. A manager may administer grants only at campuses where they hold an active `ROLE_ADMIN` grant. Only existing roles and permission codes are accepted. The last active administrator with a grant at an active campus cannot be disabled or removed, and `ROLE_ADMIN` must keep `users.manage`.

### Enrollment and academic workflows

- `/api/enrollment` manages applications linked to existing student profiles, duplicate cases, requirement verification, application status, enrollment confirmation, section records, capacity-aware section assignment, and class-section membership updates.
- Enrollment confirmation relies on the existing database guards: mandatory requirements must be submitted and a `present` orientation record must already exist. This API does not create or edit orientation-attendance records.
- `/api/academics` manages class offerings and teacher assignment, assigned-class rosters and grades, assigned-section adviser reads, grade corrections, incomplete-grade cases, and registrar grade-period finalization.
- Teacher grade writes require the teacher's current assignment, a matching class membership, and an existing grading window whose deadline is non-null and in the future and whose `locked_at` is null. Missing, null-deadline, expired, and locked windows reject writes. Grade corrections are requests against finalized grades; the assigned teacher cannot approve their own request.

### Withdrawal, credential, and report workflows

- `/api/withdrawals` supports registrar intake and tracking. A Principal records only the Principal clearance for their granted campus; Registrar staff record verified Adviser and Cashier clearance information. Cashier Reference has no write access. A withdrawal completes only after all three existing clearance rows are cleared.
- `/api/cashiering/roster` returns a read-only, campus-scoped reference roster. The cashier-facing withdrawal list omits the reason and actor notes.
- `/api/documents` supports requests, approval, and issue-status tracking using existing requirement, enrollment, and grade data. Issue records accept a storage key and optional special-order number; they do not generate or store a PDF.
- `/api/reports` provides enrollment, student-record, missing-requirement, incomplete-grade, document-request, and at-risk JSON results. Each generated report is recorded in `audit_logs` with the actor and filters.
- At-risk results can use stored incomplete/low grades, incomplete mandatory requirements, and the latest stored orientation status. They do not calculate ongoing absence counts.

## Settled role boundaries

| Role | Existing authority | Access in this implementation |
| --- | --- | --- |
| System Administrator | `users.manage` plus `ROLE_ADMIN` | Staff and role-permission administration within explicit administrator campus grants. Other business permissions must be present in the live mapping. |
| Registrar Staff | `enrollment.manage`, `grades.review`, `reports.read` | Manage student/enrollment records, requirements, sections, class assignments, documents, withdrawals, grade review/finalization, and operational reports at granted campuses. Records Adviser/Cashier withdrawal clearance confirmations. |
| Principal | `reports.read` plus `ROLE_PRINCIPAL` | Read operational reports at granted campuses, view withdrawal cases, and record only their own withdrawal approval/rejection. |
| Teacher Adviser | `advisory.read` | Read students and status information from sections assigned to that adviser at granted campuses. No workflow or student-record writes. |
| Subject Teacher | `grades.encode` | Read assigned class rosters and submit or update assigned-class grades while a matching grading window is open; request corrections after finalization. |
| Guidance Staff | `guidance.manage` | Read risk results derived from available records at granted campuses. |
| Cashiering Reference | `cashier.read` | Read the campus-scoped cashier reference roster and sanitized withdrawal list; no payment, clearance, or record writes. |

All data access remains campus-scoped. Teacher access additionally checks `class_offerings.teacher_id` and class membership. Adviser access checks `sections.adviser_id`. `/api/admin` requires both the Administrator role and the live `users.manage` permission. The permission mappings are global because the existing `role_permissions` table has no campus column.

## Paper requirements not implemented

These items are left unimplemented because their required data or integration is not present in the existing schema, or the user explicitly excluded them:

- Ongoing class attendance, attendance entry APIs, and absence counts or thresholds. Only existing orientation attendance may be read; one absent orientation record can be reported as `orientation_absent`.
- Background auto-finalization of grading windows. An expired window rejects teacher writes; Registrar staff explicitly finalize and lock it through the API.
- Attendance and honor-student operational reports. There is no ongoing attendance history or honor-student criteria table to support defensible results.
- Student-configurable at-risk rules based on a number of absences, and risk filtering by section/adviser/type. The current endpoint supports term, grading period, grade threshold, and campus filters and returns section/risk fields from existing records.
- Actual document upload, PDF/TOR/Form 137 rendering, print/export, or blob storage. Requirement, correction, and issued-document storage fields hold external storage keys only.
- Automated cashiering balance/payment verification, payment processing, and accounting. The paper excludes a full cashiering module and the current schema does not contain those records.
- Student self-service login and student-submitted withdrawal/document requests. The current authentication and workflow schema is staff-oriented.
- React workflow screens. The approved scope for this phase is backend APIs and authorization.

No changes were made to Flyway migration files, and no attendance route was added.

## Verification

Integration coverage exercises unauthenticated access, live permission changes with an existing token, last-administrator protection, campus isolation and unchanged records after denied writes, adviser/class assignment scoping, Registrar/Principal/Cashier withdrawal boundaries, missing-requirement reports before enrollment, report audit attribution, and grade-window rejection for missing, null, expired, and locked windows. Final verification: `./mvnw test` passed all 45 backend integration tests against embedded PostgreSQL 17.6; Flyway validated the existing five migrations and made no schema changes.
