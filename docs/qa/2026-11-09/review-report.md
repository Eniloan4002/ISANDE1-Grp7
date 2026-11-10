> Historical pre-gap-closure QA snapshot. The implementation has since progressed; see [gap-closure handoff](../../gap-closure-handoff.md) and [current verification](../gap-closure/review-report.md). The results below remain evidence for the source hashes recorded at that time.

# Jira QA review: ICI2-13–19 and ICI2-49

Date: November 9, 2026 (Asia/Manila).

This report evaluates the **local uncommitted implementation**, based on commit `2a82b077de9d138078692cb3aef1acd85cca4b33` plus the files identified in [source-manifest.json](source-manifest.json). It is not a claim about remote `main`, a published PR, or teammates' running databases. No Jira status was changed. Acceptance was interpreted from the supplied implementation plan, screenshots and local API contracts; live Jira issue-specific acceptance criteria were not read directly.

The [48-case matrix](test-cases.md) provides steps, expected results, actual disposition and executable evidence. [Machine-readable cases](test-cases.json) and [backend test results](backend-results.json) are included.

## Execution status

Final results: **48 acceptance cases: 45 PASS, 2 BLOCKED by missing scope, 1 CONDITIONAL on the target review environment.** No application code was changed during this QA. Only the new acceptance tests and QA evidence were added/refined.

| Execution layer | Final result |
| --- | --- |
| Spring Boot / real PostgreSQL | 49 passed, zero failures/errors/skips |
| React unit tests, serial execution | 66 passed |
| Schema and frozen archive | 15 passed |
| Production build | Passed |
| Historical PR #6 matcher checks | 6 passed; applies to that PR head, not a production resolution service |
| Chromium browser journeys | 4 passed, zero failures/skips |

The four browser journeys cover intake through allocation, duplicate positive/negative cases and observed scope gaps, a live two-tab checklist conflict plus gates/section controls, and seven-role sign-in/session restoration. A test asserting that a missing feature remains blocked is not counted as delivered acceptance scope.

| Jira issue | QA disposition | Recommendation before Done |
| --- | --- | --- |
| ICI2-13 | Matching algorithm passes; complete duplicate feature is partial | Keep full feature under review until case adjudication/resolution is delivered, or explicitly accept an algorithm-only issue scope |
| ICI2-14 | New/transferee frontend and persistent path pass; existing-student path is partial | Accept only the agreed narrower scope, otherwise keep under review for returning/continuing linkage and any included duplicate-resolution UI |
| ICI2-15 | PASS for requirement tracking and audit scope | Ready for team acceptance on a published reviewable version |
| ICI2-16 | PASS for checklist interface scope | Ready for team acceptance on a published reviewable version |
| ICI2-17 | PASS for documented credential/Present-attendance confirmation gate | Ready for team acceptance; institution-specific orientation dates remain a policy input |
| ICI2-18 | PASS for section configuration/capacity scope | Ready for team acceptance on a published reviewable version |
| ICI2-19 | PASS for allocation/capacity scope | Ready for team acceptance on a published reviewable version |
| ICI2-49 | Seven-role functionality passes; preview-environment delivery is conditional | Verify actual reviewer accounts in the agreed target database before closing a promise to provide available preview accounts |

All recommendations refer to the tested local snapshot. Nothing has been committed, pushed, deployed, or marked Done by this QA. [Execution summary](execution-summary.json), [backend cases](backend-results.json), [browser results](browser-results.json) and [source hashes](source-manifest.json) provide the evidence.

The first concurrent frontend run timed out in eight of 66 tests under the default five-second per-test limit. All eight failures were timeouts, not mismatched assertions. The same unchanged application code passed when Vitest used one worker with file parallelism disabled. Execution contention is a likely explanation, not a proven product defect. No production assertions were weakened or application code changed to obtain a pass. This first-run instability is retained as QA-EXEC-01.

The first browser acceptance attempt passed the existing two journeys but failed the two new cases because of test defects: an administrator was used for a registrar-only duplicate route, and an unscoped note locator matched both a textarea and history entry. The fixtures/locators were corrected without changing application code (QA-EXEC-02). The original report is retained in `browser-first-attempt.json`. A second attempt passed three journeys and correctly detected a reused fictional identity from the previous run as a duplicate; that test was intended to use an unrelated person, so its input was made unique (QA-EXEC-03; `browser-second-attempt.json`). The final full run passed all four journeys without application changes. These intermediate test defects are retained rather than hidden.

## Findings requiring scope decisions

### GAP-01: production duplicate adjudication is missing

Issues: ICI2-13 and the duplicate-resolution portion of ICI2-14 / Feature 2.2.

