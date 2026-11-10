# Staff sign-in and account provisioning

Current follow-up: identity resolution, existing-student linking, account editing/recovery and attendance configuration are implemented locally. See [gap closure handoff](gap-closure-handoff.md) and [verification](qa/gap-closure/review-report.md). Earlier scope notes below describe the original delivery.

November 9, 2026. Local implementation; not yet pushed or deployed.

## Findings and fixes

1. The startup guide bootstrapped only an administrator. The historical registrar/principal/adviser/teacher/guidance/cashier demo accounts belonged to a disposable preview database and were not installed on teammates’ computers. The guide now explains this, and the administrator's User Accounts screen can create real staff accounts with chosen credentials and explicit campus grants.
2. The sign-in handler could navigate to a saved administrator-only URL before checking the newly authenticated user's role. It now checks the actual returned grants and chooses an allowed destination, preserving permitted deep links and sending other users to their role's landing screen.
3. Unimplemented routes could be mistaken for failed login. Their message now explicitly says that the user is signed in and the workflow is still under development. This does not implement the missing academic/guidance/reporting business services.

## How the team uses it

- Run the updated integrated code, PostgreSQL, backend and frontend using [the startup guide](run-complete-app.md).
- Bootstrap/sign in as administrator, open **User Accounts**, and create each person's username, name, email, initial password, role and campus access. At least one campus is required. Use separate accounts rather than sharing the administrator login.
- Sign out, then sign in with the newly created credentials. Login is case-insensitive for usernames. Deactivated accounts cannot sign in, and the API rejects their existing sessions while inactive.
- The directory supports search, pagination and activation/deactivation. Passwords and hashes are never returned to the browser; a successful create clears the password input. The administrator cannot deactivate their own account. Account names/emails must be unique.
- Administrators can provision only campuses where they hold an administrator grant. Accounts with grants outside that scope are excluded from their directory/status operations. Non-administrators cannot use the provisioning API.

## Expected destinations

| Role | Landing screen when no permitted return URL is saved |
| --- | --- |
| System Administrator | Academic setup |
| Registrar | Enrollment Queue |
| Principal | Academic History |
| Teacher Adviser | Advisory Class |
| Subject Teacher | Grade Entry |
| Guidance | Guidance Records |
| Cashiering Reference | Enrolled Student Reference |

An allowed saved URL takes precedence. For example, a newly signed-in administrator may return to User Accounts, while a teacher requesting that same page is sent to Grade Entry. Prospective-student application submission is separate from the seven staff authentication roles; no `ROLE_STUDENT` was added.

## API

All endpoints require `ROLE_ADMIN` and enforce the administrator's campus scope:

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/admin/accounts/reference-data` | Allowed active campuses and seven staff roles |
| GET | `/api/admin/accounts?search=&page=0` | Scoped directory, 25 accounts per page |
| POST | `/api/admin/accounts` | Create an active account and role/campus grants atomically |
| PUT | `/api/admin/accounts/{id}/status` | Activate/deactivate using an opaque version from the directory |

Creation takes `username`, `fullName`, `email`, `password`, `role` and numeric `campusIds`. Passwords require at least 12 characters and no more than 72 UTF-8 bytes, and are BCrypt-hashed. Status updates take `active` and `version`. No new migration or default/shared password was introduced. Existing-user role editing, password change/reset/recovery and full module RBAC remain separate tasks; this is the provisioning/status portion of ICI2-9.

## Tests and collaborator handoff

Backend coverage provisions accounts for all seven roles, verifies real BCrypt sign-in and live campus grants, checks non-admin and cross-campus rejection, validates passwords, hides hashes, blocks self-deactivation, and checks stale status updates and inactive sessions. Frontend coverage checks provisioning payloads/password clearing, API failure handling and the teacher's saved-admin-URL regression. `tests/staff-roles.spec.js` covers UI account creation and browser sign-in/reload for all seven roles, including role-safe redirects.

Final verification passed: **49 backend integration tests, 66 frontend unit tests, 15 schema/archive checks, production build, and both real Chromium journeys** (complete enrollment integration and all seven staff roles). The browser account test created a registrar through the User Accounts form, provisioned the other roles through the same API, checked their role-safe saved-URL redirects, reloaded each session, and checked mobile overflow. All account and enrollment browser data lived only in a disposable PostgreSQL database.

This adds narrowly scoped account provisioning and fixes auth routing without reinstating Anton's reverted RBAC/workflow PR. His broader module-permission work remains open. Pauline's intake/requirements integration remains in place; Elijah's prototype files were not changed.

## GC-ready note

> Additional login fix: ang original startup guide kay admin ra ang gi-create; ang old demo accounts kay naa ra sa previous temporary database. Added User Accounts para maka-create ang admin ug real staff logins with role/campus access, plus activate/deactivate controls. Gi-fix pud ang redirect nga usahay moadto ang non-admin sa saved admin-only page after login. Clear na pud ang message kung successful ang login pero under development pa ang module. Wala gi-reinstate ang reverted RBAC PR; full module permissions, existing-user role editing ug password recovery remain pending. Local pa ang changes for review, wala pa gi-push.
