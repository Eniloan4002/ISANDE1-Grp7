# ICI2-31–33: reporting, exports and guidance backend

All paths below require the existing Bearer JWT. Actors, campus grants and assigned-class scope come from the current authenticated account, not request `actor` or `role` fields. V16 adds reporting/guidance configuration, explicit school days, daily academic attendance and counseling cases/notes. No production default thresholds or calendar dates are seeded.

## Operational reports: ICI2-31/32

`GET /api/reports/enrollments` requires `reports.read` (Registrar/Principal). Optional combined filters:

- `campusId`, `academicYearId`, `termId`, `gradingPeriodId`, `sectionId`
- exact `gradeLevel`, `voucher` and `enrollmentStatus`
- `requirementStatus=complete|incomplete`
- `page=0`, `size=25` (maximum 100)

Returns `items`, filtered `total`, `summary` (total/enrolled/incomplete), voucher distribution, page and size. Items include enrollment/student/campus/academic-year/term/section/program/voucher/standing and missing mandatory documents. The grading-period filter selects enrollments in that period's term; this endpoint is an enrollment report, not a grade-average computation.

Term/year, period/term and section/term combinations are validated. A caller's unrelated campus role cannot expand report scope. An explicit unauthorized campus filter returns 403; unauthorized section lookup returns 404. Counseling notes never appear in these reports.

`GET /api/reports/enrollments/export?format=csv|pdf` accepts the same filters and exports the complete filtered dataset, not just the preview page. More than 5,000 matching enrollments returns 413 and asks the caller to narrow filters; rows are not silently truncated. Empty exports contain headers/count information. Responses are downloads with `Cache-Control: no-store`. The CSV starts with a UTF-8 byte-order mark so Excel shows accented names (for example "José") correctly.

**Export auditing (ICI2-90).** Every successful export (csv or pdf) writes exactly one `audit_logs` row (`table_name='enrollment_report_exports'`, `operation='EXPORT'`, `actor_id` = caller) in the same transaction as the read. `new_data` holds only the row count, SHA-256 of the delivered file, format, the caller's campus scope and the filters used. It never contains student names, student numbers or missing-document lists. Rejected requests (403, 400, 413) write no row. This mirrors the cashier roster export.

**Previews are exempt from auditing.** `GET /api/reports/enrollments` returns the same fields, but only up to 100 rows per page, is interactive and needs a person to page through, and is already limited to the caller's campus scope. Auditing every page view would flood `audit_logs` with low-value rows and bury the bulk-extraction events that matter; a file export is the point where up to 5,000 records leave the system as a portable, unscoped copy. If preview scraping becomes a concern, rate limiting is the better control than per-page audit rows.

CSV uses UTF-8, quoted cells, escaped quotes and formula-prefix escaping after leading whitespace/control/format characters. Multiline fields stay quoted. PDF uses Apache PDFBox 3.0.6 (Apache License) and its bundled Liberation Sans font for portable embedded Unicode Latin text. A code point unavailable in that font is represented explicitly as `[U+XXXX]`; the CSV always retains the original Unicode. These are operational reports, not certified Form 137/TOR templates.

## Explicit guidance policy and school days

`GET /api/guidance/policies/{campusId}/{termId}` is readable by scoped Guidance or policy-configuring staff. An unconfigured response has `configured:false, version:0`.

`PUT /api/guidance/policies/{campusId}/{termId}` requires `guidance.configure`, explicitly seeded for Registrar/Admin campus grants. Body:

```json
{
  "failingBelow": 75,
  "consecutiveAbsences": 3,
  "credentialsDueOn": "2026-11-25",
  "reason": "Illustrative policy only; replace with institution-approved values",
  "version": 0
}
```

The example is not an approved ICI rule. Each threshold/due date is nullable to disable that signal. Expected version 0 creates; each saved mutation increments the version. Stale writes return 409.

`GET /api/academic/school-days/{campusId}/{termId}` exposes the explicitly recorded calendar to authorized campus staff.

`PUT` at the same path requires `guidance.configure` and accepts at most 366 dates atomically:

```json
{
  "reason": "Illustrative school calendar",
  "days": [
    {"date":"2026-11-23","schoolDay":true,"version":0},
    {"date":"2026-11-24","schoolDay":false,"version":0}
  ]
}
```

Dates must lie within their term, cannot repeat in a batch, and use per-date expected versions. An existing day with attendance cannot become a non-school day. There is no assumption that weekends or holidays are non-school days: configure them explicitly if they lie inside a sequence used to assess consecutive absences.