The backend algorithm implements exact LRN and fuzzy first/last name plus same DOB. Suspected matches are stored as `duplicate_flag`; pre-enlistment is blocked. However, `/enrollment/duplicate` renders a development placeholder, and the production duplicate case/resolution API is absent. No side-by-side adjudication, merge/reject/distinct decision or existing-record linkage is available through that production workflow.

Reproduce: create a new applicant, then another applicant with the same LRN in another campus; open the second applicant and Duplicate Records. Detection can pass while the full duplicate-resolution feature remains incomplete. The historical PR #6 browser-storage matcher is tested separately; its working prototype is not evidence of a production resolution service.

Recommendation: keep the full feature For Review/In Progress. If ICI2-13 is explicitly limited to the matching algorithm, its algorithm coverage passes; record and assign the resolution follow-up before closing only that narrower scope.

### GAP-02: returning/continuing intake cannot complete review

Issue: ICI2-14 when its acceptance includes all applicant categories or existing-student intake.

The form can persist returnee/continuing applications, but review rejects them with HTTP 409 because existing-student linking is pending. This safely avoids manufacturing a duplicate student, but those categories cannot reach pre-enlistment through the integrated UI. New/transferee intake with no identity match follows the complete tested journey.

Recommendation: accept the narrower frontend/new-applicant scope only if the team explicitly agrees; otherwise retain For Review and implement existing-student selection/linking. Do not describe all intake categories as complete.

### ENV-01: reviewer accounts are not provisioned in the team's target environment

Issue: ICI2-49.

Seven-role provisioning, sign-in, role-safe routing and session restoration are tested against a disposable database. Those accounts are removed when the fixture is discarded. Their existence on the team's persistent preview/review database cannot be inferred from these tests.

Recommendation: authentication functionality can pass QA. Before closing a deliverable that promises available preview accounts, provision the intended accounts on the agreed review environment and verify those actual credentials privately. The startup guide documents the User Accounts procedure; no shared passwords were committed.

## Practical reviewer walkthrough

Use the tested code version and a disposable/test database, not school records.

1. As administrator create a Registrar account in User Accounts, assign a campus, sign out and sign in as that registrar.
2. Add a fictional new applicant with two guardians, a grade level, real term/program and optional unique 12-digit LRN. Reload and verify details.
3. Explicitly Review and pre-enlist. Confirm that this does not automatically enroll the student.
4. Save a Missing credential and a note. Reload and verify history. In another tab try saving an older version: the conflict must retain the unsaved note.
5. Complete the mandatory credentials. Confirmation must still be blocked without Present attendance; Absent/Excused do not count.
6. Record Present and explicitly confirm. Create a matching one-seat section and allocate the student. Reload: occupied is one and available is zero.
7. Confirm a second distinct applicant. The full section must not accept another assignment, even if an API call bypasses the disabled button.
8. Sign in as Principal, Adviser, Teacher, Guidance and Cashiering Reference. Reload each session; administration remains restricted. A signed-in development placeholder is not a completed business module.
9. Try a duplicate and a continuing applicant to reproduce GAP-01 and GAP-02. These are unresolved scope, not successful end-to-end journeys.

## Limits and evidence interpretation

- Browser tests use real HTTP and a native disposable PostgreSQL backend. React unit tests isolate UI behavior; backend tests exercise concurrency and authorization not inferred from navigation alone.
- Mandatory document completeness and at least one Present attendance are enforced. Institution-specific first-week/orientation calendar policy has not been supplied and is not certified here.
- This is functional QA for the specified issues, not load testing, a statistical validation of duplicate accuracy, or certification of unfinished academic/reporting modules.
- All data is fictional. No existing school database, released migration, frozen archive, or Jira status was modified. Test accounts use example.invalid emails and temporary chosen passwords; credentials are excluded from the report.
- Passing local QA does not replace publishing a reviewable version and team acceptance. Keep the source manifest with any review evidence or regenerate it after code changes.


## Screenshot evidence

- [Production duplicate-resolution placeholder](screenshots/qa-13-resolution-gap.png).
- [Live conflict preserves the second registrar’s draft](screenshots/qa-16-conflict-draft.png).
- [Full section disables another assignment](screenshots/qa-19-full-section.png).

## GC-ready update

> QA completed locally for ICI2-13–19 and ICI2-49. Final checks: 49 backend tests, 66 frontend unit tests, 15 schema/archive checks, production build, six historical matcher checks and four real-browser journeys passed. ICI2-15–19 passed their implemented functional scope. ICI2-13 matching works but production duplicate resolution is still pending. ICI2-14 works for new/transferee intake; returnee/continuing linkage remains pending. All seven staff roles passed sign-in/reload checks, but actual preview accounts still need verification on the team’s agreed database. No Jira status or app code was changed during QA; publication and team acceptance are still required. The report includes 48 cases with steps, expected/actual results and evidence.

Publication note: copied console logs have trailing whitespace normalized; test results and the recorded source hashes are unchanged.
