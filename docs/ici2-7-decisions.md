# ICI2-7 RBAC and workflow decisions

Status: Settled for the current backend phase

These decisions apply the ICI Local 2.0 paper to the existing V1–V5 database schema. The seven seeded roles and eight existing permission codes are the complete RBAC vocabulary for this phase. No migration, new role, new permission code, or attendance endpoint is included.

## Access decisions

| Topic | Decision |
| --- | --- |
| Administrator scope | Business access is campus-scoped. Staff and permission administration requires both `ROLE_ADMIN` and `users.manage`; grant management is restricted to campuses where the actor holds `ROLE_ADMIN`. Admin receives no business permission solely by role name. |
| Permission activation | `role_permissions` is active authorization data. The JWT filter reloads current active grants and permission mappings on every request. Existing tokens reflect changes on their next request. |
| Last Administrator | Disabling the final active Administrator or removing its final active-campused Administrator grant is rejected. `ROLE_ADMIN` must retain `users.manage`. |
| Registrar | `enrollment.manage` and campus grants authorize student/enrollment records, requirements, section and class administration, withdrawal intake, credential status, and setup APIs. `grades.review` authorizes grade review, corrections, incomplete cases, and finalization. `reports.read` authorizes operational reports. |
| Academic setup | Subjects, years, terms, and grading periods are global in the existing schema. They require `enrollment.manage`; they cannot be further scoped to a campus without a schema change. |
| Principal | `reports.read` plus a current `ROLE_PRINCIPAL` campus grant authorizes reports and withdrawal access. The Principal may record only the Principal clearance for their granted campus. |
| Adviser | `advisory.read` plus `sections.adviser_id` and campus scope permits assigned-section reads. Advisers cannot write student or withdrawal records. Advisory notes/flags are not added because no supporting schema is available. |
| Teacher | `grades.encode`, a current teacher grant at the campus, class assignment, and class membership are all required. The teacher can submit or update grades only while an existing matching grading window has a future non-null deadline and `locked_at IS NULL`. |
| Grade review | Registrar staff finalize a grading period and review corrections. Finalized grades cannot be teacher-edited. Correction requests require the assigned teacher, a justification and evidence storage key; a reviewer cannot approve their own request. |
| Guidance | `guidance.manage` and campus scope authorize at-risk results built from stored grade marks/values, mandatory requirement status, and latest orientation-attendance status. Returned fields expose the risk reasons and section; they do not calculate daily absences. |
| Cashiering | `cashier.read` is read-only. The roster and sanitized withdrawal reference are campus-scoped. Cashier staff cannot write clearance rows or verify payment balances. |
| Withdrawal clearances | Registrar staff record verified Adviser and Cashier clearances. The Principal records Principal approval/rejection. The withdrawal completes only when the three existing clearance rows are cleared. |
| Reports | Operational and at-risk endpoints return JSON and append an audit event identifying the actor, report, and filters. This phase does not render print/PDF/CSV files. |
| Student APIs | `/api/students` and `/api/setup` use `enrollment.manage`; Student API operations apply current campus grants to reads and final writes. Authentication, setup, and student API paths remain available to appropriately authorized staff. |

## Data availability decisions

- Enrollment confirmation relies on the existing database guard requiring submitted mandatory requirements and at least one present orientation-attendance record. The API reads these rows but does not create, edit, or delete attendance data.
- The risk report may identify the latest orientation status as absent. This does not mean that ongoing class attendance is tracked.
- No absence threshold, frequent-absence report, or attendance report is implemented. The existing schema only stores orientation attendance, and the user explicitly excluded an attendance API.
- Expired grading windows reject teacher writes, but no background job auto-finalizes them. Registrar staff explicitly finalize and lock the window through the API.
- Honor-student reporting is not implemented because the current schema has no honor criteria or designation data.
- Requirements and grade-correction evidence accept storage keys only. Document issue tracking accepts a storage key and optional Special Order number; there is no upload service, blob store, or PDF generator in this phase.
- The paper excludes a full cashiering/payment module. No balances, payment processing, or automated cashier clearance checks are claimed.
- No student self-service authentication or API is present in the current staff account schema.
- React workflow screens remain placeholders; the requested implementation phase covers backend APIs and authorization.

## Schema and migration policy

The system must use the existing `users`, `roles`, `permissions`, `role_permissions`, `user_role_grants`, student/application/requirement/orientation/enrollment, section/class/grade, withdrawal/clearance, document-request, and audit tables. If a future requirement needs new columns/tables (for example, ongoing attendance, advisory notes, configurable honor criteria, or file metadata), record it as unsupported until a separately approved migration is available. Do not emulate missing tables with untracked JSON or claim unsupported report data.
