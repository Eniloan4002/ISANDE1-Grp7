# RBAC integration guide

The database is the source of role codes, permission codes, campus grants, and role-permission mappings. Use the seven seeded roles and eight existing permission codes; do not create a parallel role registry or add permission codes without a schema decision.

## Request authorization

1. `POST /api/auth/login` verifies credentials and issues a signed JWT.
2. The JWT filter checks the token, current account state, and revocation list. On every request it reloads the caller's active campus grants and joins those grants to the current `role_permissions` rows. A role or permission change applies on the next request with the same token.
3. Spring Security authenticates every protected request. Method-level rules check the current role or permission authority. Services use `AccessScope` to get campus IDs for a permission and enforce those limits in both reads and final write statements.
4. Resource assignment rules are checked in addition to permissions: teachers must match `class_offerings.teacher_id` and class membership; advisers must match `sections.adviser_id`; Principal clearance requires a Principal grant at that campus.

The account and permission APIs require both `ROLE_ADMIN` and `users.manage`. They restrict grant changes to campuses where the acting administrator has an active Administrator grant, accept only existing role/permission codes, preserve at least one active Administrator, and require `ROLE_ADMIN` to retain `users.manage`.

## Existing permission and role matrix

| Permission | Seeded role | Main API use |
| --- | --- | --- |
| `users.manage` | `ROLE_ADMIN` | Staff accounts and role-permission mappings |
| `enrollment.manage` | `ROLE_REGISTRAR` | Student and setup APIs; enrollment, section, class, withdrawal, and document workflows |
| `grades.encode` | `ROLE_TEACHER` | Assigned-class roster and grade entry/correction requests |
| `grades.review` | `ROLE_REGISTRAR` | Grade review, correction decisions, incomplete cases, and period finalization |
| `advisory.read` | `ROLE_ADVISER` | Read rosters for assigned advisory sections |
| `reports.read` | `ROLE_REGISTRAR`, `ROLE_PRINCIPAL` | Operational reports at granted campuses |
| `guidance.manage` | `ROLE_GUIDANCE` | At-risk reports based on available academic, requirement, and orientation data |
| `cashier.read` | `ROLE_CASHIER_REF` | Read-only cashier reference roster and sanitized withdrawal list |

The Principal withdrawal approval is checked by the existing `ROLE_PRINCIPAL` authority and campus grant because the schema has no withdrawal-specific permission. Registrar staff record the Adviser and Cashier verification details. The Cashier Reference role remains read-only.

`/api/setup` and `/api/students` use the live `enrollment.manage` permission. Student queries are scoped by current campus grants. Setup calendar and subject records are institution-wide because the existing schema does not associate them with a campus.

## Current workflow APIs

| API area | Main operations | Scope |
| --- | --- | --- |
| `/api/admin` | List/create/update/deactivate staff, passwords, grants, and role permissions | Administrator role plus `users.manage`; grants limited to actor's Administrator campuses |
| `/api/enrollment` | Applications, duplicate cases, requirements, confirmation, sections, and student-section assignment | `enrollment.manage` and granted campuses |
| `/api/academics` | Classes, teacher assignment, rosters, grades, corrections, incomplete cases, and finalization | Registrar permission for management/review; teachers limited to assigned classes and open grading windows; advisers limited to assigned sections |
| `/api/withdrawals` | Request, list, and track clearances | Registrar at granted campuses; Principal can view and record their own clearance; Cashier list is sanitized and read-only |
| `/api/cashiering/roster` | Enrollment reference roster | `cashier.read` and granted campuses |
| `/api/documents` | Request, approve, and issue status | `enrollment.manage` and student campus |
| `/api/reports` | Enrollment, student, missing requirements, incomplete grades, documents, and at-risk JSON | `reports.read` or `guidance.manage`, with campus filter enforcement and audit event |

Confirmation uses existing requirements and orientation-attendance rows. There is no attendance write API. Teacher grade entry requires a matching existing grading window with a non-null future deadline and no manual lock. Operational report generation is JSON; PDF/export rendering and file storage are not part of this backend phase.

## Adding or changing a module

- Put a live permission or role rule on each controller operation. Use the existing seeded codes that match the action; do not infer rights from a role name when the permission table has a corresponding code.
- Use `AccessScope` for campus checks. Do not trust campus IDs supplied by a request. Include campus and assignment predicates in the final SQL write within a transaction.
- Check the assignment columns already in the schema: `sections.adviser_id`, `class_offerings.teacher_id`, and `class_memberships`. For teacher submissions, use `grading_windows` and reject absent, null-deadline, expired, or locked windows.
- Preserve the current staff authentication and require authorization on every backend route even when frontend navigation is hidden.
- Test 401 behavior, current permissions, a valid token with denied authority, another campus and assignment, unchanged data after denied writes, and live role/permission changes with an existing token.
- Do not add a migration, permission, role, attendance endpoint, or unsupported report rule to satisfy a paper requirement. Record unsupported requirements in [rbac-implementation-log.md](rbac-implementation-log.md).
