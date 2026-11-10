# GitHub main audit: November 10, 2026

Audited GitHub `main` at **a62a677**, after fetching origin and running `git pull --ff-only origin main` in an isolated worktree at `/tmp/ici-upstream-audit-20261110`. That checkout reported Already up to date. The active project checkout remains at 2a82b07 with all unpublished files intact. No stash, reset, overwrite, merge, commit or push was performed. There is one local-only commit and 28 upstream commits beyond the common history, including merge history; these are not 28 new features.

New merged work since the previous integration: #12 RBAC (c0d04ad), #13 account management (2858e44), and #14 attendance frontend (567f8c6). #11 duplicate UI remains merged. The audit compares these with the unpublished integration without assuming either branch's tests certify the other branch.

## Findings

### P1: database migration versions collide when combined

Upstream V6 is `admin_enrollment_permission` and V7 is `user_token_version`. Local V6 is `requirement_verification_metadata` and V7 is `enrollment_workflow_versions`. Retaining both sets under the same numbers makes Flyway discover duplicate migration versions; replacing files on an already migrated database produces a history/checksum mismatch. Upstream expects `users.token_version`; local authentication uses `auth_version` and `password_change_required`. There are 28 overlapping local/upstream paths. A plain merge is not a runnable integration.

Preserve published upstream V1–V7. Move unpublished local migrations to a coordinated sequence after upstream V7, update tests/docs, and consolidate token invalidation. A local database already migrated through the unpublished sequence needs an explicit data-preserving migration plan or a separately authorized disposable rebuild. Do not repair checksums blindly or delete school data.

### P1: upstream attendance/confirmation is a demonstration, not persisted enrollment

[EnrollmentScreens.jsx lines 109–158](https://github.com/Eniloan4002/ISANDE1-Grp7/blob/a62a677/ICILocal2.0/frontend/src/enrollment/EnrollmentScreens.jsx#L109) stores attendance and confirmation in provider React state. A fresh browser audit created a real registrar account, changed Monday attendance for APP-2026-0148 from Pending to Present, reloaded, reopened the applicant and observed Pending. This reproduces loss of the recorded change. The upstream backend has no corresponding intake/requirement/attendance/confirmation/section controllers from the local integration.

[AttendanceScreen.jsx](https://github.com/Eniloan4002/ISANDE1-Grp7/blob/a62a677/ICILocal2.0/frontend/src/enrollment/AttendanceScreen.jsx#L5) fixes the dates to August 3–7, 2026 and requires all five days Present. Local implementation allows qualifying attendance inside separately configured orientation/first-week ranges. These different rules must be aligned with the ticket's approved acceptance criteria; the user's approximate schedule alone does not establish an all-five-days rule. Frontend PR #14 itself correctly describes the scope as frontend state only. Do not mark complete backend confirmation functionality Done on that evidence.

### P1: upstream duplicate screen still defaults to mock resolution

[api/duplicates.js lines 30–40](https://github.com/Eniloan4002/ISANDE1-Grp7/blob/a62a677/ICILocal2.0/frontend/src/api/duplicates.js#L30) selects an in-memory mock unless a live flag is enabled, and its proposed `/api/enrollment/duplicate-flags` service is absent from upstream. The local persisted `/api/duplicate-cases` integration is not published. Preserve the reconciled three decisions and local safety checks when combining; upstream REJECT means distinct people and must not be mapped to rejecting the incoming application.

### P2: upstream account updates silently overwrite newer edits

[AccountController.java lines 15–17](https://github.com/Eniloan4002/ISANDE1-Grp7/blob/a62a677/ICILocal2.0/backend/src/main/java/ph/edu/ici/accounts/AccountController.java#L15) has no expected version for edits/status/reset. [AccountService.java lines 98–102](https://github.com/Eniloan4002/ISANDE1-Grp7/blob/a62a677/ICILocal2.0/backend/src/main/java/ph/edu/ici/accounts/AccountService.java#L98) serializes writes but cannot detect an administrator's stale form. A dedicated reproduction saved a newer name, then submitted the older name with a changed role: both returned 200, and the older name replaced the new value. A transaction lock does not substitute for a client-supplied expected version. Keep/consolidate the local version checks rather than exposing an unversioned parallel update API.

Upstream account management otherwise has working creation/edit/status/reset, complete-target campus checks, last-admin guards, live permissions and token invalidation on reset/deactivation. Its browser journey passed. Local forced temporary-password replacement and self-service password change are absent upstream. Those are integration differences, not evidence that the collaborator's narrower delivered scope failed its own tests.

### P2: upstream schema/archive test suite is red

`npm run test:schema` reports 14 passed and one failed: `assets/js/app.js`. [archive.test.mjs line 17](https://github.com/Eniloan4002/ISANDE1-Grp7/blob/a62a677/ICILocal2.0/frontend/tests/archive.test.mjs#L17) requires the current prototype to equal the frozen archive even though the prototype has changed. Preserve the archive's recorded checksums/length checks, but remove the requirement that the evolving demo remain identical. The local integration already separates these concerns. This failure is not evidence that the frozen archive itself was corrupted.

## Validation

| Check on upstream | Result |
| --- | --- |
| Maven verify with real disposable PostgreSQL | 24 passed; zero failures/errors/skips |
| Frontend unit/component suite | 53 passed |
| Production frontend build | Passed |
| Schema/archive suite | 14 passed, 1 failed as described above |
| Browser: personnel-account creation/edit/deactivate/reactivate/reset | Passed |
| Browser: attendance-loss reproduction | Passed: confirms the undesirable reset to Pending |
| Additional backend account suite with stale-edit reproduction | 5 passed: four upstream tests plus one reproduction confirming silent overwrite |

Structured counts: [results.json](results.json). Filtered Maven summaries retain no test environment properties. The two audit reproductions were added only in the isolated worktree and do not fix the reproduced behavior. Browser ports were 5174/18082; the user's frontend/database was not restarted or modified. No actual reviewer accounts were provisioned on the user's database. Previous 59-backend/75-frontend/seven-browser results apply to the earlier local integration, not to this uncombined upstream revision.

## Consolidation order and Jira

1. Reconcile migration numbering and existing local database history before merging application code.
2. Integrate the permission/campus policy into all local enrollment endpoints; preserve both sides' scope/last-admin/optimistic-version checks. Consolidate the two account APIs and token-version mechanisms, retaining recovery/change requirements.
3. Adapt the new attendance presentation to persisted APIs and agreed confirmation policy, preserving campus/term configuration, duplicate gates and audit actors. Preserve the already reconciled duplicate/intake/checklist flows.
4. Correct the archive assertion without modifying the frozen archive. Repeat full backend/frontend/schema/build/browser checks on the combined revision.
5. Publish a reviewable shared revision, then run manual team acceptance.

Keep ICI2-13–19 For Review until the integrated shared revision passes. The UI-only portion of ICI2-14/16/17 can be acknowledged separately. ICI2-9 has new real upstream functionality, but must be reconciled with local recovery/versioning scope before the full combined feature is certified. ICI2-7's permission foundation passes its tests but does not supply unfinished academic/teacher-assignment business authorization. ICI2-49 remains dependent on each teammate actually setting up/verifying their own accounts. Preserve collaborator assignments/credit. No Jira statuses were changed.

The previous login failure remains a bootstrap-configuration validation failure in the user's local run; these upstream merges do not provision built-in `linus_admin`, `voa_admin` or registrar accounts, and were not used to overwrite that runtime.
