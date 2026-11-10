# Historical role-specific preview accounts

These example logins were created in an earlier disposable local preview, not shipped as default accounts. They do not exist automatically in a teammate’s database. For real accounts, the administrator must use **User Accounts** in the integrated version; see [startup guide](run-complete-app.md). The examples covered each staff role defined in the implementation plan (Epic 1, Feature 1.3). The Department Head request uses the Principal role by user confirmation; no new department role or permissions were invented.

| Account | Role | Campus scope | Landing screen |
| --- | --- | --- | --- |
| linus_admin | ROLE_ADMIN | All three campuses | Academic setup |
| registrar_demo | ROLE_REGISTRAR | Iligan Main | Enrollment Queue |
| principal_demo | ROLE_PRINCIPAL | All three campuses | Academic History |
| adviser_demo | ROLE_ADVISER | Iligan Main | Advisory Class |
| teacher_demo | ROLE_TEACHER | Iligan Main | Grade Entry |
| guidance_demo | ROLE_GUIDANCE | Iligan Main | Guidance Records |
| cashier_demo | ROLE_CASHIER_REF | Iligan Main | Enrolled Student Reference |

Passwords were provided privately in the chat and stored as BCrypt cost-12 hashes in PostgreSQL. No plaintext account passwords or password seeds are committed. The additional administrator logins created earlier are retained.

Principal navigation covers academic oversight, the distinct principal-review step shown in the archived grade-correction scenario, withdrawal approvals and operational reporting. Cashiering Reference navigation is limited to enrolled-student/voucher reference and clearance status. Shared pages use one route with explicitly listed roles. Registrar's landing screen follows the mockup's Enrollment Queue. Non-admin staff do not receive account-administration navigation.

This provisions role-specific preview identities and navigation. Teacher/adviser class assignments, production grade operations, principal approval services, guidance services and cashiering reference data endpoints remain their own implementation tasks. Reserved screens are labeled as placeholders. No course assignment or student records were fabricated just to populate accounts. This does not mark full RBAC (ICI2-7) or user-account management (ICI2-9) complete.

The prospective-student mockup entry is an application flow, not one of the seven staff authentication roles in the plan; no unauthorized ROLE_STUDENT account was added.

Accounts are in the current disposable local PostgreSQL preview. They disappear when that database is discarded and are not provisioned automatically on another developer's computer or a persistent deployment.

Verification: real API sign-in checked role/campus claims for all six newly provisioned accounts; non-admin/non-registrar setup access returns 403. Frontend role/route tests pass, including rejection of principal/cashier/teacher/adviser/guidance access to setup and rejection of principal access to grade encoding. Browser sign-in and landing-page checks cover all seven example staff roles.
