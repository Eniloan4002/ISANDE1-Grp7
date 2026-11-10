# Identity resolution, account security and reviewer setup

Current November 10 reconciliation: published V1–V7 plus local V8–V11; live permission checks, one versioned account API, and persisted enrollment/attendance. See [migration reconciliation](migration-reconciliation.md) and [demo/manual pass](demo-13-19-checklist.md). Earlier delivery notes below retain their historical version references.

November 9, 2026. Local implementation, not committed/pushed/deployed. Supersedes the missing-functionality findings in the earlier QA snapshot; the historical QA evidence remains intact.

For the subsequent Elijah PR #11 connection, see [integration notes](pr-11-duplicate-integration.md).

## Registrar: duplicate records

Open **Duplicate Records** or **Review duplicate checks** from an applicant's details. For an older flagged application with no persisted cases, click **Run duplicate check for this application**. New intake automatically persists cases.

The comparison shows the incoming and matched application/student, identifiers, DOB, campus, standing, match evidence and decision history. A reason and explicit confirmation are required. Versions protect against stale/concurrent decisions. A reviewer must have eligible grants for both sides; cross-campus records are not exposed to a single-campus reviewer.

- **Same person: link to matched student** reuses the verified active master identity. It retains original records and academic history, resolves the applicable cases as merged, then permits explicit intake review/pre-enlistment when other cases are clear.
- **Distinct people** records the human adjudication and its reciprocal application decision, so later canonical student creation does not re-flag the same approved distinction. Exact shared permanent LRNs cannot be cleared as distinct: correct the identifier or retain the canonical record.
- **Reject incoming duplicate** preserves the rejected application/audit trail and retains the matched canonical record. When the canonical application has no student yet, reject the incoming duplicate and explicitly review the canonical application rather than manufacturing another identity.

The workflow resolves applicant identities by canonical linking; it does not delete master records or rewrite academic histories. An already enrolled/reviewed application cannot have its identity replaced here. A student already enrolled/reviewed in the same term must use that canonical application. Archived masters must be reactivated through the existing authorized student-record process before linking.

## Registrar: returning and continuing applicants

Capture the application normally, open its details, and use **Link existing student**. Search by permanent student number, name or LRN, select the verified identity, check DOB/identifiers, and supply the verification reason. Both the application and selected student version are checked.

Linking does not copy/overwrite the master profile or grades. It resolves cases that point to the chosen identity; other open cases still block progress. When checks are clear, explicitly **Review and pre-enlist**, then follow the same credential/attendance/confirmation/allocation gates as new intake. Cross-campus linking requires authority over both the application campus and canonical student campus; the master home campus is retained.

## Administrator: account access and password recovery

In **User Accounts**, use **Edit** for staff name/email and one or more role/campus grants. A change reason is required. The administrator must control the complete target account scope and every requested campus. Self-editing of administrative access is blocked; another administrator must handle it. Existing sessions end after access edits.

Use **Recover** after verifying the staff member's identity through the school's process. Enter a chosen temporary password, attest the identity check, and record a reason without putting the password into that note. Share the password privately. The temporary login is restricted to **Account Security** until the user replaces it. Password change/reset, role edits and deactivation invalidate old sessions through the account's authentication version; reactivation does not revive old tokens.

Every signed-in staff member can use **Account Security** to change their own password with the current password and matching new-password confirmation. Old sessions end after success and the user signs in again. BCrypt hashes and password values are not returned in account responses or audit snapshots. Existing password length rules (12 characters minimum, 72 UTF-8 bytes maximum) remain enforced.

Recovery is administrator-mediated. No email reset provider, default password, or public account-enumeration endpoint was invented.

## Attendance policy: separate campus schedules

In an applicant's Attendance screen, expand **Approved orientation / first-week dates** and enter the approved dates for that specific **campus and academic term**, plus an approval reference/reason.

- The first date pair is the approved first-week class-attendance range.
- The optional second pair is the orientation-event range.
- Orientation is usually roughly two weeks before classes; first-week classes are usually in August's first week, as described by the user. Campus schedules can differ. Exact year-specific dates are not prefilled or inferred.
- Only dates inside either configured range qualify; the intervening gap does not qualify. Both orientation dates must be supplied together or both left blank.
- At least one Present record within the allowed dates is still required, along with mandatory credentials and reviewed identity. Absent/Excused do not satisfy the gate.
- Changes that would invalidate confirmed historical attendance are rejected. Policy updates are versioned, campus-scoped and audited, and serialize with attendance/confirmation.
- Until staff configure a window, the existing Present-attendance gate remains active and the screen explicitly identifies the unconfigured calendar. The app does not claim that an unconfigured campus satisfies an institution-approved date policy.

