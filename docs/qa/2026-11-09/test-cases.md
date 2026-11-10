# Jira review test cases: ICI2-13–19 and ICI2-49

Executed November 9, 2026 (Asia/Manila) on the local source snapshot in [source-manifest.json](source-manifest.json). **48 cases: 45 PASS, 2 BLOCKED by missing scope, 1 CONDITIONAL on target-environment provisioning.**

Cases map to multiple executable assertions; acceptance-case counts differ from raw test counts. See [review report](review-report.md) for suite results, attempt history, scope and Jira recommendations.

## Preconditions

Disposable PostgreSQL 17.6 with Flyway V1-V8; temporary administrator; real role/campus accounts; fictional applicants and example.invalid addresses. The browser fixtures create their own terms/sections. No real school records or Jira statuses were changed. No fixture credentials are published here.

## ICI2-13

| Case | Steps | Expected | Actual | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| **TC-13-01** Exact LRN across campuses | Submit two different people with the same 12-digit LRN in different campuses. | Second application is duplicate_flag; no student identity is created. | Cross-campus LRN duplicate returned duplicate_flag and null studentId. | PASS | qa-acceptance.spec.js: matching gates |
| **TC-13-02** Fuzzy name plus DOB | Submit a small first-name spelling variation with the same surname and birth date. | Suspected match is flagged. | Small name variation plus same DOB returned duplicate_flag. | PASS | qa-acceptance.spec.js: matching gates |
| **TC-13-03** Different birthday | Submit the same name with a different DOB and no matching LRN. | Application remains submitted, without a false duplicate flag. | Same name with a different DOB returned submitted. | PASS | qa-acceptance.spec.js: matching gates |
| **TC-13-04** Unrelated person | Submit unrelated first/last names with the same DOB. | Application remains submitted. | Unrelated names sharing DOB returned submitted. | PASS | qa-acceptance.spec.js: matching gates |
| **TC-13-05** Empty IDs and self-exclusion | Run the PR #6 fixture with empty identifiers and exclude its own application ID. | Empty values do not match; application does not match itself. | Six PR #6 fixture checks passed, including blank identifiers and self-exclusion. | PASS | scripts/check-duplicate-pr.mjs |
| **TC-13-06** Duplicate adjudication | Open Duplicate Records and attempt the production case/resolve workflow. | Side-by-side case review and adjudication should exist for the full feature. | production screen is a placeholder and case API is absent; matching alone passes. | BLOCKED | qa-acceptance.spec.js: matching gates; source review |

## ICI2-14

| Case | Steps | Expected | Actual | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| **TC-14-01** Catalog mapping | Choose a real campus, term, program group, track, specialization and voucher; submit. | Numeric catalog IDs and valid matching track are persisted. | Form-to-API catalog mapping passed and persisted numeric IDs. | PASS | intake-integration.spec.js; intake-integration.test.jsx |
| **TC-14-02** Required fields and invalid input | Submit invalid LRN, incompatible track, inaccessible campus or multiple primary guardians. | Request rejected; no partial application/student writes. | Invalid/scoped inputs rejected; partial-write assertions passed. | PASS | EnrollmentWorkflowTest.intakeValidationAndCampusScopeRejectWithoutPartialWrites |
| **TC-14-03** Multiple guardians | Enter two guardians and a primary contact; create then review the application. | Both contacts persist and are copied to the linked student. | Two guardians persisted; primary-contact validation passed. | PASS | EnrollmentWorkflowTest.intakePersistsGuardiansAndExplicitReviewFeedsEnrollmentGates; intake-integration.test.jsx |
| **TC-14-04** Queue filtering and persistence | Create applicant, reload detail, apply campus filtering and exercise paged API results. | Saved profile survives reload; scoped filters and pagination return the matching records. | Reload persistence, campus filtering and paged API assertions passed. | PASS | intake-integration.spec.js; EnrollmentWorkflowTest.directoryFiltersCompletenessCampusDistrictRouteAndPagination |
| **TC-14-05** Explicit registrar review | Submit a new applicant, attempt confirmation, then explicitly review/pre-enlist. | Submission alone creates no student or enrollment; review links one identity; concurrent review creates only one. | Explicit review created/linked one student; concurrent review produced one success and one conflict. | PASS | EnrollmentWorkflowTest.intakePersistsGuardiansAndExplicitReviewFeedsEnrollmentGates; concurrentIntakeReviewCreatesOnlyOneStudentIdentity |
| **TC-14-06** Existing-student journey | Submit continuing/returnee category and try to review/link it. | Existing identity should be linked without duplicating the student for complete intake coverage. | capture works; continuing/returnee review returns 409 pending existing-student linkage. | BLOCKED | qa-acceptance.spec.js: matching gates; source review |

