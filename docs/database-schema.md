# Database foundation (ICI2-5)

Current November 10 reconciliation: published V1–V7 plus local V8–V11; live permission checks, one versioned account API, and persisted enrollment/attendance. See [migration reconciliation](migration-reconciliation.md) and [demo/manual pass](demo-13-19-checklist.md). Earlier delivery notes below retain their historical version references.

Implemented PostgreSQL 17 foundation, with versioned Flyway migrations V1-V8 in ICILocal2.0/backend/src/main/resources/db/migration/. See verification.md for executed checks.

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
- Campus-scoped staff permissions require explicit grants per campus. No default staff accounts, passwords or student data are seeded; first-administrator creation is explicit and opt-in. Authorization is not implemented by these tables alone.
- Composite foreign keys prevent cross-campus/program/term section assignments and cross-term grade periods. Class memberships bind enrollment to the correct section.
- Provisional and enrolled records reserve section seats. A trigger locks the target section before counting occupancy. Capacity reductions below occupancy are rejected. Future APIs must use READ COMMITTED transactions, deterministic lock ordering for transfers and deadlock retries. Embedded SQL tests do not prove concurrent multi-connection behavior.
- Confirmation requires the same student as the linked application, all mandatory credentials submitted, a recorded orientation presence and explicit staff verification stamps. One present entry plus staff verification is an initial gate, not an approved first-week attendance policy. Confirm that policy with ICI.
- Numeric grades use numeric(5,2) within 0-100. Nonnumeric INC, NG and DRP are separate from numeric values. Draft grades can be empty; submitted/finalized grades require exactly one numeric value or mark. Confirm mark codes with the registrar.
- Evidence files use storage keys. JSONB holds address and intake extensions; core identity and relational fields remain normalized.
- V3 adds created_at, updated_at, created_by and updated_by to all 33 business/reference tables, with database-managed metadata and old/new audit snapshots. Password hashes are excluded from user snapshots. Authenticated setup writes set SET LOCAL app.actor_id transactionally from the verified principal. Null actors represent reference seeds/controlled bootstrap imports. audit_logs and revoked_tokens are event tables with their own event time and actor/user fields; Flyway owns its history table.
- Audit records reject UPDATE, DELETE and TRUNCATE. Use a separate runtime database user without DDL or audit-mutation rights; table owners can disable triggers. Docker owner credentials are for local development only.
- Honor eligibility is deferred at the user's request. Archived honor thresholds are illustrative and not approved policy.

## Service work still required

Spring Security/JWT, BCrypt, durable logout and the protected academic setup route/API are implemented. Campus/teacher-specific module authorization and the remaining student/grade business APIs are still separate work. No live student records are exposed by the shell.

Transactional services must implement grade deadline locks, approved correction application, optimistic grade versions, INC resolution, document release prerequisites, withdrawal office approvals and synchronized application/enrollment states. The schema stores their metadata but does not enforce staff-role authorization or certify document release. Application/student reassignment after enrollment must be restricted to a controlled merge workflow.

Also outstanding: institution-specific requirement catalogs, assessment-component storage/calculations, sensitive guidance-note access and retention, legacy row mappings/provenance and reconciliation reporting. Import tables provide initial batch tracking only.

## Migration workflow

Use new V9__description.sql and later migrations once this baseline is shared. Never edit previously applied migrations or use Hibernate ddl-auto=update. Flyway validates checksums at startup. Do not manually apply SQL against a Flyway-managed database.

Tests apply all eight migrations in PGlite and in real native PostgreSQL 17.6. The backend suite verifies clean startup, V2-to-V8 upgrade, Flyway checksum validation/restart, attributed audits and simultaneous section reservations. Docker itself returned a daemon error in the verification environment; native PostgreSQL supplied the real database checks.

Sources: [PostgreSQL constraints](https://www.postgresql.org/docs/current/ddl-constraints.html), [Flyway naming](https://www.red-gate.com/blog/database-devops/flyway-naming-patterns-matter/).

## Student master extension (V5)

Student profiles now carry current campus/program/voucher/track preferences, previous-school information, household income bracket, optimistic version and active/archive state. Existing rows without campus ownership remain hidden until verified. See student-master-api.md. No earlier migration was rewritten.

## Requirement tracking extension (V6)

application_requirements now has optimistic version, notes and verified_at fields; existing metadata/audit triggers capture them. The authenticated per-application API computes mandatory completeness and exposes scoped history. See requirement-tracking-api.md for exact states, unrecorded defaults, no-op semantics and authorization. No earlier migration was edited.

## Enrollment workflow extension (V7)

Applications, orientation attendance, sections and enrollments now have optimistic versions. Confirmation and section assignment reuse existing identity, attendance, enrollment and capacity constraints. See [enrollment-workflows.md](enrollment-workflows.md).

## Catalog display labels (V8)

V8 replaces decorative punctuation in the default Bugo campus and TVL program names. It preserves IDs, references, institution-customized names and all released migration checksums.
