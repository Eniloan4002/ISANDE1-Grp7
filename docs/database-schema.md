# Database foundation (ICI2-5)

Implemented PostgreSQL 17 foundation, with versioned Flyway migrations V1–V5 in ICILocal2.0/backend/src/main/resources/db/migration/. See verification.md for executed checks.

## Entity map

| Domain | Tables |
| --- | --- |
| Institution | campuses, academic_years, academic_terms, grading_periods, programs, sections |
| Access | users, roles, permissions, role_permissions, user_role_grants |
| Identity | students, guardians |
| Intake | applications, duplicate_cases, vouchers |
| Verification | requirement_types, application_requirements, orientation_attendance |
| Enrollment | enrollments |
| Academics | subjects, class_offerings, class_memberships, grading_windows, grades |
| Grade workflows | grade_corrections, incomplete_cases |
| Student support | withdrawals, withdrawal_clearances, student_notes |
| Reporting | document_requests |
| Cutover | import_batches, import_exceptions |
| Traceability | audit_logs |
| Authentication | revoked_tokens |

## Decisions and invariants

- Student identity is permanent. Applications capture intake snapshots; enrollments relate a student to a term. Student LRNs are nullable but unique and exactly 12 digits when present. Applicant LRNs are indexed but deliberately not unique: duplicates must enter a review queue.
- One enrollment per student per term, with one program. Confirm concurrent-program policy before changing this constraint. SHS and TVET are in scope; the archived college example is not added as a supported program.
- Campus-scoped staff permissions require explicit grants per campus. `role_permissions` contains the current permission mapping; the backend reloads grants and mapped authorities per request and applies campus/resource checks in services. No default staff accounts, passwords or student data are seeded; first-administrator creation is explicit and opt-in.
- Composite foreign keys prevent cross-campus/program/term section assignments and cross-term grade periods. Class memberships bind enrollment to the correct section.
- Provisional and enrolled records reserve section seats. A trigger locks the target section before counting occupancy. Capacity reductions below occupancy are rejected. Future APIs must use READ COMMITTED transactions, deterministic lock ordering for transfers and deadlock retries. Embedded SQL tests do not prove concurrent multi-connection behavior.
- Confirmation requires the same student as the linked application, all mandatory credentials submitted, a recorded orientation presence and explicit staff verification stamps. One present entry plus staff verification is an initial gate, not an approved first-week attendance policy. Confirm that policy with ICI.
- Numeric grades use numeric(5,2) within 0–100. Nonnumeric INC, NG and DRP are separate from numeric values. Draft grades can be empty; submitted/finalized grades require exactly one numeric value or mark. Confirm mark codes with the registrar.
- Evidence files use storage keys. JSONB holds address and intake extensions; core identity and relational fields remain normalized.
- V3 adds created_at, updated_at, created_by and updated_by to all 33 business/reference tables, with database-managed metadata and old/new audit snapshots. Password hashes are excluded from user snapshots. Authenticated setup writes set SET LOCAL app.actor_id transactionally from the verified principal. Null actors represent reference seeds/controlled bootstrap imports. audit_logs and revoked_tokens are event tables with their own event time and actor/user fields; Flyway owns its history table.
- Audit records reject UPDATE, DELETE and TRUNCATE. Use a separate runtime database user without DDL or audit-mutation rights; table owners can disable triggers. Docker owner credentials are for local development only.
- Honor eligibility is deferred at the user's request. Archived honor thresholds are illustrative and not approved policy.

## Service and workflow status

The backend implements campus-scoped staff authorization and the paper workflows supported by the current tables: student/application records, duplicate cases, requirements, confirmation, sections and class assignment, grades, corrections, incomplete cases, withdrawals and clearances, credential request status, operational reports, and staff/permission administration. Confirmation still depends on existing mandatory requirement and orientation-presence guards. Teacher grade writes require an existing open grading window; expired or locked windows reject writes.

The current schema does not define every institution-specific policy. Ongoing attendance, honor-student criteria, advisory notes, payment balances, file storage, and configurable reporting criteria remain unsupported. Document approval checks available requirements and academic records; the API tracks an external storage key and does not certify or generate a PDF credential. See [rbac-implementation-log.md](rbac-implementation-log.md) for the exact backend scope and limitations.

Application/student reassignment after enrollment remains restricted to a controlled merge workflow. Legacy row mappings/provenance and reconciliation reporting remain future work; import tables provide initial batch tracking only.

## Migration workflow

Use new V6__description.sql and later migrations once this baseline is shared. Never edit previously applied migrations or use Hibernate ddl-auto=update. Flyway validates checksums at startup. Do not manually apply SQL against a Flyway-managed database.

Tests apply all five migrations in PGlite and in real native PostgreSQL 17.6. The backend suite verifies clean startup, V2-to-V5 upgrade, Flyway checksum validation/restart, attributed audits and simultaneous section reservations. Docker itself returned a daemon error in the verification environment; native PostgreSQL supplied the real database checks.

Sources: [PostgreSQL constraints](https://www.postgresql.org/docs/current/ddl-constraints.html), [Flyway naming](https://www.red-gate.com/blog/database-devops/flyway-naming-patterns-matter/).

## Student master extension (V5)

Student profiles now carry current campus/program/voucher/track preferences, previous-school information, household income bracket, optimistic version and active/archive state. Existing rows without campus ownership remain hidden until verified. See student-master-api.md. No earlier migration was rewritten.