## ICI2-15

| Case | Steps | Expected | Actual | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| **TC-15-01** Initial checklist | Read checklist for an application with no document rows; read history. | All catalog credentials appear Pending; reads do not manufacture audit entries. | Read returned Pending catalog items without fabricated history. | PASS | RequirementTrackingTest.unrecordedChecklistIsPendingAndReadsDoNotManufactureHistory |
| **TC-15-02** Completeness rules | Exercise submitted/missing/pending/invalid mandatory credentials and an optional credential. | Complete/Incomplete/Pending are correct; optional credentials do not block completion. | All mandatory summary states and optional-item exclusions passed. | PASS | RequirementTrackingTest.mandatoryStatusesDriveAllThreeSummaryStates; optionalDocumentsDoNotBlockMandatoryCompletion |
| **TC-15-03** Attributed immutable audit | Update status and note; inspect old/new values and actor; try changing audit rows. | Real actor/time and before/after values retained; audit modification rejected. | Actor/before-after/history checks passed; audit mutations rejected. | PASS | RequirementTrackingTest.actualWritesAreAttributedAndHistoryPreservesPreviousValues; FoundationAuthenticationTest.auditIsAppendOnlyAndPasswordsNeverEnterSnapshots |
| **TC-15-04** No-op and stale writes | Repeat an identical save, then save a stale version. | No fake audit for no-op; stale edit gets 409 and cannot overwrite. | No-op did not add history; stale writes conflicted. | PASS | RequirementTrackingTest.noOpsAndStaleUpdatesDoNotCreateFalseAuditEntries |
| **TC-15-05** Validation and access | Use invalid code/status/notes, another campus and an unauthorized role; request paged history. | Invalid writes rejected; no cross-campus or unrelated history leakage. | Invalid/scoped/unrelated history access checks passed. | PASS | RequirementTrackingTest.invalidInputsAndUnknownCodesLeaveNoPartialWrites; rolesAndCampusesProtectReadWriteAndAudit; unrelatedApplicationsDoNotLeakIntoAuditPages |
| **TC-15-06** Concurrent first save | Start two document writes with the same initial version. | Exactly one succeeds and the other conflicts. | Concurrent first writes produced one success and one conflict. | PASS | RequirementTrackingTest.simultaneousFirstWritesReturnOneConflictInsteadOfOverwriting |

## ICI2-16

| Case | Steps | Expected | Actual | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| **TC-16-01** Live directory/dialog | Open persisted applicant through requirements directory and deep link. | Actual catalog/checklist loaded; no seeded mock states or fabricated history. | Directory and deep-link dialog loaded persisted backend data. | PASS | intake-integration.spec.js; qa-acceptance.spec.js |
| **TC-16-02** Status and note persistence | Set Missing with note, reload, then set Submitted. | Saved status/notes survive reload and history contains the change. | Status and notes survived reload; live history entry displayed. | PASS | qa-acceptance.spec.js: live two-tab conflict; intake-integration.spec.js |
| **TC-16-03** Real two-tab conflict | Load same checklist in two separately authenticated tabs; save A, then stale B. | B shows conflict and retains its unsaved note; A is not overwritten. | Second tab got a conflict and retained its unsaved note. | PASS | qa-acceptance.spec.js: live two-tab conflict |
| **TC-16-04** Follow-up contacts | Open contact details before and after student creation. | Actual applicant and guardian contacts displayed; no simulated message sent. | Real student/guardian contacts displayed before/after linkage; no sending action simulated. | PASS | intake-integration.test.jsx; intake-integration.spec.js |
| **TC-16-05** Plain text and separate drafts | Save one document while another has a draft; render markup-like notes. | Other draft remains; note is rendered as text. | Independent drafts remained and markup-like notes rendered as text. | PASS | enrollment.test.jsx: preserves another document draft; renders verification notes as plain text |
| **TC-16-06** Filtering, keyboard and mobile | Filter requirement status/program; dismiss checklist with Escape; reopen; use 390px viewport. | Filters call server; dismissal/reopen works with persisted notes; no document overflow. | Filter tests, Escape/reopen and 390px document-overflow checks passed. | PASS | enrollment.test.jsx: completeness filters; qa-acceptance.spec.js; intake-integration.spec.js |