## Per-teammate reviewer accounts

Each teammate runs the updated app and creates their own bootstrap administrator using the [startup guide](run-complete-app.md). For persistent accounts, use the Docker database/volume; the native disposable launcher intentionally loses accounts when stopped.

They can create the six other staff roles in **User Accounts**, choosing passwords privately. Alternatively, from the repository root, use Python 3:

```sh
python3 scripts/provision-review-accounts.py --base-url http://127.0.0.1:8081 --campus-code ILIGAN --prefix review
```

Windows PowerShell can use `py -3` instead of `python3`. To grant multiple campuses, repeat the flags for the intended authorized scope:

```sh
python3 scripts/provision-review-accounts.py --base-url http://127.0.0.1:8081 --campus-code ILIGAN --campus-code KAPATAGAN --campus-code BUGO --prefix review
```

The script prompts for the administrator credentials and a chosen password for each role. It verifies the current administrator plus Registrar, Principal, Adviser, Teacher, Guidance and Cashiering Reference. It prints usernames/results, never passwords or tokens, and logs out its verification sessions. New preview email addresses use example.invalid because this is reviewer provisioning, not an email-delivery workflow.

Rerunning checks existing accounts without overwriting their passwords/grants. To prohibit creation entirely, add `--verify-only`. Role/campus mismatches, inactive accounts, required password changes and failed sign-ins stop verification for staff review. Remote backends require HTTPS; plain HTTP is accepted only on localhost. No persistent target database is provisioned automatically by Codex because the user chose per-teammate setup documentation.

## API changes

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/duplicate-cases?status=open&applicationId=&page=0` | Scoped review queue; out-of-scope matches are redacted |
| GET | `/api/duplicate-cases/{id}` | Authorized comparison and last 50 audit events |
| POST | `/api/duplicate-cases/{id}/resolve` | Versioned same_person / distinct / reject decision with reason |
| POST | `/api/intake/applications/{id}/duplicates/scan` | Persist/recheck older application's candidate cases |
| POST | `/api/intake/applications/{id}/link-student` | Verified existing identity link with application/student versions and reason |
| PUT | `/api/admin/accounts/{id}` | Staff details and multi-grant edit, version and reason |
| POST | `/api/admin/accounts/{id}/reset-password` | Verified administrator recovery with temporary password |
| POST | `/api/auth/change-password` | Current-password verification and personal password change |
| GET/PUT | `/api/enrollment/attendance-window?campusId=&termId=` | Scoped first-week/orientation ranges, version and approval reference |

## Migration and collaboration

Draft **V9__identity_resolution_and_account_security.sql** adds case versions, account session/password-change controls and audited campus-term attendance windows. It supplies no accounts, passwords or institution dates. V1-V8 and the frozen archive were not rewritten. V9 must ship with the application changes; it is not safe to deploy only the frontend. Remote main was checked at `44eb069` during this work and had not advanced; coordinate migration numbering before any later parallel team merge.

Pauline's queue/intake/requirements structure is retained; existing-student linkage and duplicate navigation extend it. Elijah's matcher rules are preserved and now feed persisted backend cases; the scanner also handles legacy application identifiers that differ from their linked master and deduplicates candidates by canonical identity. Anton's reverted broad RBAC PR was not reinstated; these new endpoints enforce their own role/campus boundaries.

## GC-ready update

> Follow-up sa QA gaps: implemented na ang production duplicate comparison and decisions (link existing student, distinct people, reject incoming duplicate), with reason/history/version checks. Returnee/continuing intake can now select and link the existing master before pre-enlistment; wala gi-delete ang original records or academic history. Added staff role/campus editing, admin-mediated temporary-password recovery, required password replacement and old-session invalidation. Attendance can use separate orientation and first-week ranges per campus/term; exact approved dates still need to be entered. Reviewer account setup is documented per teammate, with an optional provisioning/verification script that has no default passwords. Local pa ang changes pending publication and review.
