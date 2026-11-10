# Gap closure verification: November 9, 2026

Local working-copy verification. Changes are not committed, pushed, deployed or reflected in Jira. This report supersedes the missing-functionality findings in the [earlier QA snapshot](../2026-11-09/review-report.md), which remains historical evidence.

## Final results

| Check | Result |
| --- | --- |
| PostgreSQL backend integration tests / Maven verify | 59 passed; zero failures, errors or skips |
| React unit/component tests | 70 passed |
| Schema and frozen-archive checks | 15 passed |
| Production frontend build | Passed |
| Real-browser journeys | 7 passed; zero unexpected failures, skips or flaky results |
| Reviewer-account setup tool | Provisioning and verify-only passed; six role accounts plus authenticated administrator verified; repeat run created no duplicates |
| Whitespace verification | git diff --check passed |

Structured results are in [results.json](results.json). [Source hashes](source-sha256.json) identify the tested application/test sources without copying test-run environment properties or secrets. Backend and browser tests used disposable PostgreSQL and private temporary credentials; no shared school database was provisioned.

## Acceptance cases and practical retest

| Area / case | Tested behavior | Outcome |
| --- | --- | --- |
| ICI2-13 / TC-13-06 | Persist candidate cases; compare both identities; choose same person, distinct or reject with explicit confirmation/reason; inspect audit history | Passed |
| Duplicate safety | Exact shared LRN cannot be declared distinct; reciprocal distinction survives later canonical review; cross-campus identity hidden; stale/concurrent decision has one winner | Passed |
| ICI2-14 / TC-14-06 | Capture continuing/returnee application, search and select existing student, verify identifiers/DOB, link and pre-enlist without creating another master | Passed |
| Existing identity safety | Wrong identity, archived master, stale versions and same-term duplicate enrollment are rejected; original master/history retained | Passed |
| ICI2-15–19 regression | Intake, credential persistence/conflicts, attendance, confirmation, section capacity/allocation and role/campus boundaries | Passed |
| Attendance policy | Separate orientation and first-week ranges per campus/term; accept dates in either range, reject gap dates; protect confirmed attendance from policy edits | Passed |
| Account access | Edit roles/campuses with scope/version checks; old sessions stop working | Passed |
| Password recovery | Admin verifies identity, supplies temporary password; login forces change; business APIs blocked until change; prior/temporary passwords and sessions invalidated | Passed |
| Password change | Current-password check, matching masked new fields, successful change followed by fresh login | Passed |
| ICI2-49 / TC-49-06 revised handoff | User requested setup documented for each teammate; provisioning plus non-destructive verification exercised against a disposable target | Delivered; teammates must run setup on their own database |

To test manually, use a local disposable database: create a new applicant and a matching applicant, open Duplicate Records, inspect and decide with a reason. Create a returning applicant for a later term and link the original student; verify the student number stays unchanged. Complete documents, record attendance within configured dates, confirm and allocate to a section; reload each page to verify persistence. As a second administrator, edit a staff account and recover its password, then verify the old session fails and temporary login forces Account Security. Detailed steps and teammate commands are in the [handoff](../../gap-closure-handoff.md).

Browser evidence: [linked returnee](gap-returnee-linked.png), [duplicate rejection](gap-duplicate-rejection.png), [account recovery](gap-account-recovery.png).

## Execution notes and boundaries

An earlier backend run caught a legacy-application identifier matching bug; the scanner now checks both linked applications and student masters and the regression passes. A later test-only random-identity collision was removed by assigning distinct fixture birth dates; explicit duplicate fixtures still share identity fields. The final full suite passed after those fixes. Earlier browser execution passed before the attendance clarification; the final seven journeys also passed with separate orientation/first-week ranges and a rejected gap date.

Identity resolution uses canonical application-to-student linking and preserved audit records. It does not physically consolidate two existing student histories. Recovery is administrator-mediated; email reset delivery is not configured. Unconfigured attendance policy retains the existing Present gate and explicitly shows that exact dates are unconfigured. The usual two-weeks-before-classes / August-first-week timing is guidance, not an approved calendar: staff must enter each campus/term's actual dates.

ICI2-13–19 can proceed to team acceptance against this local implementation; use the practical retest before marking Done. ICI2-49 requires each teammate's setup/verification in their own environment. Broader module RBAC, academic services, reporting and legacy migration remain outside this gap closure. V9 is a draft local migration: coordinate numbering before team publication. No Jira statuses were changed.