## ICI2-17

| Case | Steps | Expected | Actual | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| **TC-17-01** Credential gate | Try confirming with missing mandatory documents. | Confirmation disabled/rejected and no enrollment inserted. | Incomplete credentials kept confirmation disabled and API rejected premature confirmation. | PASS | qa-acceptance.spec.js; EnrollmentWorkflowTest.confirmationChecksEveryGateAndDoesNotWriteOnFailure |
| **TC-17-02** Attendance gate | Complete documents; use no attendance, Absent or Excused before Present. | No/Absent/Excused do not satisfy the gate; Present does. | No/Absent/Excused attendance did not satisfy the gate; Present enabled confirmation. | PASS | qa-acceptance.spec.js; EnrollmentWorkflowTest.confirmationChecksEveryGateAndDoesNotWriteOnFailure |
| **TC-17-03** Dates and evidence retention | Try future attendance and stale date version; remove last present after enrollment. | Future/stale edit rejected; confirmed student retains present evidence. | Future/stale date edits and loss of final Present evidence were rejected. | PASS | EnrollmentWorkflowTest.attendanceValidatesDatesVersionsAndProtectsConfirmedEvidence |
| **TC-17-04** Explicit confirmation and reload | Record Present, open confirmation dialog, confirm and reload. | Enrolled status and attributed confirmation persist; verification alone does not enroll. | Explicit confirmation persisted enrolled standing, staff attribution and reload state. | PASS | intake-integration.spec.js; EnrollmentWorkflowTest.confirmationIsAttributedPersistedAndSafeToRetry |
| **TC-17-05** Concurrency and retries | Confirm concurrently; retry with old/current version; promote provisional record. | One enrollment only; stale version conflicts; current retry is safe; same-term duplicate rejected. | Concurrent/retry/provisional/same-term checks passed without a second enrollment. | PASS | EnrollmentWorkflowTest.simultaneousConfirmationCreatesOneEnrollment; provisionalEnrollmentIsPromotedAndSameTermCannotBeEnrolledTwice |
| **TC-17-06** Other blockers and scope | Try inactive/missing student, open duplicate case, under-review standing or another campus/role. | Every blocker is enforced on server; no partial enrollment. | Student/status/duplicate/campus/role blockers rejected without partial enrollment. | PASS | EnrollmentWorkflowTest.confirmationChecksEveryGateAndDoesNotWriteOnFailure; campusAndRoleScopeProtectEveryWorkflow |

## ICI2-18

| Case | Steps | Expected | Actual | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| **TC-18-01** Create section in UI | Create a scoped section with campus, term, strand, grade and capacity. | Section persists with correct configuration and zero occupancy. | UI created a matching section with capacity one. | PASS | qa-acceptance.spec.js: section controls |
| **TC-18-02** Edit and stale conflict | Edit room/capacity with current version, reload and retry old version. | Current edit persists; stale update is rejected. | UI room edit persisted; stale edit checks passed. | PASS | qa-acceptance.spec.js; EnrollmentWorkflowTest.sectionCrudPreservesParentsVersionsAndOccupiedCapacity |
| **TC-18-03** Validation and adviser scope | Use zero capacity, invalid parent IDs or adviser without campus adviser grant. | Invalid request is rejected without partial section. | Invalid capacity/parent/adviser inputs rejected. | PASS | EnrollmentWorkflowTest.sectionInputValidatesRequiredFieldsAndAdviserCampus |
| **TC-18-04** Fixed academic-term parent | Attempt to change the section’s academic term after creation. | Term change is rejected; the original parent is preserved. | Academic term change was rejected. | PASS | EnrollmentWorkflowTest.sectionCrudPreservesParentsVersionsAndOccupiedCapacity |
| **TC-18-05** Capacity lower bound and race | Reduce below current occupancy; race capacity reduction against assignment. | Capacity never falls below occupied seats. | Capacity reduction/assignment race never allowed occupancy above capacity. | PASS | EnrollmentWorkflowTest.sectionCrudPreservesParentsVersionsAndOccupiedCapacity; capacityEditsSerializeWithReservations |
| **TC-18-06** Delete safeguards | Delete unused section, then attempt deleting a section with enrollment. | Unused section deletes; referenced section remains with a conflict response. | Unused deletion passed; UI/API refused referenced deletion and retained section. | PASS | qa-acceptance.spec.js; EnrollmentWorkflowTest.sectionCrudPreservesParentsVersionsAndOccupiedCapacity |

## ICI2-19