## Daily academic attendance prerequisite

Daily academic attendance is distinct from orientation/first-week enrollment attendance; ICI2-33 never treats orientation records as daily absence evidence.

`POST /api/academic/daily-attendance/import` is a bounded JSON batch API (maximum 500 entries). Registrar with `enrollment.manage` can record within campus scope; a teacher needs `grades.encode` at that campus and a current assigned class membership for the enrollment. Body:

```json
{
  "entries": [
    {"enrollmentId":123,"date":"2026-11-23","status":"absent","note":"Fictional demonstration only","version":0}
  ]
}
```

Statuses are `present`, `absent` (unexcused) or `excused`. Each enrollment/date is unique and versioned. Attendance requires an explicit school day and an active provisional/enrolled standing. Duplicate batch keys, wrong school days, stale entries or unauthorized enrollment fail the entire transaction; no partial imports. The API does not accept spreadsheet files or invent unknown attendance.

`GET /api/academic/daily-attendance/{enrollmentId}` reads persisted records with actor/time metadata for scoped Registrar, assigned teacher or Guidance.

## Guidance risk assessment: ICI2-33

`GET /api/guidance/risks` requires `guidance.manage`. It accepts the report filters, `page/size`, and optional ISO `asOf` (defaults to the database current date). It returns active enrollments with:

- `signals`: `incomplete_grades`, `failing_grades`, `overdue_credentials`, `consecutive_unexcused_absences` where supported by data/policy;
- counts of unresolved submitted/finalized INC marks, failing numeric grades and the current consecutive unexcused absence streak;
- `unconfiguredSignals`, identifying missing thresholds, due dates or school-calendar gaps;
- `atRisk`, existing case metadata if any, filtered totals and `atRiskTotal`.

Draft grades are excluded. INC is based on authoritative submitted/finalized grade marks; finalized INC later resolved to a numeric value no longer flags. Numeric failing marks use the configured exclusive `failingBelow` threshold. Credentials become overdue strictly after the configured due date, with any mandatory document not Submitted.

For attendance, explicit non-school days preserve a streak. Present, excused or a missing attendance record on a school day reset it. A missing calendar date also resets it and emits `school_calendar_gaps`; no consecutive-absence claim spans unknown dates. Evaluation ends at the earlier of asOf and term end. At most 5,000 candidate enrollments and ten-year term spans are evaluated per request; narrow filters if 413 is returned. Guidance risk data can be displayed before policy configuration, with available INC signals and explicit unavailable-signal labels.

## Confidential counseling cases

All case routes require `guidance.manage` at the enrollment campus; Registrar report permission does not expose case notes.

- `POST /api/guidance/cases`: `{enrollmentId, reason}` creates one retained case per enrollment.
- `GET /api/guidance/cases?status=open|monitoring|closed&page=0&size=25`: scoped directory.
- `GET /api/guidance/cases/{id}`: case and attributed notes in creation order.
- `PUT /api/guidance/cases/{id}`: `{status, reason, version}` changes monitoring status with optimistic locking.
- `POST /api/guidance/cases/{id}/notes`: `{note, version}` appends an attributed note and increments case version. No messages are sent.
- `GET /api/guidance/cases/{id}/export?format=pdf|csv`: confidential case sheet including the same case/notes, scoped and no-store. Cases above 1,000 notes reject with 413 rather than truncating history.

Notes are append-only through this API. Changes to configuration, calendar, attendance, cases and notes use existing metadata and immutable audit triggers. The frontend teams can consume these contracts without reading private database credentials or supplying acting-user identity.

## Verification

`ReportingGuidanceTest` covers nine scenarios using real local HTTP and PostgreSQL: combined filters/counts/pagination and same-grant campus scope; CSV formula protection and accented Unicode PDF text; configured INC/failure/document/absence signals; assigned-teacher attendance imports with atomic rollback and stale versions; confidential case note persistence/audit/export; missing attendance/calendar resets; measured proportional-font PDF wrapping and multipage retention; concurrent calendar editing with reverse enrollment/date ordering; and 5,002-row datasets rejecting oversized exports while preview totals stay complete. The focused eight-scenario run and final bounded-export scenario passed independently; the consolidated backend suite verifies them together.

Attendance imports acquire all shared school-day rows in campus/term/date order before enrollment locks, matching calendar updates and avoiding date/enrollment lock-order inversion. PDFs wrap using measured font widths inside the page margins and include the generation time in UTC.
