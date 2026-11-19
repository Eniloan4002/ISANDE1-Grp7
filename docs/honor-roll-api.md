# Honor-roll backend — ICI2-34

This is a configurable computation service, not a declaration of the school's policy. **No DepEd thresholds or approved rules are seeded.** An authorized school representative must approve the institution's rules and examples (ICI2-45) before real use. The approval API records a principal/admin's attestation and reference; it does not independently authenticate the external approval document. No screens or CSS are changed.

## Permissions and scope

Use existing Bearer authentication. `honors.configure` is seeded to Registrar/Admin for their granted campuses. `honors.approve` is seeded to Principal/Admin for their granted campuses. `reports.read` permits scoped calculation and policy reading. The permission and campus must belong to the same role grant; an unrelated campus role grants no authority. Teachers cannot configure rules or retrieve a cohort honor roll through these endpoints.

## Policy lifecycle

`POST /api/honors/policies` creates a draft, returning 201 and its ID/version. Example body (IDs must exist; numbers are fictional test inputs, not official rules):

```json
{
  "campusId": 1,
  "programId": 1,
  "gradeLevel": "11",
  "gradingPeriodId": 1,
  "rules": {
    "subjects": [{"subjectId": 1, "weight": 1}, {"subjectId": 2, "weight": 3}],
    "decimalPlaces": 2,
    "rounding": "HALF_UP",
    "minimumSubjectGrade": 75,
    "categories": [{"name": "Fictional distinction", "minimumAverage": 90}],
    "tiePolicy": "competition_include_all",
    "examples": [{"name": "Fictional weighted case", "values": [88, 92], "expectedAverage": 91, "expectedCategory": "Fictional distinction"}]
  }
}
```

The policy's grading period determines its academic term/year. Scope is campus + program + section grade level + grading period. Included subjects and weights are explicit, not inferred from subject units. Each subject must exist and be unique, weights must be positive and at most 1,000 with up to four decimal places. Up to 100 subjects and 20 uniquely named/thresholded award bands are supported. Average scale is 0–4 decimal places; rounding is `HALF_UP`, `HALF_EVEN` or `DOWN`. Bands use inclusive **rounded** average thresholds; the highest qualifying threshold wins. Subject minimum checks use each actual unrounded stored grade.

`GET /api/honors/policies/{id}` returns the configuration, status/version, server-derived staff and timestamps, approval reference, approving staff and time. Foreign-campus IDs return 404.

`PUT /api/honors/policies/{id}` accepts `{"version":1,"rules":{...}}`. Only drafts can be changed. Invalid subjects, duplicate subjects/bands or inconsistent worked examples return 400. Example values must follow the included-subject order; expected average and category are recomputed by the same service and must match. `expectedCategory` may be null when no award applies. Approved examples must be supplied by the school; passing arithmetic examples alone do not constitute institutional approval.

`POST /api/honors/policies/{id}/approve` accepts `{"version":1,"reference":"school-approved policy/minutes identifier"}`. Only a campus-authorized Principal/Admin can attest approval. It increments the version and records the acting user and time. New approval atomically supersedes the previous approved policy for exactly the same cohort/period. Approvals serialize through their parent grading period to prevent competing active policies. Approved/superseded rule contents and their approval metadata are immutable. To revise them, create and approve a new draft. All mutations use database audit triggers. Stale expected versions return 409.

## Computation

`GET /api/honors/roll?campusId=1&programId=1&gradeLevel=11&gradingPeriodId=1`

A school-approved applicable policy is mandatory; absent approval returns 409 with a readable explanation. Calculation runs in one repeatable-read snapshot of the approved rules, cohort and grades. It includes enrollments whose status is `enrolled` or `completed` in the specified program/campus/term and whose assigned section has that grade level. Provisional/withdrawn students are excluded.

Each configured subject must have exactly one **actual class membership** and one numeric finalized grade for this period. A section's offering alone does not imply the student takes that subject. Missing membership/grade, multiple matching classes, draft/submitted grades pending grade-correction requests, and all non-numeric marks (`INC`, `NG`, `DRP`) make the student ineligible; no partial average is substituted. Reasons appear in the result. A diagnostic average may still appear if all numeric values exist but another eligibility rule fails; `eligible` remains false and rank/category remain null.

The exact weighted sum is divided by the exact total weight once, using the configured decimal places/rounding. No intermediate rounding or floating-point arithmetic is used. An eligible student must meet every subject minimum and an award band. Rankings compare the rounded averages of eligible students: competition ranking (1, 1, 3), including all tied students. Student number provides deterministic display order without breaking ties. These supported fixed eligibility/tie semantics must be included in school approval; other tie/non-numeric behavior is not configurable in this implementation.

The response includes policy ID/version and approval reference, academic year/period, and items containing enrollment ID, student number/name, average, category, eligibility, reasons and rank. A correction to a finalized grade is reflected in the next calculation; historical computations are not persisted/certified award lists. Computation is bounded to 5,000 enrollments per configured cohort and returns 413 rather than silently truncating a larger cohort. No export endpoint or new honor-roll screen is added.

## Remaining acceptance evidence

The backend supplies rule configuration, approval attestation, worked-example validation and computation tests. Jira ICI2-45 still needs **actual institution-approved** rules and expected cases; this feature must not be represented as institutionally accepted until those materials are supplied and verified. Government policy applicability and any school-specific exemptions remain the school's responsibility.