| Case | Steps | Expected | Actual | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| **TC-19-01** Scoped directory and route filters | Filter confirmed students by campus/program, district and transport route. | Matching scoped students returned; unconfirmed applicants cannot be allocated. | Scoped district/route filters passed; unconfirmed assignment was blocked. | PASS | EnrollmentWorkflowTest.directoryFiltersCompletenessCampusDistrictRouteAndPagination; enrollment.test.jsx |
| **TC-19-02** Assign and persist | Assign confirmed student to matching section and reload. | Assignment retained and occupied/available counts updated. | Assignment and occupied/available counts survived reload. | PASS | intake-integration.spec.js |
| **TC-19-03** Compatibility and versions | Try wrong campus/program/grade, missing grade or stale enrollment version. | Invalid allocation rejected; original enrollment preserved. | Wrong grade/program/campus, missing grade and stale versions rejected. | PASS | EnrollmentWorkflowTest.assignmentChecksCompatibilityGradeVersionsAndSupportsRemoval; inaccessibleSectionsAndEnrollmentIdsCannotBeReadOrMutated |
| **TC-19-04** Full section UI and server | Fill a one-seat section; load another confirmed student; force assignment through API. | UI disables assignment; server returns 409; occupancy remains one. | Full section disabled assignment; forced API call got 409 and occupancy stayed one. | PASS | qa-acceptance.spec.js: last seat |
| **TC-19-05** Concurrent last seat | Race two confirmed students for one available seat. | Exactly one succeeds; one conflicts; no overbooking. | Last-seat race produced one success/one conflict and one occupied seat. | PASS | EnrollmentWorkflowTest.simultaneousReservationsCannotOverbookLastSeat |
| **TC-19-06** Removal and academic history | Remove ordinary assignment; try moving student with class memberships. | Ordinary removal succeeds; academic membership blocks transfer to preserve grades. | Ordinary removal worked; academic membership prevented reassignment. | PASS | EnrollmentWorkflowTest.assignmentChecksCompatibilityGradeVersionsAndSupportsRemoval; assignedAcademicMembershipsBlockTransfersAndPreserveGrades |

## ICI2-49

| Case | Steps | Expected | Actual | Result | Evidence |
| --- | --- | --- | --- | --- | --- |
| **TC-49-01** Seven actual sign-ins | Provision seven roles and sign in as each with chosen password. | Every role authenticates and reaches an allowed landing screen. | All seven roles provisioned and authenticated through real API/browser checks. | PASS | staff-roles.spec.js; FoundationAuthenticationTest.provisionedAccountsForEveryStaffRoleCanActuallySignIn |
| **TC-49-02** Session restoration | Reload each role session after successful login. | Session and correct role landing restored. | Every role restored its session after reload. | PASS | staff-roles.spec.js |
| **TC-49-03** Bad/inactive/revoked accounts | Use bad password, deactivate account, reuse revoked token and activate using current version. | Bad/inactive/revoked access rejected; valid reactivation enables new login. | Bad/inactive/revoked access rejected; current-version activation enabled new login. | PASS | FoundationAuthenticationTest.invalidCredentialsAndDisabledAccountsCannotSignIn; logoutRevokesTokenDurablyAndOtherSessionsRemainValid; accountDeactivationRejectsExistingSessionsAndSupportsActivationWithVersionChecks |
| **TC-49-04** Role-safe return URL | Sign in as each non-admin with a saved User Accounts URL; request admin APIs. | Redirect goes to allowed landing; direct unauthorized API access rejected. | Each role reached an allowed landing from a saved admin URL; unauthorized APIs stayed denied. | PASS | staff-roles.spec.js; foundation.test.jsx: saved administrator-only URL; FoundationAuthenticationTest.provisionedAccountsForEveryStaffRoleCanActuallySignIn |
| **TC-49-05** Provisioning controls | Create accounts through admin UI/API; try non-admin, cross-campus, weak password and self-deactivation. | Valid scoped creation works; invalid operations denied; hashes are not returned. | Scoped provisioning/status/validation/self-protection and password-hiding checks passed. | PASS | staff-accounts.test.jsx; staff-roles.spec.js; FoundationAuthenticationTest account tests |
| **TC-49-06** Reviewer-accessible accounts | Verify the role accounts exist in the actual team review environment after QA cleanup. | Reviewer can access the intended preview environment using privately supplied credentials. | this QA provisions disposable accounts only; target team/review database has not been inspected or provisioned. | CONDITIONAL | Environment handoff |
