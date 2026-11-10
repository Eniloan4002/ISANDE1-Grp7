# ICI2-14 and ICI2-16 integration handoff

Date: November 9, 2026. Changes are local and have not been pushed or merged into remote `main`.

## Contributions retained

Pauline's published work came from [PR #9, enrollment queue/intake](https://github.com/Eniloan4002/ISANDE1-Grp7/pull/9) and [PR #10, requirements verification](https://github.com/Eniloan4002/ISANDE1-Grp7/pull/10). The integration starts from those source files and retains the queue table, filters, intake form sections, dependent catalog selections, multiple-guardian controls, applicant details, and requirements directory/dialog layout.

The mock applicants, in-memory provider, hard-coded credential lists and fabricated verification log entries were replaced with authenticated API reads and writes. The requirements dialog embeds the existing versioned verification editor/history rather than introducing a second set of persistence rules.

## Integrated behavior

- Staff intake persists a submitted application with names, birth date, optional LRN, contact details, category, campus/term/program, voucher, previous school, income description, multiple guardians and primary-contact designation.
- The UI maps the program group, track and specialization controls to actual PostgreSQL program IDs. Campuses, terms, programs and vouchers load from real catalogs. Grade level, residential district and transport route feed the existing allocation filters.
- The queue uses server-side search, filters, pagination and campus-scoped statistics. Applicant details survive refresh and use numeric IDs internally while displaying the application number.
- Registrar review is explicit. A new/transferee application with no suspected identity match can be pre-enlisted; review creates and links its student master record and guardians transactionally. This does not confirm enrollment.
- Credential statuses, notes, mandatory completeness and staff/time history come from the requirement API. Updates include optimistic versions. Errors retain drafts, and explicit reload is available.
- Follow-up displays actual applicant/guardian contact information, including before a student identity is created. No message is sent or simulated.
- Reviewed applications continue through the existing attendance gate, explicit enrollment confirmation and capacity-aware section allocation.

## Duplicate-matching overlap with Elijah

Elijah's prototype files were left untouched by this integration. The backend intake/review gate follows PR #6's exact 12-digit LRN or fuzzy first/last name plus exact DOB rules, with the same 0.8 name threshold and first-given-name comparison. It checks existing applications and student identities across campuses and gives only a generic conflict message across scope boundaries.

Suspected matches are stored as `duplicate_flag` applications; review rechecks current records and blocks creating another identity. Intake/review transactions share an advisory lock to serialize this path. The unique student LRN constraint remains a final database guard. Other future identity-writing services must coordinate locking if they require the same fuzzy-match serialization guarantee.

This is an integration gate, not the complete duplicate-resolution product. Side-by-side cross-campus review, case adjudication, merge/reject/distinct decisions and linking an existing student remain pending. Returning/continuing applicants can be captured but their review is blocked until existing-student linking is implemented. No automatic merge or institution policy was invented.

## API additions

All endpoints require authenticated Admin/Registrar access and eligible campus grants.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/intake/reference-data` | Real enrollment catalogs and vouchers |
| GET | `/api/intake/statistics` | Campus-scoped queue counts |
| POST | `/api/intake/applications` | Create a submitted application; flag suspected matches |
| GET | `/api/intake/applications/{id}` | Persisted profile, guardian details and enrollment workflow |
| POST | `/api/intake/applications/{id}/review` | Versioned review and pre-enlistment for eligible new/transferee identities |

Creation takes numeric `campusId`, `termId`, `programId`; `category` (`new`, `transferee`, `returnee`, `continuing`); names and `birthDate`; optional nullable `lrn`, `sex`, `phone`, `email`; `voucher`; `track`; `gradeLevel`; optional `district`, `transportRoute`, `previousSchool`, `householdIncome`; and up to ten guardians (`fullName`, `relationship`, optional `phone`/`address`, `primaryContact`). Blank optional LRN/email values are sent as null. Review takes `{ "version": <current application version> }`.

No new database migration is required by this integration. It uses the local V1-V8 foundation. No released migration or frozen archive was edited.

## Files to review

- `frontend/src/enrollment/EnrollmentScreens.jsx`: Pauline's queue, intake and detail screens adapted to APIs.
- `frontend/src/enrollment/RequirementsScreen.jsx`: Pauline's directory/dialog integrated with the shared checklist editor.
- `frontend/src/enrollment/enrollmentApi.js`: catalog and request/response mappings.
- `frontend/src/enrollment/enrollment.css`: retained styles, with shared selectors scoped to avoid allocation conflicts.
- `frontend/src/EnrollmentWorkspace.jsx`, `App.jsx`, `routes/routeConfig.js`: shared verification behavior and protected route integration.
- `backend/src/main/java/ph/edu/ici/enrollment/IntakeController.java` and `IntakeService.java`: application persistence/review and identity gate.
- `EnrollmentService.java`: directory identity/category/date fields; `EnrollmentErrors.java`: consistent intake error responses.

Paths above are relative to `ICILocal2.0/`.

## Verification

- Backend: 49 tests passed on disposable PostgreSQL (including the subsequent staff-login coverage), including five added integration tests for intake persistence, guardians, explicit review, the complete API enrollment journey, invalid inputs/rollback, campus/role restrictions and duplicate gates.
- Frontend: 66 unit tests and 15 archive/schema checks passed; production build passed.
- The opt-in Chromium journey passed against a disposable backend, including reload persistence and 390px viewport overflow checks. Desktop/mobile screenshots were inspected. Browser coverage is in `frontend/tests/intake-integration.spec.js`. It creates a temporary applicant through the real UI, reviews and verifies it, confirms enrollment and allocates a section; the test also checks reload persistence and mobile overflow. Enable only against a disposable backend with `ICI_LIVE_API=1`, `ICI_TEST_USERNAME`, `ICI_TEST_PASSWORD`, and the matching `ICI_API_URL`.

## GC update draft

> Update sa ICI2-14 ug ICI2-16: gi-integrate nako with Codex ang frontend ni Pauline sa existing backend enrollment work. Gi-retain ang queue/intake form, multiple guardians, applicant details ug requirements dialog. Real API/database na ang intake, catalogs, checklist updates ug verification history; dili na mock data sa integrated local version. Added explicit registrar review before pre-enlistment, then connected to attendance, confirmation ug section allocation. Gi-follow pud ang duplicate matching rules ni Elijah as a backend gate; wala giusab iyang prototype files. Pending pa ang full duplicate resolution ug existing-student linking for returnee/continuing applicants. Local pa ni for review; wala pa gi-push.


## Follow-up: staff sign-in

The startup guide originally provisioned only the administrator; the historical preview accounts were not available on teammates’ machines. A restricted User Accounts screen and API now allow an administrator to create real staff accounts with chosen passwords and campus grants, and activate/deactivate them. A sign-in return-URL bug was also fixed: users now return only to a route their actual signed-in role permits. Placeholder pages explicitly distinguish successful authentication from unfinished business modules. See [staff login fix](staff-login-fix.md).
